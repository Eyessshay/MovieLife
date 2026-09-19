package com.movielife.ai;

import lombok.Getter;

@Getter
public class AiSummaryClientException extends RuntimeException {

    private final AiSummaryFailureCode failureCode;
    private final Integer retryAfterSeconds;

    public AiSummaryClientException(AiSummaryFailureCode failureCode) {
        this(failureCode, null, null);
    }

    public AiSummaryClientException(AiSummaryFailureCode failureCode, Throwable cause) {
        this(failureCode, null, cause);
    }

    public AiSummaryClientException(AiSummaryFailureCode failureCode,
                                    Integer retryAfterSeconds,
                                    Throwable cause) {
        super(failureCode.name(), cause);
        this.failureCode = failureCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
