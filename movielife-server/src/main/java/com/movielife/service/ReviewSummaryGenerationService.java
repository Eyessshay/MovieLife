package com.movielife.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.ai.AiClientResponse;
import com.movielife.ai.AiSummaryGeneratedContent;
import com.movielife.ai.AiSummaryPublication;
import com.movielife.ai.ReviewSummaryGenerationResult;
import com.movielife.ai.ReviewSummaryPrompt;
import com.movielife.ai.ReviewSummarySnapshot;
import com.movielife.config.AiReviewSummaryProperties;
import com.movielife.enums.AiSummaryTargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReviewSummaryGenerationService {

    private final ReviewSummarySourceService sourceService;
    private final ReviewSummaryPromptBuilder promptBuilder;
    private final ReviewSummaryFingerprintCalculator fingerprintCalculator;
    private final AiReviewSummaryClient client;
    private final AiSummaryResponseValidator responseValidator;
    private final AiReviewSummaryProperties properties;
    private final ObjectMapper objectMapper;

    public ReviewSummaryGenerationResult generate(AiSummaryTargetType targetType,
                                                   Long targetId,
                                                   Long sourceVersion) {
        if (sourceVersion == null || sourceVersion <= 0) {
            throw new IllegalArgumentException("positive sourceVersion is required");
        }
        ReviewSummarySnapshot snapshot = sourceService.loadSnapshot(targetType, targetId);
        if (snapshot.getSourceReviewCount() < properties.getMinReviews()) {
            return ReviewSummaryGenerationResult.insufficient(snapshot);
        }
        if (snapshot.getInputReviewCount() < properties.getMinReviews()) {
            throw new IllegalStateException("AI input limits exclude required reviews");
        }

        ReviewSummaryPrompt prompt = promptBuilder.build(snapshot);
        String fingerprint = fingerprintCalculator.calculate(snapshot, prompt.getPromptVersion());
        AiClientResponse response = client.generate(prompt);
        AiSummaryGeneratedContent generated = responseValidator.validate(response.getContent());

        AiSummaryPublication publication = new AiSummaryPublication();
        publication.setTargetType(targetType);
        publication.setTargetId(targetId);
        publication.setSourceVersion(sourceVersion);
        publication.setPromptVersion(prompt.getPromptVersion());
        publication.setSourceFingerprint(fingerprint);
        publication.setSourceReviewCount(snapshot.getSourceReviewCount());
        publication.setInputReviewCount(snapshot.getInputReviewCount());
        publication.setSummary(generated.getSummary());
        publication.setPros(json(generated.getPros()));
        publication.setCons(json(generated.getCons()));
        publication.setSuitableFor(json(generated.getSuitableFor()));
        publication.setWarningPoints(json(generated.getWarningPoints()));
        publication.setModelName(response.getModelName());
        return ReviewSummaryGenerationResult.generated(snapshot, publication);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize generated AI summary", e);
        }
    }
}
