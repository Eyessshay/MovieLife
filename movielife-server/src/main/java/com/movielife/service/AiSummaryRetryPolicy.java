package com.movielife.service;

import com.movielife.ai.AiSummaryFailureCode;
import com.movielife.ai.AiSummaryRetryDecision;
import com.movielife.config.AiReviewSummaryProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class AiSummaryRetryPolicy {

    private final AiReviewSummaryProperties properties;

    public AiSummaryRetryDecision decide(AiSummaryFailureCode code,
                                         int attemptCount,
                                         Integer retryAfterSeconds,
                                         LocalDateTime now) {
        if (code == null || attemptCount <= 0 || now == null) {
            throw new IllegalArgumentException("code, positive attemptCount and now are required");
        }
        if (!code.isRetryable() || attemptCount >= properties.getMaxAttempts()) {
            return new AiSummaryRetryDecision(false, code.name(), null);
        }
        long delay = delaySeconds(attemptCount);
        if (code == AiSummaryFailureCode.AI_RATE_LIMITED && retryAfterSeconds != null && retryAfterSeconds > 0) {
            delay = Math.max(delay, Math.min(retryAfterSeconds, 600));
        }
        return new AiSummaryRetryDecision(true, code.name(), now.plusSeconds(delay));
    }

    private long delaySeconds(int attemptCount) {
        if (attemptCount == 1) {
            return 30;
        }
        if (attemptCount == 2) {
            return 120;
        }
        return 600;
    }
}
