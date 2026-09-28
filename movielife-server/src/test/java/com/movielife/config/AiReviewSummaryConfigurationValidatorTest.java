package com.movielife.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiReviewSummaryConfigurationValidatorTest {

    @Test
    void disabledCapabilityShouldNotRequireProviderConfiguration() {
        AiReviewSummaryProperties properties = new AiReviewSummaryProperties();

        assertThatCode(() -> validator(properties).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void enabledCapabilityShouldRejectMissingRequiredConfigurationWithoutLeakingValues() {
        AiReviewSummaryProperties properties = new AiReviewSummaryProperties();
        properties.setEnabled(true);

        assertThatThrownBy(() -> validator(properties).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_REVIEW_SUMMARY_ENDPOINT")
                .hasMessageContaining("AI_REVIEW_SUMMARY_API_KEY")
                .hasMessageContaining("AI_REVIEW_SUMMARY_MODEL");
    }

    @Test
    void enabledCapabilityShouldRejectNonHttpEndpointWithoutPrintingSecret() {
        AiReviewSummaryProperties properties = completeProperties();
        properties.setEndpoint("file:///tmp/provider");
        properties.setApiKey("do-not-print-this-secret");

        assertThatThrownBy(() -> validator(properties).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_REVIEW_SUMMARY_ENDPOINT")
                .hasMessageNotContaining("do-not-print-this-secret");
    }

    @Test
    void enabledCapabilityShouldAcceptCompleteProviderConfiguration() {
        assertThatCode(() -> validator(completeProperties()).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    private AiReviewSummaryConfigurationValidator validator(AiReviewSummaryProperties properties) {
        return new AiReviewSummaryConfigurationValidator(properties);
    }

    private AiReviewSummaryProperties completeProperties() {
        AiReviewSummaryProperties properties = new AiReviewSummaryProperties();
        properties.setEnabled(true);
        properties.setEndpoint("https://provider.example/v1/chat/completions");
        properties.setApiKey("test-only-api-key");
        properties.setModel("test-model");
        return properties;
    }
}
