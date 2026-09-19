package com.movielife.ai;

import lombok.Value;

import java.time.LocalDateTime;

@Value
public class AiSummaryRetryDecision {
    boolean retry;
    String errorCode;
    LocalDateTime nextRetryTime;
}
