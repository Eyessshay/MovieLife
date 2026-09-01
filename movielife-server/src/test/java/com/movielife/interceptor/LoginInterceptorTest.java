package com.movielife.interceptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;

class LoginInterceptorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LoginInterceptor interceptor = new LoginInterceptor(objectMapper);
    private final EndpointFixture endpoints = new EndpointFixture();

    @AfterEach
    void cleanup() {
        com.movielife.utils.UserHolder.removeUser();
    }

    @Test
    void shouldReturnUnauthorizedWhenTokenIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, protectedHandler());
        JsonNode body = objectMapper.readTree(response.getContentAsString());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(body.get("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void shouldReturnTokenInvalidWhenBearerTokenCannotBeResolved() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RefreshTokenInterceptor.TOKEN_INVALID_ATTRIBUTE, Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, protectedHandler());
        JsonNode body = objectMapper.readTree(response.getContentAsString());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(body.get("code").asText()).isEqualTo("TOKEN_INVALID");
    }

    @Test
    void shouldAllowPublicEndpointWithoutLogin() throws Exception {
        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                new HandlerMethod(endpoints, EndpointFixture.class.getMethod("publicEndpoint"))
        );

        assertThat(allowed).isTrue();
    }

    private HandlerMethod protectedHandler() throws NoSuchMethodException {
        return new HandlerMethod(
                endpoints,
                EndpointFixture.class.getMethod("protectedEndpoint")
        );
    }

    static class EndpointFixture {

        @LoginRequired
        public void protectedEndpoint() {
        }

        public void publicEndpoint() {
        }
    }
}
