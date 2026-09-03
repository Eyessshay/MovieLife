package com.movielife.controller;

import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.service.ShowtimeService;
import com.movielife.vo.ShowtimeDetailVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShowtimeController.class)
class ShowtimeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ShowtimeService showtimeService;
    @MockBean
    private StringRedisTemplate stringRedisTemplate;

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
}
