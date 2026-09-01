package com.movielife.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class ResultTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldCarryRequestIdFromMdc() {
        MDC.put(RequestIdFilter.REQUEST_ID, "request-123");

        Result<String> result = Result.ok("done");

        assertThat(result.getCode()).isEqualTo("OK");
        assertThat(result.getData()).isEqualTo("done");
        assertThat(result.getRequestId()).isEqualTo("request-123");
    }
}
