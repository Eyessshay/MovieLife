package com.movielife.service;

import com.movielife.constants.RedisConstants;
import com.movielife.enums.AiSummaryTargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AiSummaryStreamPublisher {

    private static final long MAX_STREAM_LENGTH = 10000L;

    private final StringRedisTemplate redisTemplate;

    public RecordId publish(AiSummaryTargetType targetType, Long targetId) {
        if (targetType == null || targetId == null || targetId <= 0) {
            throw new IllegalArgumentException("AI summary stream target is invalid");
        }
        ensureGroup();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("targetType", targetType.name());
        fields.put("targetId", String.valueOf(targetId));
        RecordId recordId = redisTemplate.opsForStream().add(
                StreamRecords.mapBacked(fields)
                        .withStreamKey(RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY));
        if (recordId == null) {
            throw new IllegalStateException("Redis did not return AI summary stream record id");
        }
        redisTemplate.opsForStream().trim(
                RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY, MAX_STREAM_LENGTH, true);
        return recordId;
    }

    public void ensureGroup() {
        try {
            redisTemplate.execute((RedisCallback<String>) connection ->
                    connection.streamCommands().xGroupCreate(
                            RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY.getBytes(StandardCharsets.UTF_8),
                            RedisConstants.AI_REVIEW_SUMMARY_STREAM_GROUP,
                            ReadOffset.from("0-0"),
                            true));
        } catch (RuntimeException exception) {
            if (!containsBusyGroup(exception)) {
                throw exception;
            }
        }
    }

    private boolean containsBusyGroup(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
