package com.movielife.dto;

import com.movielife.constants.RedisConstants;
import com.movielife.exception.ErrorCode;
import com.movielife.vo.SeatLockVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SeatLockFoundationTest {

    @Test
    void shouldExposeOnlyLockTokenOnReleaseRequest() {
        assertThat(SeatReleaseRequest.class.getDeclaredFields())
                .extracting("name")
                .containsExactly("lockToken");
    }

    @Test
    void shouldUseLocalDateTimeForLockExpiry() {
        LocalDateTime expireTime = LocalDateTime.of(2026, 9, 3, 18, 30);
        SeatLockVO result = new SeatLockVO();
        result.setLockToken("lock-token");
        result.setExpireTime(expireTime);

        assertThat(result.getLockToken()).isEqualTo("lock-token");
        assertThat(result.getExpireTime()).isEqualTo(expireTime);
    }

    @Test
    void metadataShouldContainOnlyFrozenRedisFields() {
        assertThat(SeatLockMetadata.class.getDeclaredFields())
                .extracting("name")
                .containsExactly("userId", "showtimeId", "seatIds", "expireTime");
    }

    @Test
    void shouldProvideFrozenRedisKeysAndTtl() {
        assertThat(RedisConstants.SEAT_LOCK_KEY_PREFIX).isEqualTo("seat:lock:");
        assertThat(RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX).isEqualTo("seat:lock:token:");
        assertThat(RedisConstants.SEAT_LOCK_TTL_MINUTES).isEqualTo(5L);
    }

    @Test
    void shouldMapSeatLockErrorsToFrozenHttpStatuses() {
        assertThat(ErrorCode.SEAT_ALREADY_LOCKED.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.SEAT_NOT_AVAILABLE.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.LOCK_NOT_OWNED.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ErrorCode.INVALID_LOCK_TOKEN.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
