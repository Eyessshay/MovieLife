package com.movielife.ai;

import lombok.Value;

@Value
public class ReviewSummaryInputItem {
    Long reviewId;
    String score;
    String content;
}
