package com.movielife.dto;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;

import static org.assertj.core.api.Assertions.assertThat;

class SeatReleaseRequestTest {

    private static final ValidatorFactory VALIDATOR_FACTORY =
            Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void shouldRejectNullLockToken() {
        SeatReleaseRequest request = new SeatReleaseRequest();

        assertThat(VALIDATOR.validate(request)).isNotEmpty();
    }

    @Test
    void shouldRejectEmptyLockToken() {
        SeatReleaseRequest request = new SeatReleaseRequest();
        request.setLockToken("");

        assertThat(VALIDATOR.validate(request)).isNotEmpty();
    }

    @Test
    void shouldAcceptNonBlankLockToken() {
        SeatReleaseRequest request = new SeatReleaseRequest();
        request.setLockToken("lock-token");

        assertThat(VALIDATOR.validate(request)).isEmpty();
    }
}
