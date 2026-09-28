package com.movielife.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Fails application startup when the optional AI review-summary capability is
 * explicitly enabled without a complete provider configuration.
 */
@Component
public class AiReviewSummaryConfigurationValidator implements InitializingBean {

    private final AiReviewSummaryProperties properties;

    public AiReviewSummaryConfigurationValidator(AiReviewSummaryProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.isEnabled()) {
            return;
        }

        List<String> invalidVariables = new ArrayList<>();
        if (!isHttpEndpoint(properties.getEndpoint())) {
            invalidVariables.add("AI_REVIEW_SUMMARY_ENDPOINT");
        }
        if (isBlank(properties.getApiKey())) {
            invalidVariables.add("AI_REVIEW_SUMMARY_API_KEY");
        }
        if (isBlank(properties.getModel())) {
            invalidVariables.add("AI_REVIEW_SUMMARY_MODEL");
        }

        if (!invalidVariables.isEmpty()) {
            throw new IllegalStateException(
                    "AI review summary is enabled but required configuration is missing or invalid: "
                            + String.join(", ", invalidVariables));
        }
    }

    private boolean isHttpEndpoint(String value) {
        if (isBlank(value)) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            return uri.isAbsolute()
                    && uri.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
