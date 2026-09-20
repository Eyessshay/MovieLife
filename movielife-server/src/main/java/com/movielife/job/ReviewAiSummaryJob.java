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
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.domain.Range;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
            fixedDelayString = "${movielife.ai.review-summary.pending-recovery-fixed-delay-ms:5000}",
            initialDelayString = "${movielife.ai.review-summary.pending-recovery-initial-delay-ms:10000}")
    public void recoverPendingMessages() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            streamPublisher.ensureGroup();
            PendingMessages pending = redisTemplate.opsForStream().pending(
                    RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY,
                    RedisConstants.AI_REVIEW_SUMMARY_STREAM_GROUP,
                    Range.unbounded(), properties.getRecoveryBatchSize());
            if (pending == null || pending.isEmpty()) {
                return;
            }
            List<RecordId> ids = new ArrayList<>();
            for (PendingMessage message : pending) {
                if (message.getElapsedTimeSinceLastDelivery()
                        .compareTo(properties.getPendingClaimMinIdle()) >= 0) {
                    ids.add(message.getId());
                }
            }
            if (ids.isEmpty()) {
                return;
            }
            // Redisson 3.23.5 reads idleTime as XCLAIM's required min-idle argument.
            RedisStreamCommands.XClaimOptions claimOptions =
                    RedisStreamCommands.XClaimOptions
                            .minIdle(properties.getPendingClaimMinIdle())
                            .ids(ids)
                            .idle(properties.getPendingClaimMinIdle());
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().claim(
                    RedisConstants.AI_REVIEW_SUMMARY_STREAM_KEY,
                    RedisConstants.AI_REVIEW_SUMMARY_STREAM_GROUP,
                    consumerName,
                    claimOptions);
            if (records != null) {
                for (MapRecord<String, Object, Object> record : records) {
                    consume(record);
                }
            }
        } catch (RuntimeException exception) {
            log.error("Failed to recover pending review AI summary messages", exception);
        }
    }

    @Scheduled(
            fixedDelayString = "${movielife.ai.review-summary.reconciliation-fixed-delay-ms:30000}",
            initialDelayString = "${movielife.ai.review-summary.reconciliation-initial-delay-ms:15000}")
    public void reconcileRecoverableTasks() {
        if (!properties.isEnabled()) {
            return;
        }
        List<AiSummaryTask> targets;
        try {
            targets = taskMapper.selectRecoverableTargets(
                    LocalDateTime.now(), properties.getRecoveryBatchSize());
        } catch (RuntimeException exception) {
            log.error("Failed to reconcile recoverable review AI summary tasks", exception);
            return;
        }
        for (AiSummaryTask target : targets) {
            try {
                streamPublisher.publish(AiSummaryTargetType.valueOf(target.getTargetType()),
                        target.getTargetId());
            } catch (RuntimeException exception) {
                log.error("Failed to notify recoverable review AI summary, targetType={}, targetId={}",
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
