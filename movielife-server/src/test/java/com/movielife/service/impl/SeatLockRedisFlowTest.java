package com.movielife.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import com.movielife.vo.SeatLockVO;
import com.movielife.vo.ShowtimeDetailVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatLockRedisFlowTest {

    private static final Long USER_ID = 1L;
    private static final Long SHOWTIME_ID = 1001L;
    private static final Long HALL_ID = 10L;

    @Mock
    private ShowtimeService showtimeService;
    @Mock
    private SeatMapper seatMapper;
    @Mock
    private ShowtimeSeatMapper showtimeSeatMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private ObjectMapper objectMapper;
    private SeatLockServiceImpl seatLockService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        seatLockService = new SeatLockServiceImpl(
                showtimeService, seatMapper, showtimeSeatMapper, objectMapper, redisTemplate);
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void noExistingLockAndLuaSuccessShouldReturnNewToken() {
        stubValidDatabase(101L, 102L);
        stubSeatLocks(null, null);
        when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any())).thenReturn(0L);

        SeatLockVO result = seatLockService.lock(SHOWTIME_ID, request(102L, 101L));

        assertThat(result.getLockToken()).isNotBlank().doesNotContain("-");
        assertThat(result.getExpireTime()).isAfter(LocalDateTime.now());
        verify(valueOperations, times(1)).multiGet(anyList());
        verify(valueOperations, never()).get(anyString());
        verify(redisTemplate, times(1)).execute(any(), anyList(), any(), any(), any(), any());
    }

    @Test
    void luaSeatConflictAfterEmptyPrecheckShouldReturnSeatAlreadyLocked() {
        stubValidDatabase(101L, 102L);
        stubSeatLocks(null, null);
        when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any())).thenReturn(1L);

        assertError(ErrorCode.SEAT_ALREADY_LOCKED,
                () -> seatLockService.lock(SHOWTIME_ID, request(101L, 102L)));
    }

    @Test
    void luaTokenConflictShouldReturnInternalError() {
        stubValidDatabase(101L);
        stubSeatLocks((String) null);
        when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any())).thenReturn(2L);

        assertError(ErrorCode.INTERNAL_ERROR,
                () -> seatLockService.lock(SHOWTIME_ID, request(101L)));
    }

    @Test
    void nullLuaResultShouldReturnInternalError() {
        stubValidDatabase(101L);
        stubSeatLocks((String) null);
        when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any())).thenReturn(null);

        assertError(ErrorCode.INTERNAL_ERROR,
                () -> seatLockService.lock(SHOWTIME_ID, request(101L)));
    }

    @Test
    void unknownLuaResultShouldReturnInternalError() {
        stubValidDatabase(101L);
        stubSeatLocks((String) null);
        when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any())).thenReturn(99L);

        assertError(ErrorCode.INTERNAL_ERROR,
                () -> seatLockService.lock(SHOWTIME_ID, request(101L)));
    }

    @Test
    void sameValidLockShouldBeIdempotentForReorderedRequestWithoutRedisWrites() throws Exception {
        stubValidDatabase(101L, 102L);
        stubSeatLocks("tokenA", "tokenA");
        SeatLockMetadata metadata = metadata(USER_ID, SHOWTIME_ID,
                Arrays.asList(101L, 102L), LocalDateTime.now().plusMinutes(4));
        when(valueOperations.get("seat:lock:token:tokenA"))
                .thenReturn(objectMapper.writeValueAsString(metadata));

        SeatLockVO result = seatLockService.lock(SHOWTIME_ID, request(102L, 101L));

        assertThat(result.getLockToken()).isEqualTo("tokenA");
        assertThat(result.getExpireTime()).isEqualTo(metadata.getExpireTime());
        verify(valueOperations, times(1)).multiGet(anyList());
        verify(valueOperations, times(1)).get("seat:lock:token:tokenA");
        verify(redisTemplate, never()).execute(any(), anyList(), any(), any(), any(), any());
    }

    @Test
    void partiallyExistingSeatKeysShouldConflict() {
        stubValidDatabase(101L, 102L, 103L);
        stubSeatLocks("tokenA", "tokenA", null);

        assertError(ErrorCode.SEAT_ALREADY_LOCKED,
                () -> seatLockService.lock(SHOWTIME_ID, request(101L, 102L, 103L)));

        verify(valueOperations, never()).get(anyString());
        verify(redisTemplate, never()).execute(any(), anyList(), any(), any(), any(), any());
    }

    @Test
    void differentTokensAcrossSeatKeysShouldConflict() {
        stubValidDatabase(101L, 102L, 103L);
        stubSeatLocks("tokenA", "tokenB", "tokenA");

        assertError(ErrorCode.SEAT_ALREADY_LOCKED,
                () -> seatLockService.lock(SHOWTIME_ID, request(101L, 102L, 103L)));
    }

    @Test
    void missingMetadataShouldConflict() {
        stubValidDatabase(101L, 102L);
        stubSeatLocks("tokenA", "tokenA");
        when(valueOperations.get("seat:lock:token:tokenA")).thenReturn(null);

        assertError(ErrorCode.SEAT_ALREADY_LOCKED,
                () -> seatLockService.lock(SHOWTIME_ID, request(101L, 102L)));
    }

    @Test
    void metadataForAnotherUserShouldConflict() throws Exception {
        assertMetadataRejected(metadata(2L, SHOWTIME_ID,
                Arrays.asList(101L, 102L), LocalDateTime.now().plusMinutes(4)));
    }

    @Test
    void metadataForAnotherShowtimeShouldConflict() throws Exception {
        assertMetadataRejected(metadata(USER_ID, 2002L,
                Arrays.asList(101L, 102L), LocalDateTime.now().plusMinutes(4)));
    }

    @Test
    void metadataForDifferentSeatSetShouldConflict() throws Exception {
        assertMetadataRejected(metadata(USER_ID, SHOWTIME_ID,
                Arrays.asList(101L, 103L), LocalDateTime.now().plusMinutes(4)));
    }

    @Test
    void expiredMetadataShouldConflict() throws Exception {
        assertMetadataRejected(metadata(USER_ID, SHOWTIME_ID,
                Arrays.asList(101L, 102L), LocalDateTime.now().minusSeconds(1)));
    }

    @Test
    void malformedMetadataShouldConflict() {
        stubValidDatabase(101L, 102L);
        stubSeatLocks("tokenA", "tokenA");
        when(valueOperations.get("seat:lock:token:tokenA")).thenReturn("not-json");

        assertError(ErrorCode.SEAT_ALREADY_LOCKED,
                () -> seatLockService.lock(SHOWTIME_ID, request(101L, 102L)));
    }

    private void assertMetadataRejected(SeatLockMetadata metadata) throws Exception {
        stubValidDatabase(101L, 102L);
        stubSeatLocks("tokenA", "tokenA");
        when(valueOperations.get("seat:lock:token:tokenA"))
                .thenReturn(objectMapper.writeValueAsString(metadata));

        assertError(ErrorCode.SEAT_ALREADY_LOCKED,
                () -> seatLockService.lock(SHOWTIME_ID, request(101L, 102L)));
    }

    private void stubValidDatabase(Long... seatIds) {
        ShowtimeDetailVO showtime = new ShowtimeDetailVO();
        showtime.setId(SHOWTIME_ID);
        showtime.setHallId(HALL_ID);
        when(showtimeService.queryShowtimeDetail(SHOWTIME_ID)).thenReturn(showtime);

        List<Seat> seats = new ArrayList<>();
        List<ShowtimeSeat> states = new ArrayList<>();
        for (Long seatId : seatIds) {
            Seat seat = new Seat();
            seat.setId(seatId);
            seat.setHallId(HALL_ID);
            seat.setBaseStatus(1);
            seats.add(seat);

            ShowtimeSeat state = new ShowtimeSeat();
            state.setShowtimeId(SHOWTIME_ID);
            state.setSeatId(seatId);
            state.setStatus(0);
            states.add(state);
        }
        when(seatMapper.selectList(any())).thenReturn(seats);
        when(showtimeSeatMapper.selectList(any())).thenReturn(states);
    }

    private void stubSeatLocks(String... tokens) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(tokens));
    }

    private SeatLockMetadata metadata(Long userId, Long showtimeId,
                                      List<Long> seatIds, LocalDateTime expireTime) {
        SeatLockMetadata metadata = new SeatLockMetadata();
        metadata.setUserId(userId);
        metadata.setShowtimeId(showtimeId);
        metadata.setSeatIds(seatIds);
        metadata.setExpireTime(expireTime);
        return metadata;
    }

    private SeatLockRequest request(Long... seatIds) {
        SeatLockRequest request = new SeatLockRequest();
        request.setSeatIds(Arrays.asList(seatIds));
        return request;
    }

    private void assertError(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(expected);
    }
}
