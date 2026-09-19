package com.movielife.service;

import com.movielife.ai.AiClientResponse;
import com.movielife.ai.ReviewSummaryPrompt;

public interface AiReviewSummaryClient {
    AiClientResponse generate(ReviewSummaryPrompt prompt);
}
