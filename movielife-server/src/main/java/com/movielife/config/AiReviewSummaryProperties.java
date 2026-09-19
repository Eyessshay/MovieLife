package com.movielife.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "movielife.ai.review-summary")
public class AiReviewSummaryProperties {

    private boolean enabled = false;
    private String endpoint = "";
    private String apiKey = "";
    private String model = "";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(45);
    private int maxResponseBytes = 16 * 1024;
    private int minReviews = 3;
    private int maxReviews = 100;
    private int maxReviewCharacters = 1000;
    private int maxInputCharacters = 20000;
    private int maxAttempts = 3;
    private Duration leaseDuration = Duration.ofMinutes(2);
    private int streamBatchSize = 10;
    private int retryBatchSize = 100;
}
