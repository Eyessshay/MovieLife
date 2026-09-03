package com.movielife.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.movielife.dto.SeatLockContext;
import com.movielife.dto.SeatLockMetadata;
import com.movielife.dto.SeatLockRequest;
import com.movielife.dto.UserDTO;
import com.movielife.entity.Seat;
import com.movielife.entity.ShowtimeSeat;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.service.ShowtimeService;
import com.movielife.utils.UserHolder;
import com.movielife.vo.ShowtimeDetailVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatLockServiceImplTest {

    private static final Long SHOWTIME_ID = 1001L;
    private static final Long HALL_ID = 10L;

    @Mock
    private ShowtimeService showtimeService;
    @Mock
    private SeatMapper seatMapper;
    @Mock
    private ShowtimeSeatMapper showtimeSeatMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private SeatLockServiceImpl seatLockService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        seatLockService = new SeatLockServiceImpl(
                showtimeService, seatMapper, showtimeSeatMapper, objectMapper, stringRedisTemplate);
        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void duplicateSeatIdsShouldFailBeforeDatabaseQueries() {
        SeatLockRequest request = request(101L, 101L);

        assertError(ErrorCode.INVALID_PARAM,
                () -> seatLockService.prepareLockContext(SHOWTIME_ID, request));

        verify(showtimeService, never()).queryShowtimeDetail(any());
        verify(seatMapper, never()).selectList(any());
        verify(showtimeSeatMapper, never()).selectList(any());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void nonPositiveSeatIdShouldFail(long invalidSeatId) {
        assertError(ErrorCode.INVALID_PARAM,
                () -> seatLockService.prepareLockContext(SHOWTIME_ID, request(invalidSeatId)));
    }

    @Test
    void moreThanSixSeatIdsShouldFail() {
        assertError(ErrorCode.INVALID_PARAM,
                () -> seatLockService.prepareLockContext(
                        SHOWTIME_ID, request(1L, 2L, 3L, 4L, 5L, 6L, 7L)));
    }

    @Test
    void unavailableShowtimeShouldStopBeforeSeatQueries() {
        when(showtimeService.queryShowtimeDetail(SHOWTIME_ID))
                .thenThrow(new BusinessException(ErrorCode.SHOWTIME_NOT_FOUND));

        assertError(ErrorCode.SHOWTIME_NOT_FOUND,
                () -> seatLockService.prepareLockContext(SHOWTIME_ID, request(101L)));

        verify(seatMapper, never()).selectList(any());
        verify(showtimeSeatMapper, never()).selectList(any());
    }

    @Test
    void seatOutsideTargetHallShouldFail() {
        stubValidShowtime();
        when(seatMapper.selectList(any())).thenReturn(Collections.singletonList(enabledSeat(101L)));

        assertError(ErrorCode.INVALID_PARAM,
                () -> seatLockService.prepareLockContext(SHOWTIME_ID, request(101L, 102L)));

        verify(seatMapper, times(1)).selectList(any());
        verify(showtimeSeatMapper, never()).selectList(any());
    }

    @Test
    void disabledSeatShouldFail() {
        stubValidShowtime();
        Seat disabled = enabledSeat(101L);
        disabled.setBaseStatus(0);
        when(seatMapper.selectList(any())).thenReturn(Collections.singletonList(disabled));

        assertError(ErrorCode.INVALID_PARAM,
                () -> seatLockService.prepareLockContext(SHOWTIME_ID, request(101L)));
    }

    @Test
    void missingShowtimeSeatShouldFailAsIncompleteDatabaseState() {
        stubValidShowtime();
        when(seatMapper.selectList(any()))
                .thenReturn(Arrays.asList(enabledSeat(101L), enabledSeat(102L)));
        when(showtimeSeatMapper.selectList(any()))
                .thenReturn(Collections.singletonList(showtimeSeat(101L, 0)));

        assertError(ErrorCode.INTERNAL_ERROR,
                () -> seatLockService.prepareLockContext(SHOWTIME_ID, request(101L, 102L)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void occupiedOrSoldShowtimeSeatShouldBeNotAvailable(int status) {
        stubValidShowtime();
        when(seatMapper.selectList(any())).thenReturn(Collections.singletonList(enabledSeat(101L)));
        when(showtimeSeatMapper.selectList(any()))
                .thenReturn(Collections.singletonList(showtimeSeat(101L, status)));

        assertError(ErrorCode.SEAT_NOT_AVAILABLE,
                () -> seatLockService.prepareLockContext(SHOWTIME_ID, request(101L)));
    }

    @Test
    void validRequestShouldBatchQueryAndBuildNormalizedContext() {
        stubValidShowtime();
        when(seatMapper.selectList(any()))
                .thenReturn(Arrays.asList(enabledSeat(101L), enabledSeat(102L), enabledSeat(103L)));
        when(showtimeSeatMapper.selectList(any()))
                .thenReturn(Arrays.asList(
                        showtimeSeat(101L, 0), showtimeSeat(102L, 0), showtimeSeat(103L, 0)));

        SeatLockContext context = seatLockService.prepareLockContext(
                SHOWTIME_ID, request(103L, 101L, 102L));

        assertThat(context.getUserId()).isEqualTo(1L);
        assertThat(context.getShowtimeId()).isEqualTo(SHOWTIME_ID);
        assertThat(context.getHallId()).isEqualTo(HALL_ID);
        assertThat(context.getSeatIds()).containsExactly(101L, 102L, 103L);
        assertThat(context.getLockToken()).isNull();
        assertThat(context.getExpireTime()).isNull();
        verify(seatMapper, times(1)).selectList(any());
        verify(showtimeSeatMapper, times(1)).selectList(any());
    }

    @Test
    void consecutiveContextsShouldReceiveDifferentTokens() {
        stubValidShowtime();
        when(seatMapper.selectList(any())).thenReturn(Collections.singletonList(enabledSeat(101L)));
        when(showtimeSeatMapper.selectList(any()))
                .thenReturn(Collections.singletonList(showtimeSeat(101L, 0)));

        SeatLockContext first = seatLockService.prepareLockContext(SHOWTIME_ID, request(101L));
        SeatLockContext second = seatLockService.prepareLockContext(SHOWTIME_ID, request(101L));
        seatLockService.completeNewLockContext(first);
        seatLockService.completeNewLockContext(second);

        assertThat(first.getLockToken()).isNotBlank().doesNotContain("-");
        assertThat(second.getLockToken()).isNotBlank().doesNotContain("-");
        assertThat(second.getLockToken()).isNotEqualTo(first.getLockToken());
    }

    @Test
    void metadataShouldCopyContextAndRoundTripThroughJson() {
        stubValidShowtime();
        when(seatMapper.selectList(any()))
                .thenReturn(Arrays.asList(enabledSeat(101L), enabledSeat(102L)));
        when(showtimeSeatMapper.selectList(any()))
                .thenReturn(Arrays.asList(showtimeSeat(101L, 0), showtimeSeat(102L, 0)));
        SeatLockContext context = seatLockService.prepareLockContext(
                SHOWTIME_ID, request(102L, 101L));
        seatLockService.completeNewLockContext(context);

        SeatLockMetadata metadata = seatLockService.buildMetadata(context);
        String json = seatLockService.serializeMetadata(metadata);
        SeatLockMetadata restored = seatLockService.deserializeMetadata(json);

        assertThat(metadata.getUserId()).isEqualTo(context.getUserId());
        assertThat(metadata.getShowtimeId()).isEqualTo(context.getShowtimeId());
        assertThat(metadata.getSeatIds()).containsExactly(101L, 102L);
        assertThat(metadata.getExpireTime()).isEqualTo(context.getExpireTime());
        assertThat(json).contains("\"expireTime\":\"");
        assertThat(restored.getUserId()).isEqualTo(metadata.getUserId());
        assertThat(restored.getShowtimeId()).isEqualTo(metadata.getShowtimeId());
        assertThat(restored.getSeatIds()).containsExactlyElementsOf(metadata.getSeatIds());
        assertThat(restored.getExpireTime()).isEqualTo(metadata.getExpireTime());
    }

    private void stubValidShowtime() {
        ShowtimeDetailVO showtime = new ShowtimeDetailVO();
        showtime.setId(SHOWTIME_ID);
        showtime.setHallId(HALL_ID);
        when(showtimeService.queryShowtimeDetail(SHOWTIME_ID)).thenReturn(showtime);
    }

    private SeatLockRequest request(Long... seatIds) {
        SeatLockRequest request = new SeatLockRequest();
        request.setSeatIds(Arrays.asList(seatIds));
        return request;
    }

    private Seat enabledSeat(Long id) {
        Seat seat = new Seat();
        seat.setId(id);
        seat.setHallId(HALL_ID);
        seat.setBaseStatus(1);
        return seat;
    }

    private ShowtimeSeat showtimeSeat(Long seatId, int status) {
        ShowtimeSeat showtimeSeat = new ShowtimeSeat();
        showtimeSeat.setShowtimeId(SHOWTIME_ID);
        showtimeSeat.setSeatId(seatId);
        showtimeSeat.setStatus(status);
        return showtimeSeat;
    }

    private void assertError(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(expected);
    }
}
