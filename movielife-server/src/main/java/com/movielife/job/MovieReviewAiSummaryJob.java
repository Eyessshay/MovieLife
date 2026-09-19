package com.movielife.job;

import com.movielife.config.AiReviewSummaryProperties;
import com.movielife.constants.RedisConstants;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.mapper.AiSummaryTaskMapper;
import com.movielife.service.AiSummaryStreamPublisher;
import com.movielife.service.MovieReviewAiSummaryWorker;
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
public class MovieReviewAiSummaryJob {

    private final String consumerName = buildConsumerName();

    private final AiReviewSummaryProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final AiSummaryStreamPublisher streamPublisher;
    private final MovieReviewAiSummaryWorker worker;
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
            log.error("Failed to consume movie AI summary stream", exception);
        }
    }

    @Scheduled(
            fixedDelayString = "${movielife.ai.review-summary.retry-fixed-delay-ms:5000}",
            initialDelayString = "${movielife.ai.review-summary.retry-initial-delay-ms:5000}")
    public void notifyReadyRetries() {
        if (!properties.isEnabled()) {
            return;
        }
        List<Long> movieIds;
        try {
            movieIds = taskMapper.selectRetryReadyMovieIds(
                    LocalDateTime.now(), properties.getRetryBatchSize());
        } catch (RuntimeException exception) {
            log.error("Failed to scan retry-ready movie AI summary tasks", exception);
            return;
        }
        for (Long movieId : movieIds) {
            try {
                streamPublisher.publish(AiSummaryTargetType.MOVIE, movieId);
            } catch (RuntimeException exception) {
                log.error("Failed to notify retry-ready movie AI summary, movieId={}",
                        movieId, exception);
            }
        }
    }

    private void consume(MapRecord<String, Object, Object> record) {
        Long movieId = movieId(record.getValue());
        if (movieId == null) {
            acknowledge(record);
            return;
        }
        if (worker.process(movieId)) {
            acknowledge(record);
        }
    }

    private Long movieId(Map<Object, Object> value) {
        Object type = value.get("targetType");
        Object id = value.get("targetId");
        if (!AiSummaryTargetType.MOVIE.name().equals(String.valueOf(type))) {
            return null;
        }
        try {
            long parsed = Long.parseLong(String.valueOf(id));
            return parsed > 0 ? parsed : null;
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
        return "movie-ai-" + runtime + "-" + UUID.randomUUID();
    }
}
