package com.movielife.service;

import com.movielife.ai.ReviewSummaryInputItem;
import com.movielife.ai.ReviewSummarySnapshot;
import com.movielife.config.AiReviewSummaryProperties;
import com.movielife.entity.Review;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.mapper.ReviewMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReviewSummarySourceService {

    private static final int HARD_MAX_REVIEWS = 100;

    private final ReviewSummaryTargetPolicyRegistry policyRegistry;
    private final ReviewMapper reviewMapper;
    private final ReviewContentSanitizer sanitizer;
    private final AiReviewSummaryProperties properties;

    @Transactional(readOnly = true)
    public ReviewSummarySnapshot loadSnapshot(AiSummaryTargetType targetType, Long targetId) {
        validateArguments(targetType, targetId);
        validateLimits();
        policyRegistry.require(targetType).validateForSnapshot(targetId);

        String type = targetType.name();
        int sourceCount = reviewMapper.countActiveByTarget(type, targetId);
        List<Review> source = reviewMapper.selectActiveSummarySource(type, targetId, properties.getMaxReviews());
        List<ReviewSummaryInputItem> inputs = new ArrayList<>();
        int remaining = properties.getMaxInputCharacters();
        for (Review review : source) {
            if (remaining == 0) {
                break;
            }
            int perReviewLimit = Math.min(properties.getMaxReviewCharacters(), remaining);
            String content = sanitizer.sanitize(review.getContent(), perReviewLimit);
            remaining -= sanitizer.codePointLength(content);
            inputs.add(new ReviewSummaryInputItem(review.getId(), canonicalScore(review.getScore()), content));
        }
        return new ReviewSummarySnapshot(targetType, targetId, sourceCount, inputs);
    }

    private String canonicalScore(BigDecimal score) {
        if (score == null) {
            throw new IllegalStateException("review score must not be null");
        }
        return score.stripTrailingZeros().toPlainString();
    }

    private void validateArguments(AiSummaryTargetType targetType, Long targetId) {
        if (targetType == null || targetId == null || targetId <= 0) {
            throw new IllegalArgumentException("targetType and positive targetId are required");
        }
    }

    private void validateLimits() {
        if (properties.getMaxReviews() <= 0 || properties.getMaxReviews() > HARD_MAX_REVIEWS
                || properties.getMaxReviewCharacters() <= 0 || properties.getMaxInputCharacters() <= 0
                || properties.getMinReviews() <= 0 || properties.getMinReviews() > properties.getMaxReviews()) {
            throw new IllegalStateException("invalid AI review summary input limits");
        }
    }
}
