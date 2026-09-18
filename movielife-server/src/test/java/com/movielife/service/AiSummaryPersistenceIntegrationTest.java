package com.movielife.service;

import com.movielife.ai.AiSummaryPublication;
import com.movielife.entity.AiReviewSummary;
import com.movielife.entity.AiSummaryTask;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.mapper.AiReviewSummaryMapper;
import com.movielife.mapper.AiSummaryTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "movielife.startup-check.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.redisson.spring.starter.RedissonAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        }
)
@Transactional
class AiSummaryPersistenceIntegrationTest {

    private static final String FINGERPRINT =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @MockBean
    private StringRedisTemplate redisTemplate;

    @Autowired
    private AiSummaryPersistenceService service;
    @Autowired
    private AiSummaryTaskMapper taskMapper;
    @Autowired
    private AiReviewSummaryMapper summaryMapper;

    @Test
    void movieAndActivityWithSameIdShouldHaveIndependentTasks() {
        LocalDateTime now = time(0);

        service.markDirty(AiSummaryTargetType.MOVIE, 991401L, now);
        service.markDirty(AiSummaryTargetType.ACTIVITY, 991401L, now);

        assertThat(service.findTask(AiSummaryTargetType.MOVIE, 991401L).getDirtyVersion())
                .isEqualTo(1L);
        assertThat(service.findTask(AiSummaryTargetType.ACTIVITY, 991401L).getDirtyVersion())
                .isEqualTo(1L);
    }

    @Test
    void leaseShouldBeExclusiveRenewableAndRecoverableAfterExpiry() {
        long targetId = 991402L;
        LocalDateTime now = time(0);
        service.markDirty(AiSummaryTargetType.MOVIE, targetId, now);

        AiSummaryTask claimed = service.claim(AiSummaryTargetType.MOVIE, targetId,
                "worker-a", now.plusSeconds(1), now.plusMinutes(2)).orElseThrow(AssertionError::new);
        assertThat(claimed.getProcessingVersion()).isEqualTo(1L);
        assertThat(service.claim(AiSummaryTargetType.MOVIE, targetId, "worker-b",
                now.plusSeconds(2), now.plusMinutes(3))).isEmpty();
        assertThat(service.renewLease(AiSummaryTargetType.MOVIE, targetId, "worker-b",
                now.plusSeconds(3), now.plusMinutes(4))).isFalse();
        assertThat(service.renewLease(AiSummaryTargetType.MOVIE, targetId, "worker-a",
                now.plusSeconds(3), now.plusMinutes(4))).isTrue();

        Optional<AiSummaryTask> recovered = service.claim(AiSummaryTargetType.MOVIE, targetId,
                "worker-b", now.plusMinutes(5), now.plusMinutes(7));
        assertThat(recovered).isPresent();
        assertThat(recovered.get().getLeaseOwner()).isEqualTo("worker-b");
        assertThat(recovered.get().getAttemptCount()).isEqualTo(2);
    }

    @Test
    void retryShouldOnlyBecomeClaimableWhenDue() {
        long targetId = 991403L;
        LocalDateTime now = time(0);
        service.markDirty(AiSummaryTargetType.ACTIVITY, targetId, now);
        AiSummaryTask task = service.claim(AiSummaryTargetType.ACTIVITY, targetId,
                "worker-a", now.plusSeconds(1), now.plusMinutes(2)).orElseThrow(AssertionError::new);

        assertThat(service.markRetry(AiSummaryTargetType.ACTIVITY, targetId,
                task.getProcessingVersion(), "worker-a", "MODEL_TIMEOUT",
                now.plusSeconds(2), now.plusMinutes(10))).isTrue();
        assertThat(service.claim(AiSummaryTargetType.ACTIVITY, targetId, "worker-b",
                now.plusMinutes(9), now.plusMinutes(11))).isEmpty();
        assertThat(service.claim(AiSummaryTargetType.ACTIVITY, targetId, "worker-b",
                now.plusMinutes(10), now.plusMinutes(12))).isPresent();
    }

