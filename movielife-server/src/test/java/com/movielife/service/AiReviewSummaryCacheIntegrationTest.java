package com.movielife.service;

import com.movielife.ai.AiReviewSummaryCacheValue;
import com.movielife.constants.RedisConstants;
import com.movielife.entity.AiReviewSummary;
import com.movielife.enums.AiSummaryTargetType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "movielife.startup-check.enabled=false")
class AiReviewSummaryCacheIntegrationTest {

    private static final long TARGET_ID = 991498L;
    private static final String KEY = RedisConstants.AI_REVIEW_SUMMARY_KEY
            + "MOVIE:" + TARGET_ID;

    @Autowired
    private AiReviewSummaryCacheRepository repository;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(KEY);
    }

    @Test
    void shouldRoundTripCompleteSnapshotWithBoundedTtl() {
        AiReviewSummary summary = new AiReviewSummary();
        summary.setId(9914981L);
        summary.setTargetType("MOVIE");
        summary.setTargetId(TARGET_ID);
        summary.setSummary("真实 Redis 缓存联调");
        summary.setPros("[\"稳定\"]");
        summary.setCons("[]");
        summary.setSuitableFor("[]");
        summary.setWarningPoints("[]");
        summary.setSourceReviewCount(3);
        summary.setInputReviewCount(3);
        summary.setVersion(1);
        summary.setPromptVersion("review-summary-v1");
        summary.setCreateTime(LocalDateTime.now().withNano(0));

        repository.put(summary);
        Optional<AiReviewSummaryCacheValue> cached = repository.find(
                AiSummaryTargetType.MOVIE, TARGET_ID);

        assertThat(cached).isPresent();
        assertThat(cached.get().getSummaryId()).isEqualTo(summary.getId());
        assertThat(cached.get().getSummary()).isEqualTo(summary.getSummary());
        Long ttl = redisTemplate.getExpire(KEY, TimeUnit.MINUTES);
        assertThat(ttl).isNotNull().isBetween(29L, 35L);
    }
}
