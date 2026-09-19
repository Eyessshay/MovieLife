package com.movielife.job;

import com.movielife.config.AiReviewSummaryProperties;
import com.movielife.constants.RedisConstants;
import com.movielife.entity.AiSummaryTask;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.mapper.AiSummaryTaskMapper;
import com.movielife.service.AiSummaryStreamPublisher;
import com.movielife.service.ReviewAiSummaryWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewAiSummaryJob {

    private final String consumerName = buildConsumerName();

    private final AiReviewSummaryProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final AiSummaryStreamPublisher streamPublisher;
    private final ReviewAiSummaryWorker worker;
    private final AiSummaryTaskMapper taskMapper;

    @Scheduled(
            fixedDelayString = "${movielife.ai.review-summary.worker-fixed-delay-ms:1000}",
            initialDelayString = "${movielife.ai.review-summary.worker-initial-delay-ms:5000}")
    public void consumeNewMessages() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            streamPublisher.ensureGroup();
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                    Consumer.from(RedisConstants.AI_REVIEW_SUMMARY_STREAM_GROUP, consumerName),
                    StreamReadOptions.empty().count(properties.getStreamBatchSize()),
                    StreamOffset.create(RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY,
                            ReadOffset.lastConsumed()));
            if (records == null) {
                return;
            }
            for (MapRecord<String, Object, Object> record : records) {
                consume(record);
            }
        } catch (RuntimeException exception) {
            log.error("Failed to consume review AI summary stream", exception);
        }
    }

    @Scheduled(
            fixedDelayString = "${movielife.ai.review-summary.retry-fixed-delay-ms:5000}",
            initialDelayString = "${movielife.ai.review-summary.retry-initial-delay-ms:5000}")
    public void notifyReadyRetries() {
        if (!properties.isEnabled()) {
            return;
        }
        List<AiSummaryTask> targets;
        try {
            targets = taskMapper.selectRetryReadyTargets(
                    LocalDateTime.now(), properties.getRetryBatchSize());
        } catch (RuntimeException exception) {
            log.error("Failed to scan retry-ready review AI summary tasks", exception);
            return;
        }
        for (AiSummaryTask target : targets) {
            try {
                streamPublisher.publish(AiSummaryTargetType.valueOf(target.getTargetType()),
                        target.getTargetId());
            } catch (RuntimeException exception) {
                log.error("Failed to notify retry-ready review AI summary, targetType={}, targetId={}",
                        target.getTargetType(), target.getTargetId(), exception);
            }
        }
    }

    private void consume(MapRecord<String, Object, Object> record) {
        AiSummaryTask target = target(record.getValue());
        if (target == null) {
            acknowledge(record);
            return;
        }
        if (worker.process(AiSummaryTargetType.valueOf(target.getTargetType()), target.getTargetId())) {
            acknowledge(record);
        }
    }

    private AiSummaryTask target(Map<Object, Object> value) {
        Object type = value.get("targetType");
        Object id = value.get("targetId");
        AiSummaryTargetType targetType;
        try {
            targetType = AiSummaryTargetType.valueOf(String.valueOf(type));
        } catch (IllegalArgumentException exception) {
            return null;
        }
        try {
            long parsed = Long.parseLong(String.valueOf(id));
            if (parsed <= 0) {
                return null;
            }
            AiSummaryTask target = new AiSummaryTask();
            target.setTargetType(targetType.name());
            target.setTargetId(parsed);
            return target;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        redisTemplate.opsForStream().acknowledge(
                RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY,
                RedisConstants.AI_REVIEW_SUMMARY_STREAM_GROUP,
                record.getId());
    }

    private static String buildConsumerName() {
        String runtime = ManagementFactory.getRuntimeMXBean().getName().replaceAll("[^A-Za-z0-9_.-]", "_");
        return "review-ai-" + runtime + "-" + UUID.randomUUID();
    }
}
