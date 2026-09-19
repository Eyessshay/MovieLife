package com.movielife.ai;

import com.movielife.enums.AiSummaryTargetType;
import lombok.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Value
public class ReviewSummarySnapshot {
    AiSummaryTargetType targetType;
    Long targetId;
    int sourceReviewCount;
    List<ReviewSummaryInputItem> reviews;

    public ReviewSummarySnapshot(AiSummaryTargetType targetType,
                                 Long targetId,
                                 int sourceReviewCount,
                                 List<ReviewSummaryInputItem> reviews) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.sourceReviewCount = sourceReviewCount;
        this.reviews = Collections.unmodifiableList(new ArrayList<>(reviews));
    }

    public int getInputReviewCount() {
        return reviews.size();
    }
}
