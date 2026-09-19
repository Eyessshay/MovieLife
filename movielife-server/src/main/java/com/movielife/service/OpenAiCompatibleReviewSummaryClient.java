package com.movielife.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.movielife.ai.AiClientResponse;
import com.movielife.ai.AiSummaryClientException;
import com.movielife.ai.AiSummaryFailureCode;
import com.movielife.ai.ReviewSummaryPrompt;
import com.movielife.config.AiReviewSummaryProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

@Service
public class OpenAiCompatibleReviewSummaryClient implements AiReviewSummaryClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final AiReviewSummaryProperties properties;

    public OpenAiCompatibleReviewSummaryClient(
            @Qualifier("aiReviewSummaryRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            AiReviewSummaryProperties properties) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public AiClientResponse generate(ReviewSummaryPrompt prompt) {
        validateConfiguration();
        try {
            byte[] request = requestBody(prompt).getBytes(StandardCharsets.UTF_8);
            String response = restTemplate.execute(
                    URI.create(properties.getEndpoint().trim()),
                    HttpMethod.POST,
                    httpRequest -> {
                        HttpHeaders headers = httpRequest.getHeaders();
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
                        headers.setBearerAuth(properties.getApiKey().trim());
                        httpRequest.getBody().write(request);
                    },
                    httpResponse -> readLimited(httpResponse.getBody()));
            return parseResponse(response);
        } catch (AiSummaryClientException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            throw classifyStatus(e);
        } catch (ResourceAccessException e) {
            Throwable cause = e.getRootCause();
            String name = cause == null ? "" : cause.getClass().getSimpleName().toLowerCase();
            AiSummaryFailureCode code = name.contains("timeout")
                    ? AiSummaryFailureCode.AI_TIMEOUT : AiSummaryFailureCode.AI_CONNECTION_ERROR;
            throw new AiSummaryClientException(code, e);
        } catch (IllegalArgumentException e) {
            throw new AiSummaryClientException(AiSummaryFailureCode.AI_CONFIG_INVALID, e);
        } catch (Exception e) {
            throw new AiSummaryClientException(AiSummaryFailureCode.AI_INVALID_RESPONSE, e);
        }
    }

    private String requestBody(ReviewSummaryPrompt prompt) throws Exception {
        if (prompt == null) {
            throw new AiSummaryClientException(AiSummaryFailureCode.AI_CONFIG_INVALID);
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.getModel().trim());
        body.put("temperature", 0.2);
        body.putObject("response_format").put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", prompt.getSystemMessage());
        messages.addObject().put("role", "user").put("content", prompt.getUserMessage());
        return objectMapper.writeValueAsString(body);
    }

    private AiClientResponse parseResponse(String body) throws Exception {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > properties.getMaxResponseBytes()) {
            throw new AiSummaryClientException(AiSummaryFailureCode.AI_INVALID_RESPONSE);
        }
        JsonNode root = objectMapper.readTree(body);
        JsonNode choices = root.path("choices");
        JsonNode model = root.path("model");
        if (!choices.isArray() || choices.size() == 0 || !model.isTextual()) {
            throw new AiSummaryClientException(AiSummaryFailureCode.AI_INVALID_RESPONSE);
        }
        JsonNode content = choices.get(0).path("message").path("content");
        String modelName = model.asText().trim();
        if (!content.isTextual() || modelName.isEmpty() || modelName.length() > 64) {
            throw new AiSummaryClientException(AiSummaryFailureCode.AI_INVALID_RESPONSE);
        }
        return new AiClientResponse(content.asText(), modelName);
    }

    private String readLimited(InputStream input) throws IOException {
        int limit = properties.getMaxResponseBytes();
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 4096));
        byte[] buffer = new byte[1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new AiSummaryClientException(AiSummaryFailureCode.AI_INVALID_RESPONSE);
            }
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private AiSummaryClientException classifyStatus(HttpStatusCodeException exception) {
        int status = exception.getRawStatusCode();
        if (status == 401 || status == 403) {
            return new AiSummaryClientException(AiSummaryFailureCode.AI_AUTH_FAILED, exception);
        }
        if (status == 429) {
            return new AiSummaryClientException(
                    AiSummaryFailureCode.AI_RATE_LIMITED,
                    parseRetryAfter(exception.getResponseHeaders()),
                    exception);
        }
        if (status >= 500) {
            return new AiSummaryClientException(AiSummaryFailureCode.AI_PROVIDER_UNAVAILABLE, exception);
        }
        return new AiSummaryClientException(AiSummaryFailureCode.AI_REQUEST_REJECTED, exception);
    }

    private Integer parseRetryAfter(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        try {
            return value == null ? null : Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void validateConfiguration() {
        if (properties.getEndpoint() == null || properties.getEndpoint().trim().isEmpty()
                || properties.getApiKey() == null || properties.getApiKey().trim().isEmpty()
                || properties.getModel() == null || properties.getModel().trim().isEmpty()
                || properties.getMaxResponseBytes() <= 0) {
            throw new AiSummaryClientException(AiSummaryFailureCode.AI_CONFIG_INVALID);
        }
    }
}
