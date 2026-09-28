package com.movielife.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ActiveProfiles("demo")
@TestPropertySource(properties = "movielife.startup-check.enabled=false")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoHealthHttpIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeOnlySanitizedApplicationMysqlAndRedisHealth() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health", Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("UP", body.get("status"));

        Map<String, Map<String, Object>> components =
                (Map<String, Map<String, Object>>) body.get("components");
        assertNotNull(components);
        Set<String> expectedNames = new HashSet<>(Arrays.asList("application", "mysql", "redis"));
        assertEquals(expectedNames, components.keySet());
        for (Map<String, Object> component : components.values()) {
            assertEquals("UP", component.get("status"));
            assertFalse(component.containsKey("details"));
        }

        String serialized = response.getBody().toString().toLowerCase();
        assertFalse(serialized.contains("jdbc:"));
        assertFalse(serialized.contains("username"));
        assertFalse(serialized.contains("token"));
        assertFalse(serialized.contains("exception"));
    }

    @Test
    void shouldNotExposeActuatorDiscoveryOrEnvironmentEndpoints() {
        assertEquals(HttpStatus.NOT_FOUND,
                restTemplate.getForEntity("/actuator", String.class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                restTemplate.getForEntity("/actuator/env", String.class).getStatusCode());
    }
}
