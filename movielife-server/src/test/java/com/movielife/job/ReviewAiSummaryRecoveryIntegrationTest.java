package com.movielife.job;

import com.movielife.ai.AiClientResponse;
import com.movielife.constants.RedisConstants;
import com.movielife.entity.AiSummaryTask;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.service.AiReviewSummaryClient;
import com.movielife.service.AiSummaryPersistenceService;
import com.movielife.service.AiSummaryStreamPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.domain.Range;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "movielife.startup-check.enabled=false",
                "movielife.ai.review-summary.enabled=true",
                "movielife.ai.review-summary.pending-claim-min-idle=0s",
                "movielife.ai.review-summary.worker-initial-delay-ms=3600000",
                "movielife.ai.review-summary.pending-recovery-initial-delay-ms=3600000",
                "movielife.ai.review-summary.reconciliation-initial-delay-ms=3600000"
        })
class ReviewAiSummaryRecoveryIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(14_600_000_000L);

    @Autowired
    private ReviewAiSummaryJob job;
    @Autowired
    private AiSummaryPersistenceService persistenceService;
    @Autowired
    private AiSummaryStreamPublisher streamPublisher;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockBean
    private AiReviewSummaryClient client;

    private final List<Long> targetIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        redisTemplate.delete(RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY);
        when(client.generate(any())).thenReturn(new AiClientResponse(
                "{\"summary\":\"真实链路恢复成功\",\"pros\":[\"稳定\"],"
                        + "\"cons\":[],\"suitableFor\":[\"普通用户\"],"
                        + "\"warningPoints\":[]}", "recovery-model"));
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY);
        for (Long targetId : targetIds) {
            redisTemplate.delete(RedisConstants.AI_REVIEW_SUMMARY_KEY + "MOVIE:" + targetId);
            redisTemplate.delete(RedisConstants.AI_REVIEW_SUMMARY_KEY + "ACTIVITY:" + targetId);
            jdbcTemplate.update("DELETE FROM tb_ai_summary_task WHERE target_id=?", targetId);
            jdbcTemplate.update("DELETE FROM tb_ai_review_summary WHERE target_id=?", targetId);
            jdbcTemplate.update("DELETE FROM tb_review WHERE target_id=?", targetId);
            jdbcTemplate.update("DELETE FROM tb_movie WHERE id=?", targetId);
            jdbcTemplate.update("DELETE FROM tb_activity WHERE id=?", targetId);
        }
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM tb_user WHERE id=?", userId);
        }
    }

    @Test
    void reconciliationAndDuplicateMessagesShouldKeepSameIdTargetsIndependent() {
        long targetId = createSameIdMovieAndActivityWithReviews();
        LocalDateTime now = LocalDateTime.now();
        persistenceService.markDirty(AiSummaryTargetType.MOVIE, targetId, now);
        persistenceService.markDirty(AiSummaryTargetType.ACTIVITY, targetId, now);

        job.reconcileRecoverableTasks();
        job.reconcileRecoverableTasks();
        assertThat(redisTemplate.opsForStream().size(
                RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY)).isEqualTo(4L);

        job.consumeNewMessages();

        AiSummaryTask movie = persistenceService.findTask(AiSummaryTargetType.MOVIE, targetId);
        AiSummaryTask activity = persistenceService.findTask(AiSummaryTargetType.ACTIVITY, targetId);
        assertThat(movie.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(activity.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(movie.getCurrentSummaryId()).isNotEqualTo(activity.getCurrentSummaryId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_ai_review_summary WHERE target_id=?",
                Integer.class, targetId)).isEqualTo(2);
        assertThat(redisTemplate.opsForStream().pending(
                RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY,
                RedisConstants.AI_REVIEW_SUMMARY_STREAM_GROUP)
                .getTotalPendingMessages()).isZero();
        verify(client, times(2)).generate(any());
    }

    @Test
    void pendingMessageOwnedByStoppedConsumerShouldBeClaimedAndCompleted() {
        long targetId = createMovieWithReviews();
        persistenceService.markDirty(AiSummaryTargetType.MOVIE, targetId, LocalDateTime.now());
        streamPublisher.publish(AiSummaryTargetType.MOVIE, targetId);

        redisTemplate.opsForStream().read(
                Consumer.from(RedisConstants.AI_REVIEW_SUMMARY_STREAM_GROUP, "stopped-worker"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY,
                        ReadOffset.lastConsumed()));
        assertThat(redisTemplate.opsForStream().pending(
                RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY,
                RedisConstants.AI_REVIEW_SUMMARY_STREAM_GROUP)
                .getTotalPendingMessages()).isEqualTo(1L);

        job.recoverPendingMessages();

        assertThat(persistenceService.findTask(AiSummaryTargetType.MOVIE, targetId).getStatus())
                .isEqualTo("SUCCEEDED");
        assertThat(redisTemplate.opsForStream().pending(
                RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY,
                RedisConstants.AI_REVIEW_SUMMARY_STREAM_GROUP)
                .getTotalPendingMessages()).isZero();
    }

    @Test
    void concurrentWorkersShouldAllowOnlyOneLeaseOwner() throws Exception {
        long targetId = createTargetId();
        LocalDateTime now = LocalDateTime.now();
        persistenceService.markDirty(AiSummaryTargetType.MOVIE, targetId, now);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<AiSummaryTask>>> claims = new ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                final int worker = index;
                claims.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return persistenceService.claim(AiSummaryTargetType.MOVIE, targetId,
                            "concurrent-worker-" + worker, now.plusSeconds(1), now.plusMinutes(2));
                }));
            }
            ready.await();
            start.countDown();

            int acquired = 0;
            for (Future<Optional<AiSummaryTask>> claim : claims) {
                if (claim.get().isPresent()) {
                    acquired++;
                }
            }
            assertThat(acquired).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reconciliationShouldRecoverDueRetryAndExpiredLeaseButNotActiveLease() {
        long expiredId = createTargetId();
        long retryId = createTargetId();
        long activeId = createTargetId();
        LocalDateTime now = LocalDateTime.now();

        persistenceService.markDirty(AiSummaryTargetType.MOVIE, expiredId, now.minusMinutes(10));
        persistenceService.claim(AiSummaryTargetType.MOVIE, expiredId, "expired-worker",
                now.minusMinutes(9), now.minusMinutes(8));

        persistenceService.markDirty(AiSummaryTargetType.ACTIVITY, retryId, now.minusMinutes(10));
        AiSummaryTask retry = persistenceService.claim(AiSummaryTargetType.ACTIVITY, retryId,
                "retry-worker", now.minusMinutes(9), now.minusMinutes(5)).orElseThrow(AssertionError::new);
        persistenceService.markRetry(AiSummaryTargetType.ACTIVITY, retryId,
                retry.getProcessingVersion(), "retry-worker", "AI_PROVIDER_UNAVAILABLE",
                now.minusMinutes(8), now.minusMinutes(1));

        persistenceService.markDirty(AiSummaryTargetType.MOVIE, activeId, now);
        persistenceService.claim(AiSummaryTargetType.MOVIE, activeId, "active-worker",
                now.plusSeconds(1), now.plusMinutes(2));

        job.reconcileRecoverableTasks();

        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().range(
                RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY, Range.unbounded());
        assertThat(records).hasSize(2);
        assertThat(records).extracting(record ->
                        Long.valueOf(String.valueOf(record.getValue().get("targetId"))))
                .containsExactlyInAnyOrder(expiredId, retryId)
                .doesNotContain(activeId);
    }

    private long createSameIdMovieAndActivityWithReviews() {
        long targetId = createTargetId();
        jdbcTemplate.update("INSERT INTO tb_movie(id, title, status) VALUES (?, ?, 2)",
                targetId, "Stage 14.6 movie");
        jdbcTemplate.update("INSERT INTO tb_activity(id, title, type, city, status) "
                        + "VALUES (?, ?, 'concert', 'STEP146', 1)",
                targetId, "Stage 14.6 activity");
        for (int index = 0; index < 3; index++) {
            long userId = createUser();
            insertReview(userId, "MOVIE", targetId, index);
            insertReview(userId, "ACTIVITY", targetId, index);
        }
        return targetId;
    }

    private long createMovieWithReviews() {
        long targetId = createTargetId();
        jdbcTemplate.update("INSERT INTO tb_movie(id, title, status) VALUES (?, ?, 2)",
                targetId, "Stage 14.6 pending movie");
        for (int index = 0; index < 3; index++) {
            insertReview(createUser(), "MOVIE", targetId, index);
        }
        return targetId;
    }

    private long createUser() {
        long userId = IDS.incrementAndGet();
        userIds.add(userId);
        jdbcTemplate.update("INSERT INTO tb_user(id, phone, nick_name) VALUES (?, ?, ?)",
                userId, "16" + userId, "Stage 14.6 user");
        return userId;
    }

    private void insertReview(long userId, String targetType, long targetId, int index) {
        jdbcTemplate.update("INSERT INTO tb_review(user_id, target_type, target_id, score, content) "
                        + "VALUES (?, ?, ?, ?, ?)",
                userId, targetType, targetId, index == 0 ? "4.0" : "4.5",
                "Stage 14.6 review " + index);
    }

    private long createTargetId() {
        long id = IDS.incrementAndGet();
        targetIds.add(id);
        return id;
    }
}
