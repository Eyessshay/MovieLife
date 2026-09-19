package com.movielife.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.ai.AiClientResponse;
import com.movielife.constants.RedisConstants;
import com.movielife.dto.CreateReviewDTO;
import com.movielife.dto.UpdateReviewDTO;
import com.movielife.dto.UserDTO;
import com.movielife.entity.AiSummaryTask;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.job.ReviewAiSummaryJob;
import com.movielife.service.AiReviewSummaryClient;
import com.movielife.service.AiSummaryPersistenceService;
import com.movielife.service.ReviewService;
import com.movielife.utils.UserHolder;
import com.movielife.vo.ReviewVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "movielife.startup-check.enabled=false",
                "movielife.ai.review-summary.enabled=true",
                "movielife.ai.review-summary.worker-initial-delay-ms=3600000",
                "movielife.ai.review-summary.retry-initial-delay-ms=3600000"
        })
class ActivityReviewAiSummaryHttpIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(14_500_000_000L);

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private ReviewAiSummaryJob job;
    @Autowired
    private AiSummaryPersistenceService persistenceService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private AiReviewSummaryClient client;

    private long activityId;
    private final long[] userIds = new long[3];
    private final List<Long> reviewIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        redisTemplate.delete(RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY);
        activityId = IDS.incrementAndGet();
        jdbcTemplate.update("INSERT INTO tb_activity(id, title, type, city, status) "
                        + "VALUES (?, ?, 'concert', 'STEP145', 1)",
                activityId, "Step 14.5 AI activity");
        for (int index = 0; index < userIds.length; index++) {
            userIds[index] = IDS.incrementAndGet();
            jdbcTemplate.update("INSERT INTO tb_user(id, phone, nick_name) VALUES (?, ?, ?)",
                    userIds[index], "15" + userIds[index], "Step 14.5 user " + index);
        }
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
        for (Long reviewId : reviewIds) {
            redisTemplate.delete(RedisConstants.REVIEW_LIKE_KEY + reviewId);
        }
        redisTemplate.delete(RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY);
        redisTemplate.delete(RedisConstants.AI_REVIEW_SUMMARY_KEY + "ACTIVITY:" + activityId);
        jdbcTemplate.update("DELETE FROM tb_ai_summary_task "
                + "WHERE target_type='ACTIVITY' AND target_id=?", activityId);
        jdbcTemplate.update("DELETE FROM tb_ai_review_summary "
                + "WHERE target_type='ACTIVITY' AND target_id=?", activityId);
        jdbcTemplate.update("DELETE FROM tb_review WHERE target_type='ACTIVITY' AND target_id=?",
                activityId);
        jdbcTemplate.update("DELETE FROM tb_activity WHERE id=?", activityId);
        for (long userId : userIds) {
            jdbcTemplate.update("DELETE FROM tb_user WHERE id=?", userId);
        }
    }

    @Test
    void activeActivityShouldGenerateWhileOfflineActivityHidesButKeepsHistory() throws Exception {
        for (int index = 0; index < userIds.length; index++) {
            login(userIds[index]);
            ReviewVO review = reviewService.createReview(review(index));
            reviewIds.add(review.getId());
        }
        UserHolder.removeUser();

        AiSummaryTask pending = persistenceService.findTask(AiSummaryTargetType.ACTIVITY, activityId);
        assertThat(pending.getStatus()).isEqualTo("PENDING");
        assertThat(pending.getDirtyVersion()).isEqualTo(3L);
        assertThat(redisTemplate.opsForStream().size(RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY))
                .isEqualTo(3L);

        when(client.generate(any())).thenReturn(new AiClientResponse(
                "{\"summary\":\"现场口碑良好\",\"pros\":[\"互动自然\"],"
                        + "\"cons\":[\"入场稍慢\"],\"suitableFor\":[\"现场演出观众\"],"
                        + "\"warningPoints\":[]}", "stub-model"));
        job.consumeNewMessages();

        AiSummaryTask completed = persistenceService.findTask(AiSummaryTargetType.ACTIVITY, activityId);
        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(completed.getProcessedVersion()).isEqualTo(3L);
        assertThat(completed.getCurrentSummaryId()).isNotNull();
        Long summaryId = completed.getCurrentSummaryId();
        assertReady();

        login(userIds[1]);
        reviewService.likeReview(reviewIds.get(0));
        reviewService.unlikeReview(reviewIds.get(0));
        assertThat(persistenceService.findTask(AiSummaryTargetType.ACTIVITY, activityId)
                .getDirtyVersion()).isEqualTo(3L);

        jdbcTemplate.update("UPDATE tb_activity SET status=2 WHERE id=?", activityId);
        assertActivitySummaryUnavailable();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_ai_review_summary WHERE id=?", Integer.class, summaryId))
                .isEqualTo(1);
        assertThat(redisTemplate.hasKey(
                RedisConstants.AI_REVIEW_SUMMARY_KEY + "ACTIVITY:" + activityId)).isTrue();

        login(userIds[0]);
        UpdateReviewDTO update = new UpdateReviewDTO();
        update.setScore(new BigDecimal("5.0"));
        update.setContent("活动下线后原作者仍可修改评价");
        update.setImages(Collections.emptyList());
        reviewService.updateReview(reviewIds.get(0), update);
        UserHolder.removeUser();
        job.consumeNewMessages();

        AiSummaryTask unchanged = persistenceService.findTask(
                AiSummaryTargetType.ACTIVITY, activityId);
        assertThat(unchanged.getDirtyVersion()).isEqualTo(3L);
        assertThat(unchanged.getCurrentSummaryId()).isEqualTo(summaryId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_ai_review_summary "
                        + "WHERE target_type='ACTIVITY' AND target_id=?",
                Integer.class, activityId)).isEqualTo(1);
        verify(client, times(1)).generate(any());
        assertActivitySummaryUnavailable();
    }

    private void assertReady() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/ai/summaries/ACTIVITY/" + activityId, String.class);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body.path("data").path("targetType").asText()).isEqualTo("ACTIVITY");
        assertThat(body.path("data").path("status").asText()).isEqualTo("READY");
        assertThat(body.path("data").path("summary").asText()).isEqualTo("现场口碑良好");
        assertThat(body.path("data").path("stale").asBoolean()).isFalse();
    }

    private void assertActivitySummaryUnavailable() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/ai/summaries/ACTIVITY/" + activityId, String.class);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body.path("code").asText()).isEqualTo("ACTIVITY_NOT_FOUND");
    }

    private void login(long userId) {
        UserDTO current = new UserDTO();
        current.setId(userId);
        UserHolder.saveUser(current);
    }

    private CreateReviewDTO review(int index) {
        CreateReviewDTO request = new CreateReviewDTO();
        request.setTargetType("ACTIVITY");
        request.setTargetId(activityId);
        request.setScore(new BigDecimal(index == 0 ? "4.0" : "4.5"));
        request.setContent("Step 14.5 activity review " + index);
        request.setImages(Collections.emptyList());
        return request;
    }
}
