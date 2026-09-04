package com.movielife.service;

import com.movielife.dto.SeatLockContext;
import com.movielife.dto.SeatLockRequest;
import com.movielife.dto.SeatReleaseRequest;
import com.movielife.vo.SeatLockVO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class SeatLockContractTest {

    @Test
    void shouldExposeLockMethod() throws NoSuchMethodException {
        Method method = SeatLockService.class.getDeclaredMethod(
                "lock", Long.class, SeatLockRequest.class);

        assertThat(method.getReturnType()).isEqualTo(SeatLockVO.class);
    }

    @Test
    void shouldExposeReleaseMethod() throws NoSuchMethodException {
        Method method = SeatLockService.class.getDeclaredMethod(
                "release", Long.class, SeatReleaseRequest.class);

        assertThat(method.getReturnType()).isEqualTo(Void.TYPE);
    }

    @Test
    void shouldExposeConditionalCleanupMethodForCommittedOrders() throws NoSuchMethodException {
        Method method = SeatLockService.class.getDeclaredMethod(
                "cleanupLock", Long.class, java.util.List.class, String.class);

        assertThat(method.getReturnType()).isEqualTo(Void.TYPE);
    }

    @Test
    void shouldCarryValidatedAndNormalizedSeatLockData() {
        LocalDateTime expireTime = LocalDateTime.of(2026, 9, 3, 18, 30);
        SeatLockContext context = new SeatLockContext();
        context.setUserId(1L);
        context.setShowtimeId(1001L);
        context.setHallId(10L);
        context.setSeatIds(Arrays.asList(101L, 102L));
        context.setLockToken("lock-token");
        context.setExpireTime(expireTime);

        assertThat(context.getUserId()).isEqualTo(1L);
        assertThat(context.getShowtimeId()).isEqualTo(1001L);
        assertThat(context.getHallId()).isEqualTo(10L);
        assertThat(context.getSeatIds()).containsExactly(101L, 102L);
        assertThat(context.getLockToken()).isEqualTo("lock-token");
        assertThat(context.getExpireTime()).isEqualTo(expireTime);
    }
}
