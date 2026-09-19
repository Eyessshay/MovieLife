package com.movielife.service;

import com.movielife.ai.AiSummaryClientException;
import com.movielife.ai.AiSummaryRetryDecision;
import com.movielife.ai.ReviewSummaryGenerationResult;
import com.movielife.config.AiReviewSummaryProperties;
import com.movielife.entity.AiReviewSummary;
import com.movielife.entity.AiSummaryTask;
import com.movielife.enums.AiSummaryTargetType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MovieReviewAiSummaryWorker {

    private final AiReviewSummaryProperties properties;
    private final AiSummaryPersistenceService persistenceService;
    private final ReviewSummaryGenerationService generationService;
    private final AiSummaryRetryPolicy retryPolicy;
    private final AiReviewSummaryCacheRepository cacheRepository;

    public boolean process(Long movieId) {
        if (!properties.isEnabled() || movieId == null || movieId <= 0) {
            return true;
        }
        LocalDateTime now = LocalDateTime.now();
        String leaseOwner = "movie-ai-" + UUID.randomUUID();
        Optional<AiSummaryTask> claimed = persistenceService.claim(
                AiSummaryTargetType.MOVIE, movieId, leaseOwner, now,
                now.plus(properties.getLeaseDuration()));
        if (!claimed.isPresent()) {
            return true;
        }
        AiSummaryTask task = claimed.get();
        Long sourceVersion = task.getProcessingVersion();
        try {
            ReviewSummaryGenerationResult result = generationService.generate(
                    AiSummaryTargetType.MOVIE, movieId, sourceVersion);
            LocalDateTime completedAt = LocalDateTime.now();
            if (result.getStatus() == ReviewSummaryGenerationResult.Status.INSUFFICIENT_REVIEWS) {
                boolean completed = persistenceService.markInsufficient(
                        AiSummaryTargetType.MOVIE, movieId, sourceVersion, leaseOwner, completedAt);
                if (completed) {
                    cacheRepository.delete(AiSummaryTargetType.MOVIE, movieId);
                }
                return completed;
            }
            AiReviewSummary summary = persistenceService.publish(
                    result.getPublication(), leaseOwner, completedAt);
            cacheRepository.put(summary);
            return true;
        } catch (AiSummaryClientException exception) {
            return handleExpectedFailure(movieId, task, sourceVersion, leaseOwner, exception);
        } catch (RuntimeException exception) {
            log.error("Unexpected movie AI summary worker failure, movieId={}, sourceVersion={}",
                    movieId, sourceVersion, exception);
            return false;
        }
    }

    private boolean handleExpectedFailure(Long movieId,
                                          AiSummaryTask task,
                                          Long sourceVersion,
                                          String leaseOwner,
                                          AiSummaryClientException exception) {
        LocalDateTime failedAt = LocalDateTime.now();
        AiSummaryRetryDecision decision = retryPolicy.decide(
                exception.getFailureCode(), task.getAttemptCount(),
                exception.getRetryAfterSeconds(), failedAt);
        boolean transitioned;
        if (decision.isRetry()) {
            transitioned = persistenceService.markRetry(
                    AiSummaryTargetType.MOVIE, movieId, sourceVersion, leaseOwner,
                    decision.getErrorCode(), failedAt, decision.getNextRetryTime());
        } else {
            transitioned = persistenceService.markDead(
                    AiSummaryTargetType.MOVIE, movieId, sourceVersion, leaseOwner,
                    decision.getErrorCode(), failedAt);
        }
        if (!transitioned) {
            log.warn("Movie AI summary failure transition lost lease or became stale, movieId={}, sourceVersion={}",
                    movieId, sourceVersion);
        }
        return transitioned;
    }
}
