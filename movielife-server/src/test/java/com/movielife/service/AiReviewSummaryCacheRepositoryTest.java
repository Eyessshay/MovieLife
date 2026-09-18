package com.movielife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.movielife.ai.AiReviewSummaryCacheValue;
import com.movielife.entity.AiReviewSummary;
import com.movielife.enums.AiSummaryTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiReviewSummaryCacheRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private ObjectMapper objectMapper;
    private AiReviewSummaryCacheRepository repository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        repository = new AiReviewSummaryCacheRepository(redisTemplate, objectMapper);
    }

    @Test
    void shouldWriteWholeImmutableSnapshotWithVersionedIdentity() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        AiReviewSummary summary = summary();

        repository.put(summary);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq("ai:review:summary:MOVIE:9"), json.capture(), ttl.capture());
        AiReviewSummaryCacheValue cached = objectMapper.readValue(
                json.getValue(), AiReviewSummaryCacheValue.class);
        assertThat(cached.getSummaryId()).isEqualTo(101L);
        assertThat(cached.getVersion()).isEqualTo(3);
        assertThat(cached.getTargetType()).isEqualTo("MOVIE");
        assertThat(ttl.getValue()).isBetween(Duration.ofMinutes(30), Duration.ofMinutes(35));
    }

    @Test
    void shouldTreatMalformedOrCrossTargetCacheAsMissAndDeleteIt() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        AiReviewSummaryCacheValue wrongTarget = new AiReviewSummaryCacheValue();
        wrongTarget.setSummaryId(1L);
        wrongTarget.setVersion(1);
        wrongTarget.setTargetType("ACTIVITY");
        wrongTarget.setTargetId(9L);
        when(valueOperations.get("ai:review:summary:MOVIE:9"))
                .thenReturn(objectMapper.writeValueAsString(wrongTarget));

        Optional<AiReviewSummaryCacheValue> result = repository.find(
                AiSummaryTargetType.MOVIE, 9L);

        assertThat(result).isEmpty();
        verify(redisTemplate).delete("ai:review:summary:MOVIE:9");
    }

    @Test
    void redisFailureShouldRemainARecoverableCacheMiss() {
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis unavailable"));

        assertThat(repository.find(AiSummaryTargetType.ACTIVITY, 8L)).isEmpty();

        repository.put(summary());
    }

    private AiReviewSummary summary() {
        AiReviewSummary summary = new AiReviewSummary();
        summary.setId(101L);
        summary.setTargetType("MOVIE");
        summary.setTargetId(9L);
        summary.setSummary("整体评价稳定");
        summary.setPros("[\"节奏紧凑\"]");
        summary.setCons("[]");
        summary.setSuitableFor("[\"剧情片观众\"]");
        summary.setWarningPoints("[]");
        summary.setSourceReviewCount(12);
        summary.setInputReviewCount(10);
        summary.setVersion(3);
        summary.setPromptVersion("review-summary-v1");
        summary.setCreateTime(LocalDateTime.of(2026, 9, 18, 12, 0));
        return summary;
    }
}
