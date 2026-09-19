package com.movielife.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AiReviewSummaryHttpConfig {

    @Bean("aiReviewSummaryRestTemplate")
    public RestTemplate aiReviewSummaryRestTemplate(RestTemplateBuilder builder,
                                                     AiReviewSummaryProperties properties) {
        return builder
                .setConnectTimeout(properties.getConnectTimeout())
                .setReadTimeout(properties.getReadTimeout())
                .build();
    }
}
