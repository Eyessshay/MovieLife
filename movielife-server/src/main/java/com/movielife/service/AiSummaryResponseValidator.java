package com.movielife.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.ai.AiSummaryClientException;
import com.movielife.ai.AiSummaryFailureCode;
import com.movielife.ai.AiSummaryGeneratedContent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class AiSummaryResponseValidator {

    private static final Set<String> REQUIRED_FIELDS = new HashSet<>(Arrays.asList(
            "summary", "pros", "cons", "suitableFor", "warningPoints"));

    private final ObjectMapper strictMapper;

    public AiSummaryResponseValidator(ObjectMapper objectMapper) {
        this.strictMapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public AiSummaryGeneratedContent validate(String raw) {
        try {
            if (raw == null || raw.trim().isEmpty() || raw.trim().startsWith("```")) {
                throw invalid(null);
            }
            JsonNode root = strictMapper.readTree(raw);
            if (root == null || !root.isObject() || !hasExactFields(root)) {
                throw invalid(null);
            }
            String summary = text(root.get("summary"), 300);
            return new AiSummaryGeneratedContent(
                    summary,
                    list(root.get("pros")),
                    list(root.get("cons")),
                    list(root.get("suitableFor")),
                    list(root.get("warningPoints")));
        } catch (AiSummaryClientException e) {
            throw e;
        } catch (Exception e) {
            throw invalid(e);
        }
    }

    private boolean hasExactFields(JsonNode root) {
        Set<String> actual = new HashSet<>();
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            actual.add(names.next());
        }
        return actual.equals(REQUIRED_FIELDS);
    }

    private List<String> list(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 5) {
            throw invalid(null);
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String value = text(item, 100);
            if (!unique.add(value)) {
                throw invalid(null);
            }
        }
        return new ArrayList<>(unique);
    }

    private String text(JsonNode node, int maxCodePoints) {
        if (node == null || !node.isTextual()) {
            throw invalid(null);
        }
        String value = node.asText().trim().replaceAll("\\s+", " ");
        if (value.isEmpty() || value.codePointCount(0, value.length()) > maxCodePoints) {
            throw invalid(null);
        }
        return value;
    }

    private AiSummaryClientException invalid(Throwable cause) {
        return new AiSummaryClientException(AiSummaryFailureCode.AI_INVALID_RESPONSE, cause);
    }
}
