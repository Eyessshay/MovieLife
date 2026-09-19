package com.movielife.ai;

import lombok.Getter;

@Getter
public class ReviewSummaryGenerationResult {

    public enum Status {
        GENERATED,
        INSUFFICIENT_REVIEWS
    }

    private final Status status;
    private final ReviewSummarySnapshot snapshot;
    private final AiSummaryPublication publication;

    private ReviewSummaryGenerationResult(Status status,
                                          ReviewSummarySnapshot snapshot,
                                          AiSummaryPublication publication) {
        this.status = status;
        this.snapshot = snapshot;
        this.publication = publication;
    }

    public static ReviewSummaryGenerationResult insufficient(ReviewSummarySnapshot snapshot) {
        return new ReviewSummaryGenerationResult(Status.INSUFFICIENT_REVIEWS, snapshot, null);
    }

    public static ReviewSummaryGenerationResult generated(ReviewSummarySnapshot snapshot,
                                                           AiSummaryPublication publication) {
        return new ReviewSummaryGenerationResult(Status.GENERATED, snapshot, publication);
    }
}