    @Test
    void publishShouldKeepNewDirtyVersionPendingAndAdvanceCurrentPointerAtomically() {
        long targetId = 991404L;
        LocalDateTime now = time(0);
        service.markDirty(AiSummaryTargetType.MOVIE, targetId, now);
        AiSummaryTask claimed = service.claim(AiSummaryTargetType.MOVIE, targetId,
                "worker-a", now.plusSeconds(1), now.plusMinutes(5)).orElseThrow(AssertionError::new);
        service.markDirty(AiSummaryTargetType.MOVIE, targetId, now.plusSeconds(2));

        AiReviewSummary summary = service.publish(publication(
                AiSummaryTargetType.MOVIE, targetId, claimed.getProcessingVersion()),
                "worker-a", now.plusSeconds(3));

        AiSummaryTask finalTask = service.findTask(AiSummaryTargetType.MOVIE, targetId);
        assertThat(finalTask.getDirtyVersion()).isEqualTo(2L);
        assertThat(finalTask.getProcessedVersion()).isEqualTo(1L);
        assertThat(finalTask.getStatus()).isEqualTo("PENDING");
        assertThat(finalTask.getCurrentSummaryId()).isEqualTo(summary.getId());
        assertThat(service.findCurrentSummary(AiSummaryTargetType.MOVIE, targetId).getId())
                .isEqualTo(summary.getId());
    }

