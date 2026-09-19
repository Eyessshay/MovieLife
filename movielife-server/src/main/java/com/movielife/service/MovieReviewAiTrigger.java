package com.movielife.service;

import com.movielife.config.AiReviewSummaryProperties;
import com.movielife.enums.AiSummaryTargetType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class MovieReviewAiTrigger {

    private final AiReviewSummaryProperties properties;
    private final AiSummaryPersistenceService persistenceService;
    private final AiReviewSummaryCacheRepository cacheRepository;
    private final AiSummaryStreamPublisher streamPublisher;

    public void afterMovieReviewCommitted(Long movieId) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            persistenceService.markDirty(AiSummaryTargetType.MOVIE, movieId, LocalDateTime.now());
        } catch (RuntimeException exception) {
            log.error("Failed to mark movie AI summary dirty, movieId={}", movieId, exception);
            return;
        }
        try {
            cacheRepository.delete(AiSummaryTargetType.MOVIE, movieId);
        } catch (RuntimeException exception) {
            log.warn("Failed to evict stale movie AI summary cache, movieId={}", movieId, exception);
        }
        try {
            streamPublisher.publish(AiSummaryTargetType.MOVIE, movieId);
        } catch (RuntimeException exception) {
            log.error("Failed to notify movie AI summary worker, movieId={}", movieId, exception);
        }
    }
}
