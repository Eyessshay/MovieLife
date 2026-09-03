package com.movielife.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.movielife.dto.SeatLockMetadata;
import com.movielife.dto.SeatReleaseRequest;
import com.movielife.dto.UserDTO;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.service.ShowtimeService;
import com.movielife.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatReleaseServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long SHOWTIME_ID = 1001L;
    private static final String LOCK_TOKEN = "tokenA";
    private static final String TOKEN_KEY = "seat:lock:token:" + LOCK_TOKEN;

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
    void missingTokenMetadataShouldBeIdempotentSuccess() {
        stubMetadataJson(null);

        seatLockService.release(SHOWTIME_ID, request());

        verify(redisTemplate, never()).execute(any(), anyList(), any(), any());
    }

    @Test
    void anotherUserTokenShouldBeForbidden() throws Exception {
        stubMetadata(metadata(2L, SHOWTIME_ID, LocalDateTime.now().plusMinutes(4)));

        assertError(ErrorCode.LOCK_NOT_OWNED,
                () -> seatLockService.release(SHOWTIME_ID, request()));

        verify(redisTemplate, never()).execute(any(), anyList(), any(), any());
    }

    @Test
    void tokenForAnotherShowtimeShouldBeInvalid() throws Exception {
        stubMetadata(metadata(USER_ID, 2002L, LocalDateTime.now().plusMinutes(4)));

        assertError(ErrorCode.INVALID_LOCK_TOKEN,
                () -> seatLockService.release(SHOWTIME_ID, request()));

        verify(redisTemplate, never()).execute(any(), anyList(), any(), any());
    }

    @Test
    void expiredTokenShouldStillExecuteIdempotentCleanup() throws Exception {
        stubMetadata(metadata(USER_ID, SHOWTIME_ID, LocalDateTime.now().minusSeconds(1)));
        when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(0L);

        seatLockService.release(SHOWTIME_ID, request());

        verify(redisTemplate, times(1)).execute(any(), anyList(), any(), any());
    }

    @Test
    void validTokenAndLuaSuccessShouldCompleteRelease() throws Exception {
        stubMetadata(metadata(USER_ID, SHOWTIME_ID, LocalDateTime.now().plusMinutes(4)));
        when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(0L);

        seatLockService.release(SHOWTIME_ID, request());

        verify(valueOperations, times(1)).get(TOKEN_KEY);
        verify(redisTemplate, times(1)).execute(any(), anyList(), any(), any());
    }

    @Test
    void luaMetadataErrorShouldReturnInternalError() throws Exception {
        stubMetadata(metadata(USER_ID, SHOWTIME_ID, LocalDateTime.now().plusMinutes(4)));
        when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(1L);

        assertError(ErrorCode.INTERNAL_ERROR,
                () -> seatLockService.release(SHOWTIME_ID, request()));
    }

    @Test
    void nullLuaResultShouldReturnInternalError() throws Exception {
        stubMetadata(metadata(USER_ID, SHOWTIME_ID, LocalDateTime.now().plusMinutes(4)));
        when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(null);

        assertError(ErrorCode.INTERNAL_ERROR,
                () -> seatLockService.release(SHOWTIME_ID, request()));
    }

    @Test
    void unknownLuaResultShouldReturnInternalError() throws Exception {
        stubMetadata(metadata(USER_ID, SHOWTIME_ID, LocalDateTime.now().plusMinutes(4)));
        when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(99L);

        assertError(ErrorCode.INTERNAL_ERROR,
                () -> seatLockService.release(SHOWTIME_ID, request()));
    }

    @Test
    void blankTokenShouldBeInvalidBeforeRedisRead() {
        SeatReleaseRequest request = new SeatReleaseRequest();
        request.setLockToken("  ");

        assertError(ErrorCode.INVALID_LOCK_TOKEN,
                () -> seatLockService.release(SHOWTIME_ID, request));

        verify(redisTemplate, never()).opsForValue();
    }

    private void stubMetadata(SeatLockMetadata metadata) throws Exception {
        stubMetadataJson(objectMapper.writeValueAsString(metadata));
    }

    private void stubMetadataJson(String json) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(TOKEN_KEY)).thenReturn(json);
    }

    private SeatLockMetadata metadata(Long userId, Long showtimeId, LocalDateTime expireTime) {
        SeatLockMetadata metadata = new SeatLockMetadata();
        metadata.setUserId(userId);
        metadata.setShowtimeId(showtimeId);
        metadata.setSeatIds(Arrays.asList(101L, 102L));
        metadata.setExpireTime(expireTime);
        return metadata;
    }

    private SeatReleaseRequest request() {
        SeatReleaseRequest request = new SeatReleaseRequest();
        request.setLockToken(LOCK_TOKEN);
        return request;
    }

    private void assertError(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(expected);
    }
}
