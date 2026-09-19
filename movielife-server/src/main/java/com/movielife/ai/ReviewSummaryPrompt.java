package com.movielife.ai;

import lombok.Value;

@Value
public class ReviewSummaryPrompt {
    String promptVersion;
    String systemMessage;
    String userMessage;
}
