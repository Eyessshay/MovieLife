package com.movielife.controller;

import com.movielife.constants.RedisConstants;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.service.SeatLockService;
import com.movielife.service.ShowtimeService;
import com.movielife.vo.SeatLockVO;
import com.movielife.vo.SeatVO;
import com.movielife.vo.ShowtimeDetailVO;
import com.movielife.vo.ShowtimeSeatMapVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShowtimeController.class)
class ShowtimeControllerTest {

    private static final String AUTHORIZATION = "Bearer controller-test-token";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ShowtimeService showtimeService;
    @MockBean
    private SeatLockService seatLockService;
    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUpAuthentication() {
        valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisConstants.LOGIN_TOKEN_KEY + "controller-test-token")).thenReturn("1");
    }

    @Test
    void shouldReturnPublicShowtimeDetail() throws Exception {
        ShowtimeDetailVO detail = new ShowtimeDetailVO();
        detail.setId(101L);
        detail.setVenueId(1L);
        detail.setVenueName("MovieLife影城");
        detail.setHallId(2L);
        detail.setHallName("2号厅");
        detail.setMovieId(3L);
        detail.setMovieName("示例电影");
        detail.setStartTime(LocalDateTime.of(2026, 9, 4, 19, 30));
        detail.setEndTime(LocalDateTime.of(2026, 9, 4, 21, 30));
        detail.setBasePrice(new BigDecimal("45.00"));
        when(showtimeService.queryShowtimeDetail(101L)).thenReturn(detail);

        mockMvc.perform(get("/api/showtimes/101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(101))
                .andExpect(jsonPath("$.data.venueName").value("MovieLife影城"))
                .andExpect(jsonPath("$.data.hallName").value("2号厅"))
                .andExpect(jsonPath("$.data.movieName").value("示例电影"))
                .andExpect(jsonPath("$.data.basePrice").value(45.00));
    }

    @Test
    void shouldReturnShowtimeNotFound() throws Exception {
        when(showtimeService.queryShowtimeDetail(999999L))
                .thenThrow(new BusinessException(ErrorCode.SHOWTIME_NOT_FOUND));

        mockMvc.perform(get("/api/showtimes/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOWTIME_NOT_FOUND"));
    }

    @Test
    void shouldRejectInvalidShowtimeId() throws Exception {
        when(showtimeService.queryShowtimeDetail(0L))
                .thenThrow(new BusinessException(ErrorCode.INVALID_ID));

        mockMvc.perform(get("/api/showtimes/0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ID"));

        mockMvc.perform(get("/api/showtimes/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ID"));
    }

    @Test
    void shouldReturnPublicShowtimeSeatMap() throws Exception {
        SeatVO seat = new SeatVO();
        seat.setSeatId(10L);
        seat.setRowNo(1);
        seat.setColNo(2);
        seat.setSeatNo("1排2座");
        seat.setSeatType("VIP");
        seat.setStatus(2);
        seat.setPrice(new BigDecimal("60.00"));
        ShowtimeSeatMapVO seatMap = new ShowtimeSeatMapVO();
        seatMap.setShowtimeId(101L);
        seatMap.setHallId(2L);
        seatMap.setHallName("2号厅");
        seatMap.setBasePrice(new BigDecimal("45.00"));
        seatMap.setSeats(Collections.singletonList(seat));
        when(showtimeService.querySeatMap(101L)).thenReturn(seatMap);

        mockMvc.perform(get("/api/showtimes/101/seats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.showtimeId").value(101))
                .andExpect(jsonPath("$.data.hallName").value("2号厅"))
                .andExpect(jsonPath("$.data.basePrice").value(45.00))
                .andExpect(jsonPath("$.data.seats[0].seatId").value(10))
                .andExpect(jsonPath("$.data.seats[0].colNo").value(2))
                .andExpect(jsonPath("$.data.seats[0].status").value(2))
                .andExpect(jsonPath("$.data.seats[0].price").value(60.00));
    }

    @Test
    void shouldRejectUnauthenticatedSeatLock() throws Exception {
        mockMvc.perform(post("/api/showtimes/101/seats/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[101,102]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldValidateSeatLockRequest() throws Exception {
        mockMvc.perform(post("/api/showtimes/101/seats/lock")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":null}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/showtimes/101/seats/lock")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/showtimes/101/seats/lock")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[1,2,3,4,5,6,7]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnSeatLockResult() throws Exception {
        SeatLockVO result = new SeatLockVO();
        result.setLockToken("lock-token-1");
        result.setExpireTime(LocalDateTime.of(2026, 9, 3, 14, 30));
        when(seatLockService.lock(any(), any())).thenReturn(result);

        mockMvc.perform(post("/api/showtimes/101/seats/lock")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[101,102]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.lockToken").value("lock-token-1"))
                .andExpect(jsonPath("$.data.expireTime").value("2026-09-03T14:30:00"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void shouldMapSeatLockConflicts() throws Exception {
        doThrow(new BusinessException(ErrorCode.SEAT_ALREADY_LOCKED))
                .doThrow(new BusinessException(ErrorCode.SEAT_NOT_AVAILABLE))
                .when(seatLockService).lock(any(), any());

        mockMvc.perform(post("/api/showtimes/101/seats/lock")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[101]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_ALREADY_LOCKED"));

        mockMvc.perform(post("/api/showtimes/101/seats/lock")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[102]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_NOT_AVAILABLE"));
    }

    @Test
    void shouldMapShowtimeAndInternalLockErrors() throws Exception {
        doThrow(new BusinessException(ErrorCode.SHOWTIME_NOT_FOUND))
                .doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR))
                .when(seatLockService).lock(any(), any());

        mockMvc.perform(post("/api/showtimes/999999/seats/lock")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[101]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOWTIME_NOT_FOUND"));

        mockMvc.perform(post("/api/showtimes/101/seats/lock")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[101]}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void shouldRejectUnauthenticatedSeatRelease() throws Exception {
        mockMvc.perform(post("/api/showtimes/101/seats/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockToken\":\"lock-token-1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldValidateSeatReleaseRequest() throws Exception {
        mockMvc.perform(post("/api/showtimes/101/seats/release")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockToken\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReleaseSeatLock() throws Exception {
        mockMvc.perform(post("/api/showtimes/101/seats/release")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockToken\":\"lock-token-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(seatLockService).release(any(), any());
    }

    @Test
    void shouldMapSeatReleaseErrors() throws Exception {
        doThrow(new BusinessException(ErrorCode.LOCK_NOT_OWNED))
                .doThrow(new BusinessException(ErrorCode.INVALID_LOCK_TOKEN))
                .when(seatLockService).release(any(), any());

        mockMvc.perform(post("/api/showtimes/101/seats/release")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockToken\":\"not-owned\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LOCK_NOT_OWNED"));

        mockMvc.perform(post("/api/showtimes/101/seats/release")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockToken\":\"wrong-showtime\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LOCK_TOKEN"));
    }
}
