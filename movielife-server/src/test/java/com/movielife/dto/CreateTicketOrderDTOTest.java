package com.movielife.dto;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class CreateTicketOrderDTOTest {

    private static final ValidatorFactory VALIDATOR_FACTORY =
            Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void shouldAcceptValidRequest() {
        assertThat(VALIDATOR.validate(request(1001L, "token", 101L, 102L))).isEmpty();
    }

    @Test
    void shouldRejectMissingOrNonPositiveShowtimeId() {
        assertThat(VALIDATOR.validate(request(null, "token", 101L))).isNotEmpty();
        assertThat(VALIDATOR.validate(request(0L, "token", 101L))).isNotEmpty();
        assertThat(VALIDATOR.validate(request(-1L, "token", 101L))).isNotEmpty();
    }

    @Test
    void shouldRejectMissingEmptyOrOversizedSeatIds() {
        CreateTicketOrderDTO missing = request(1001L, "token", 101L);
        missing.setSeatIds(null);
        assertThat(VALIDATOR.validate(missing)).isNotEmpty();

        CreateTicketOrderDTO empty = request(1001L, "token", 101L);
        empty.setSeatIds(Collections.emptyList());
        assertThat(VALIDATOR.validate(empty)).isNotEmpty();

        assertThat(VALIDATOR.validate(request(
                1001L, "token", 1L, 2L, 3L, 4L, 5L, 6L, 7L))).isNotEmpty();
    }

    @Test
    void shouldRejectNullOrNonPositiveSeatId() {
        assertThat(VALIDATOR.validate(request(1001L, "token", 101L, null))).isNotEmpty();
        assertThat(VALIDATOR.validate(request(1001L, "token", 0L))).isNotEmpty();
        assertThat(VALIDATOR.validate(request(1001L, "token", -1L))).isNotEmpty();
    }

    @Test
    void shouldRejectBlankOrOversizedLockToken() {
        assertThat(VALIDATOR.validate(request(1001L, null, 101L))).isNotEmpty();
        assertThat(VALIDATOR.validate(request(1001L, "   ", 101L))).isNotEmpty();
        assertThat(VALIDATOR.validate(request(1001L, repeat('a', 65), 101L))).isNotEmpty();
    }

    private CreateTicketOrderDTO request(Long showtimeId, String lockToken, Long... seatIds) {
        CreateTicketOrderDTO dto = new CreateTicketOrderDTO();
        dto.setShowtimeId(showtimeId);
        dto.setSeatIds(Arrays.asList(seatIds));
        dto.setLockToken(lockToken);
        return dto;
    }

    private String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
