package com.movielife.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.ai.AiClientResponse;
import com.movielife.constants.RedisConstants;
import com.movielife.dto.CreateReviewDTO;
import com.movielife.dto.UserDTO;
import com.movielife.entity.AiSummaryTask;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.job.MovieReviewAiSummaryJob;
import com.movielife.service.AiReviewSummaryClient;
import com.movielife.service.AiSummaryPersistenceService;
import com.movielife.service.ReviewService;
import com.movielife.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "movielife.startup-check.enabled=false",
                "movielife.ai.review-summary.enabled=true",
                "movielife.ai.review-summary.worker-initial-delay-ms=3600000",
                "movielife.ai.review-summary.retry-initial-delay-ms=3600000"
        })
class MovieReviewAiSummaryHttpIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(14_410_000_000L);

    @LocalServerPort
    private int port;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private MovieReviewAiSummaryJob job;
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

    private long movieId;
    private final long[] userIds = new long[3];

    @BeforeEach
    void setUp() {
        redisTemplate.delete(RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY);
        movieId = IDS.incrementAndGet();
        jdbcTemplate.update("INSERT INTO tb_movie(id, title, status) VALUES (?, ?, 2)",
                movieId, "Step 14.4 HTTP movie");
        for (int index = 0; index < userIds.length; index++) {
            userIds[index] = IDS.incrementAndGet();
            jdbcTemplate.update("INSERT INTO tb_user(id, phone, nick_name) VALUES (?, ?, ?)",
                    userIds[index], "14" + userIds[index], "Step 14.4 user " + index);
        }
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
        redisTemplate.delete(RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY);
        redisTemplate.delete(RedisConstants.AI_REVIEW_SUMMARY_KEY + "MOVIE:" + movieId);
        jdbcTemplate.update("DELETE FROM tb_ai_summary_task WHERE target_type='MOVIE' AND target_id=?", movieId);
        jdbcTemplate.update("DELETE FROM tb_ai_review_summary WHERE target_type='MOVIE' AND target_id=?", movieId);
        jdbcTemplate.update("DELETE FROM tb_review WHERE target_type='MOVIE' AND target_id=?", movieId);
        jdbcTemplate.update("DELETE FROM tb_movie WHERE id=?", movieId);
        for (long userId : userIds) {
            jdbcTemplate.update("DELETE FROM tb_user WHERE id=?", userId);
        }
    }

    @Test
    void committedReviewsShouldFlowThroughStreamWorkerMysqlCacheAndAnonymousHttp() throws Exception {
        for (int index = 0; index < userIds.length; index++) {
            UserDTO current = new UserDTO();
            current.setId(userIds[index]);
            UserHolder.saveUser(current);
            reviewService.createReview(review(index));
        }
        UserHolder.removeUser();

        AiSummaryTask pending = persistenceService.findTask(AiSummaryTargetType.MOVIE, movieId);
        assertThat(pending.getStatus()).isEqualTo("PENDING");
        assertThat(pending.getDirtyVersion()).isEqualTo(3L);
        assertThat(redisTemplate.opsForStream().size(RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY))
                .isEqualTo(3L);

        when(client.generate(any())).thenReturn(new AiClientResponse(
                "{\"summary\":\"整体口碑良好\",\"pros\":[\"表演自然\"],"
                        + "\"cons\":[\"节奏稍慢\"],\"suitableFor\":[\"剧情片观众\"],"
                        + "\"warningPoints\":[]}", "stub-model"));
        job.consumeNewMessages();

        AiSummaryTask completed = persistenceService.findTask(AiSummaryTargetType.MOVIE, movieId);
        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(completed.getProcessedVersion()).isEqualTo(3L);
        assertThat(completed.getCurrentSummaryId()).isNotNull();
        assertThat(redisTemplate.opsForStream().pending(
                RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY,
                RedisConstants.AI_REVIEW_SUMMARY_STREAM_GROUP).getTotalPendingMessages()).isZero();
        assertThat(redisTemplate.hasKey(
                RedisConstants.AI_REVIEW_SUMMARY_KEY + "MOVIE:" + movieId)).isTrue();

        ResponseEntity<String> response = new RestTemplate().getForEntity(
                "http://127.0.0.1:" + port + "/api/ai/summaries/MOVIE/" + movieId,
                String.class);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(body.path("data").path("status").asText()).isEqualTo("READY");
        assertThat(body.path("data").path("summary").asText()).isEqualTo("整体口碑良好");
        assertThat(body.path("data").path("pros").get(0).asText()).isEqualTo("表演自然");
        assertThat(body.path("data").path("stale").asBoolean()).isFalse();
    }

    private CreateReviewDTO review(int index) {
        CreateReviewDTO request = new CreateReviewDTO();
        request.setTargetType("MOVIE");
        request.setTargetId(movieId);
        request.setScore(new BigDecimal(index == 0 ? "4.0" : "4.5"));
        request.setContent("Step 14.4 movie review " + index);
        request.setImages(Collections.emptyList());
        return request;
    }
}
