package com.movielife.dto;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class SeatLockRequestTest {

    private static final ValidatorFactory VALIDATOR_FACTORY =
            Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void shouldRejectNullSeatIds() {
        SeatLockRequest request = new SeatLockRequest();

        assertThat(VALIDATOR.validate(request)).isNotEmpty();
    }

    @Test
    void shouldRejectEmptySeatIds() {
        SeatLockRequest request = new SeatLockRequest();
        request.setSeatIds(Collections.emptyList());

        assertThat(VALIDATOR.validate(request)).isNotEmpty();
    }

    @Test
    void shouldRejectMoreThanSixSeatIds() {
        SeatLockRequest request = new SeatLockRequest();
        request.setSeatIds(Arrays.asList(1L, 2L, 3L, 4L, 5L, 6L, 7L));

        assertThat(VALIDATOR.validate(request)).isNotEmpty();
    }

    @Test
    void shouldAcceptOneToSixSeatIds() {
        SeatLockRequest request = new SeatLockRequest();
        request.setSeatIds(Collections.singletonList(1L));
        assertThat(VALIDATOR.validate(request)).isEmpty();

        request.setSeatIds(Arrays.asList(1L, 2L, 3L, 4L, 5L, 6L));
        assertThat(VALIDATOR.validate(request)).isEmpty();
    }
}
