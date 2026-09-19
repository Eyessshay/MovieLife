package com.movielife.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.ai.AiClientResponse;
import com.movielife.ai.AiSummaryClientException;
import com.movielife.ai.AiSummaryFailureCode;
import com.movielife.ai.ReviewSummaryPrompt;
import com.movielife.config.AiReviewSummaryProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleReviewSummaryClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldCallRealHttpEndpointWithSeparatedMessages() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> request = new AtomicReference<>();
        start(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(StreamUtils.copyToString(exchange.getRequestBody(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"model\":\"stub-model\",\"choices\":[{\"message\":{\"content\":"
                    + "\"{\\\"summary\\\":\\\"好\\\",\\\"pros\\\":[],\\\"cons\\\":[],"
                    + "\\\"suitableFor\\\":[],\\\"warningPoints\\\":[]}\"}}]}");
        });

        AiClientResponse result = client(properties()).generate(prompt());

        assertThat(result.getModelName()).isEqualTo("stub-model");
        assertThat(authorization.get()).isEqualTo("Bearer secret");
        JsonNode messages = objectMapper.readTree(request.get()).path("messages");
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
    }

    @Test
    void shouldClassifyRateLimitAndHonorRetryAfter() throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "77");
            respond(exchange, 429, "{}");
        });

        assertThatThrownBy(() -> client(properties()).generate(prompt()))
                .isInstanceOf(AiSummaryClientException.class)
                .satisfies(error -> {
                    AiSummaryClientException exception = (AiSummaryClientException) error;
                    assertThat(exception.getFailureCode()).isEqualTo(AiSummaryFailureCode.AI_RATE_LIMITED);
                    assertThat(exception.getRetryAfterSeconds()).isEqualTo(77);
                });
    }

    @Test
    void shouldClassifyAuthenticationAndProviderFailures() throws Exception {
        start(exchange -> respond(exchange, 401, "{}"));
        assertFailure(client(properties()), AiSummaryFailureCode.AI_AUTH_FAILED);
        server.stop(0);

        start(exchange -> respond(exchange, 503, "{}"));
        assertFailure(client(properties()), AiSummaryFailureCode.AI_PROVIDER_UNAVAILABLE);
    }

    @Test
    void shouldRejectOversizedResponse() throws Exception {
        start(exchange -> respond(exchange, 200, "{\"model\":\"stub\",\"choices\":[],\"padding\":\"123456789\"}"));
        AiReviewSummaryProperties properties = properties();
        properties.setMaxResponseBytes(10);

        assertFailure(client(properties), AiSummaryFailureCode.AI_INVALID_RESPONSE);
    }

    @Test
    void shouldClassifyReadTimeout() throws Exception {
        start(exchange -> {
            try {
                Thread.sleep(250);
                respond(exchange, 200, "{}");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });

        assertFailure(client(properties(), 50), AiSummaryFailureCode.AI_TIMEOUT);
    }

    private OpenAiCompatibleReviewSummaryClient client(AiReviewSummaryProperties properties) {
        return client(properties, 1000);
    }

    private OpenAiCompatibleReviewSummaryClient client(AiReviewSummaryProperties properties, int readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(readTimeout);
        return new OpenAiCompatibleReviewSummaryClient(new RestTemplate(factory), objectMapper, properties);
    }

    private AiReviewSummaryProperties properties() {
        AiReviewSummaryProperties properties = new AiReviewSummaryProperties();
        properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
        properties.setApiKey("secret");
        properties.setModel("stub-model");
        return properties;
    }

    private ReviewSummaryPrompt prompt() {
        return new ReviewSummaryPrompt("v1", "system rules", "{\"reviews\":[]}");
    }

    private void assertFailure(OpenAiCompatibleReviewSummaryClient target, AiSummaryFailureCode code) {
        assertThatThrownBy(() -> target.generate(prompt()))
                .isInstanceOf(AiSummaryClientException.class)
                .extracting(error -> ((AiSummaryClientException) error).getFailureCode())
                .isEqualTo(code);
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", handler);
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
