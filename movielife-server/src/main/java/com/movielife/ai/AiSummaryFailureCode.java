package com.movielife.ai;

public enum AiSummaryFailureCode {
    AI_TIMEOUT(true),
    AI_CONNECTION_ERROR(true),
    AI_RATE_LIMITED(true),
    AI_PROVIDER_UNAVAILABLE(true),
    AI_INVALID_RESPONSE(true),
    AI_AUTH_FAILED(false),
    AI_REQUEST_REJECTED(false),
    AI_CONFIG_INVALID(false);

    private final boolean retryable;

    AiSummaryFailureCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