    @Test
    void identicalFingerprintShouldReuseCommittedSummary() {
        long targetId = 991405L;
        LocalDateTime now = time(0);
        service.markDirty(AiSummaryTargetType.MOVIE, targetId, now);
        AiSummaryTask firstClaim = service.claim(AiSummaryTargetType.MOVIE, targetId,
                "worker-a", now.plusSeconds(1), now.plusMinutes(5)).orElseThrow(AssertionError::new);
        AiReviewSummary first = service.publish(publication(
                AiSummaryTargetType.MOVIE, targetId, firstClaim.getProcessingVersion()),
                "worker-a", now.plusSeconds(2));

        service.markDirty(AiSummaryTargetType.MOVIE, targetId, now.plusSeconds(3));
        AiSummaryTask secondClaim = service.claim(AiSummaryTargetType.MOVIE, targetId,
                "worker-b", now.plusSeconds(4), now.plusMinutes(6)).orElseThrow(AssertionError::new);
        AiReviewSummary second = service.publish(publication(
                AiSummaryTargetType.MOVIE, targetId, secondClaim.getProcessingVersion()),
                "worker-b", now.plusSeconds(5));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(summaryMapper.selectCount(null)).isEqualTo(1L);
        AiSummaryTask task = service.findTask(AiSummaryTargetType.MOVIE, targetId);
        assertThat(task.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(task.getProcessedVersion()).isEqualTo(2L);
        assertThat(task.getLastSummaryVersion()).isEqualTo(1);
    }

    @Test
    void insufficientResultShouldNotConsumeChangesArrivingDuringProcessing() {
        long targetId = 991406L;
        LocalDateTime now = time(0);
        service.markDirty(AiSummaryTargetType.ACTIVITY, targetId, now);
        AiSummaryTask claimed = service.claim(AiSummaryTargetType.ACTIVITY, targetId,
                "worker-a", now.plusSeconds(1), now.plusMinutes(5)).orElseThrow(AssertionError::new);
        service.markDirty(AiSummaryTargetType.ACTIVITY, targetId, now.plusSeconds(2));

        assertThat(service.markInsufficient(AiSummaryTargetType.ACTIVITY, targetId,
                claimed.getProcessingVersion(), "worker-a", now.plusSeconds(3))).isTrue();

        AiSummaryTask task = service.findTask(AiSummaryTargetType.ACTIVITY, targetId);
        assertThat(task.getStatus()).isEqualTo("PENDING");
        assertThat(task.getProcessedVersion()).isEqualTo(1L);
        assertThat(task.getDirtyVersion()).isEqualTo(2L);
    }

    @Test
    void oldFailureShouldNotDelayOrKillANewerDirtyVersion() {
        long retryTargetId = 991409L;
        long deadTargetId = 991410L;
        LocalDateTime now = time(0);

        AiSummaryTask retryClaim = dirtyClaimAndDirtyAgain(retryTargetId, now, "worker-r");
        assertThat(service.markRetry(AiSummaryTargetType.MOVIE, retryTargetId,
                retryClaim.getProcessingVersion(), "worker-r", "MODEL_TIMEOUT",
                now.plusSeconds(3), now.plusMinutes(10))).isTrue();
        AiSummaryTask retryTask = service.findTask(AiSummaryTargetType.MOVIE, retryTargetId);
        assertThat(retryTask.getStatus()).isEqualTo("PENDING");
        assertThat(retryTask.getNextRetryTime()).isNull();
        assertThat(retryTask.getAttemptCount()).isZero();

        AiSummaryTask deadClaim = dirtyClaimAndDirtyAgain(deadTargetId, now, "worker-d");
        assertThat(service.markDead(AiSummaryTargetType.MOVIE, deadTargetId,
                deadClaim.getProcessingVersion(), "worker-d", "INVALID_MODEL_RESPONSE",
                now.plusSeconds(3))).isTrue();
        AiSummaryTask deadTask = service.findTask(AiSummaryTargetType.MOVIE, deadTargetId);
        assertThat(deadTask.getStatus()).isEqualTo("PENDING");
        assertThat(deadTask.getLastErrorCode()).isNull();
        assertThat(deadTask.getAttemptCount()).isZero();
    }

    @Test
    void expiredOrWrongLeaseMustNotPublish() {
        long targetId = 991407L;
        LocalDateTime now = time(0);
        service.markDirty(AiSummaryTargetType.MOVIE, targetId, now);
        AiSummaryTask task = service.claim(AiSummaryTargetType.MOVIE, targetId,
                "worker-a", now.plusSeconds(1), now.plusMinutes(2)).orElseThrow(AssertionError::new);
        AiSummaryPublication publication = publication(
                AiSummaryTargetType.MOVIE, targetId, task.getProcessingVersion());

        assertThatThrownBy(() -> service.publish(publication, "worker-b", now.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.publish(publication, "worker-a", now.plusMinutes(3)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(summaryMapper.selectCount(null)).isZero();
    }

    @Test
    void errorCodeMustBeSanitizedBeforePersistence() {
        long targetId = 991408L;
        LocalDateTime now = time(0);
        service.markDirty(AiSummaryTargetType.MOVIE, targetId, now);
        AiSummaryTask task = service.claim(AiSummaryTargetType.MOVIE, targetId,
                "worker-a", now.plusSeconds(1), now.plusMinutes(2)).orElseThrow(AssertionError::new);

        assertThatThrownBy(() -> service.markDead(AiSummaryTargetType.MOVIE, targetId,
                task.getProcessingVersion(), "worker-a", "provider said: secret key=123",
                now.plusSeconds(2))).isInstanceOf(IllegalArgumentException.class);
        assertThat(taskMapper.selectByTarget("MOVIE", targetId).getStatus())
                .isEqualTo("PROCESSING");
    }

    private AiSummaryPublication publication(AiSummaryTargetType targetType,
                                             long targetId,
                                             long sourceVersion) {
        AiSummaryPublication publication = new AiSummaryPublication();
        publication.setTargetType(targetType);
        publication.setTargetId(targetId);
        publication.setSourceVersion(sourceVersion);
        publication.setPromptVersion("review-summary-v1");
        publication.setSourceFingerprint(FINGERPRINT);
        publication.setSourceReviewCount(5);
        publication.setInputReviewCount(5);
        publication.setSummary("整体反馈稳定");
        publication.setPros("[\"节奏紧凑\"]");
        publication.setCons("[\"结尾仓促\"]");
        publication.setSuitableFor("[\"剧情片观众\"]");
        publication.setWarningPoints("[]");
        publication.setModelName("test-model");
        return publication;
    }

    private AiSummaryTask dirtyClaimAndDirtyAgain(long targetId,
                                                  LocalDateTime now,
                                                  String worker) {
        service.markDirty(AiSummaryTargetType.MOVIE, targetId, now);
        AiSummaryTask claimed = service.claim(AiSummaryTargetType.MOVIE, targetId,
                worker, now.plusSeconds(1), now.plusMinutes(5)).orElseThrow(AssertionError::new);
        service.markDirty(AiSummaryTargetType.MOVIE, targetId, now.plusSeconds(2));
        return claimed;
    }

    private LocalDateTime time(int minute) {
        return LocalDateTime.of(2026, 9, 18, 10, minute, 0);
    }
}
