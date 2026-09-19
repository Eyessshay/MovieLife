package com.movielife.service;

import com.movielife.config.AiReviewSummaryProperties;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewAiSummaryTrigger {

    private final AiReviewSummaryProperties properties;
    private final AiSummaryPersistenceService persistenceService;
    private final AiReviewSummaryCacheRepository cacheRepository;
    private final AiSummaryStreamPublisher streamPublisher;
    private final ReviewSummaryTargetPolicyRegistry policyRegistry;

    public void afterReviewCommitted(AiSummaryTargetType targetType, Long targetId) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            policyRegistry.require(targetType).validateForSnapshot(targetId);
        } catch (BusinessException exception) {
            log.info("Skip AI summary trigger for unavailable target, targetType={}, targetId={}",
                    targetType, targetId);
            return;
        }
        try {
            persistenceService.markDirty(targetType, targetId, LocalDateTime.now());
        } catch (RuntimeException exception) {
            log.error("Failed to mark review AI summary dirty, targetType={}, targetId={}",
                    targetType, targetId, exception);
            return;
        }
        try {
            cacheRepository.delete(targetType, targetId);
        } catch (RuntimeException exception) {
            log.warn("Failed to evict stale review AI summary cache, targetType={}, targetId={}",
                    targetType, targetId, exception);
        }
        try {
            streamPublisher.publish(targetType, targetId);
        } catch (RuntimeException exception) {
            log.error("Failed to notify review AI summary worker, targetType={}, targetId={}",
                    targetType, targetId, exception);
        }
    }
}
