package com.movielife.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.ai.AiReviewSummaryCacheValue;
import com.movielife.constants.RedisConstants;
import com.movielife.entity.AiReviewSummary;
import com.movielife.enums.AiSummaryTargetType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Repository
@RequiredArgsConstructor
public class AiReviewSummaryCacheRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public Optional<AiReviewSummaryCacheValue> find(AiSummaryTargetType targetType, Long targetId) {
        validateTarget(targetType, targetId);
        String key = key(targetType, targetId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            AiReviewSummaryCacheValue value = objectMapper.readValue(
                    json, AiReviewSummaryCacheValue.class);
            if (value.getSummaryId() == null
                    || value.getVersion() == null
                    || !targetType.name().equals(value.getTargetType())
                    || !targetId.equals(value.getTargetId())) {
                delete(targetType, targetId);
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (JsonProcessingException exception) {
            log.warn("Invalid AI review summary cache, deleting key={}", key);
            delete(targetType, targetId);
            return Optional.empty();
        } catch (RuntimeException exception) {
            log.warn("Redis AI summary read failed, MySQL fallback is required, key={}", key);
            return Optional.empty();
        }
    }

    public void put(AiReviewSummary summary) {
        validateSummary(summary);
        AiSummaryTargetType targetType = AiSummaryTargetType.valueOf(summary.getTargetType());
        String key = key(targetType, summary.getTargetId());
        try {
            AiReviewSummaryCacheValue value = toCacheValue(summary);
            long jitter = ThreadLocalRandom.current().nextLong(
                    RedisConstants.AI_REVIEW_SUMMARY_TTL_RANDOM_MINUTES + 1);
            Duration ttl = RedisConstants.AI_REVIEW_SUMMARY_TTL.plusMinutes(jitter);
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize AI review summary cache", exception);
        } catch (RuntimeException exception) {
            log.warn("Redis AI summary write failed, MySQL remains authoritative, key={}", key);
        }
    }

    public void delete(AiSummaryTargetType targetType, Long targetId) {
        validateTarget(targetType, targetId);
        String key = key(targetType, targetId);
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException exception) {
            log.warn("Redis AI summary delete failed, key={}", key);
        }
    }

    String key(AiSummaryTargetType targetType, Long targetId) {
        return RedisConstants.AI_REVIEW_SUMMARY_KEY + targetType.name() + ":" + targetId;
    }

    private AiReviewSummaryCacheValue toCacheValue(AiReviewSummary summary) {
        AiReviewSummaryCacheValue value = new AiReviewSummaryCacheValue();
        value.setSummaryId(summary.getId());
        value.setTargetType(summary.getTargetType());
        value.setTargetId(summary.getTargetId());
        value.setSummary(summary.getSummary());
        value.setPros(summary.getPros());
        value.setCons(summary.getCons());
        value.setSuitableFor(summary.getSuitableFor());
        value.setWarningPoints(summary.getWarningPoints());
        value.setSourceReviewCount(summary.getSourceReviewCount());
        value.setInputReviewCount(summary.getInputReviewCount());
        value.setVersion(summary.getVersion());
        value.setPromptVersion(summary.getPromptVersion());
        value.setGeneratedAt(summary.getCreateTime());
        return value;
    }

    private void validateSummary(AiReviewSummary summary) {
        if (summary == null || summary.getId() == null || summary.getVersion() == null
                || summary.getTargetId() == null || summary.getTargetId() <= 0
                || summary.getTargetType() == null) {
            throw new IllegalArgumentException("Only a persisted AI summary can be cached");
        }
        try {
            AiSummaryTargetType.valueOf(summary.getTargetType());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported AI summary target type", exception);
        }
    }

    private void validateTarget(AiSummaryTargetType targetType, Long targetId) {
        if (targetType == null || targetId == null || targetId <= 0) {
            throw new IllegalArgumentException("AI summary target is invalid");
        }
    }
}
