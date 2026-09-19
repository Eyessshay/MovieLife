package com.movielife.service;

import com.movielife.ai.AiSummaryClientException;
import com.movielife.ai.AiSummaryRetryDecision;
import com.movielife.ai.ReviewSummaryGenerationResult;
import com.movielife.config.AiReviewSummaryProperties;
import com.movielife.entity.AiReviewSummary;
import com.movielife.entity.AiSummaryTask;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewAiSummaryWorker {

    private final AiReviewSummaryProperties properties;
    private final AiSummaryPersistenceService persistenceService;
    private final ReviewSummaryGenerationService generationService;
    private final AiSummaryRetryPolicy retryPolicy;
    private final AiReviewSummaryCacheRepository cacheRepository;
    private final ReviewSummaryTargetPolicyRegistry policyRegistry;

    public boolean process(AiSummaryTargetType targetType, Long targetId) {
        if (!properties.isEnabled() || targetType == null || targetId == null || targetId <= 0) {
            return true;
        }
        LocalDateTime now = LocalDateTime.now();
        String leaseOwner = "review-ai-" + targetType.name().toLowerCase() + "-" + UUID.randomUUID();
        Optional<AiSummaryTask> claimed = persistenceService.claim(
                targetType, targetId, leaseOwner, now,
                now.plus(properties.getLeaseDuration()));
        if (!claimed.isPresent()) {
            return true;
        }
        AiSummaryTask task = claimed.get();
        Long sourceVersion = task.getProcessingVersion();
        try {
            ReviewSummaryGenerationResult result = generationService.generate(
                    targetType, targetId, sourceVersion);
            LocalDateTime completedAt = LocalDateTime.now();
            if (result.getStatus() == ReviewSummaryGenerationResult.Status.INSUFFICIENT_REVIEWS) {
                boolean completed = persistenceService.markInsufficient(
                        targetType, targetId, sourceVersion, leaseOwner, completedAt);
                if (completed) {
                    cacheRepository.delete(targetType, targetId);
                }
                return completed;
            }
            policyRegistry.require(targetType).validateForSnapshot(targetId);
            AiReviewSummary summary = persistenceService.publish(
                    result.getPublication(), leaseOwner, completedAt);
            cacheRepository.put(summary);
            return true;
        } catch (AiSummaryClientException exception) {
            return handleExpectedFailure(targetType, targetId, task, sourceVersion, leaseOwner, exception);
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == ErrorCode.MOVIE_NOT_FOUND
                    || exception.getErrorCode() == ErrorCode.ACTIVITY_NOT_FOUND) {
                return persistenceService.markDead(targetType, targetId, sourceVersion, leaseOwner,
                        exception.getErrorCode().getCode(), LocalDateTime.now());
            }
            log.error("Unexpected review AI summary business failure, targetType={}, targetId={}, sourceVersion={}",
                    targetType, targetId, sourceVersion, exception);
            return false;
        } catch (RuntimeException exception) {
            log.error("Unexpected review AI summary worker failure, targetType={}, targetId={}, sourceVersion={}",
                    targetType, targetId, sourceVersion, exception);
            return false;
        }
    }

    private boolean handleExpectedFailure(AiSummaryTargetType targetType,
                                          Long targetId,
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
                    targetType, targetId, sourceVersion, leaseOwner,
                    decision.getErrorCode(), failedAt, decision.getNextRetryTime());
        } else {
            transitioned = persistenceService.markDead(
                    targetType, targetId, sourceVersion, leaseOwner,
                    decision.getErrorCode(), failedAt);
        }
        if (!transitioned) {
            log.warn("Review AI summary failure transition lost lease or became stale, "
                            + "targetType={}, targetId={}, sourceVersion={}",
                    targetType, targetId, sourceVersion);
        }
        return transitioned;
    }
}
