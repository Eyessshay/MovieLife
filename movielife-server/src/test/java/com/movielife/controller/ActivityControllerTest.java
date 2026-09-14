package com.movielife.controller;

import com.movielife.dto.ActivityShowtimeQueryDTO;
import com.movielife.dto.ActivityNearbyQueryDTO;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.service.ActivityService;
import com.movielife.vo.ActivityDetailVO;
import com.movielife.vo.ActivityListItemVO;
import com.movielife.vo.ActivityNearbyVO;
import com.movielife.vo.ActivityShowtimeVO;
import com.movielife.vo.PageVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ActivityController.class)
class ActivityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ActivityService activityService;
    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void shouldAllowPublicActivityList() throws Exception {
        ActivityListItemVO item = new ActivityListItemVO();
        item.setId(1L);
        item.setCollected(false);
        when(activityService.queryActivities(any()))
                .thenReturn(new PageVO<>(Collections.singletonList(item), 1L, 1, 20));

        mockMvc.perform(get("/api/activities").param("city", "上海"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.list[0].collected").value(false));
    }

    @Test
    void listShouldValidatePagination() throws Exception {
        mockMvc.perform(get("/api/activities").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));

        mockMvc.perform(get("/api/activities").param("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAGE_SIZE_TOO_LARGE"));
    }

    @Test
    void nearbyShouldReturnPublicActivityDiscoveryResult() throws Exception {
        ActivityNearbyVO nearby = new ActivityNearbyVO();
        nearby.setId(1L);
        nearby.setTitle("附近话剧");
        nearby.setNearestVenueId(2L);
        nearby.setNextShowtimeId(3L);
        nearby.setDistance(125.5D);
        when(activityService.queryNearbyActivities(any(ActivityNearbyQueryDTO.class)))
                .thenReturn(Collections.singletonList(nearby));

        mockMvc.perform(get("/api/activities/nearby")
                        .param("city", "上海")
                        .param("x", "121.47")
                        .param("y", "31.23")
                        .param("venueType", "THEATER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].nearestVenueId").value(2))
                .andExpect(jsonPath("$.data[0].nextShowtimeId").value(3));
    }

    @Test
    void nearbyShouldValidateParametersAndVenueType() throws Exception {
        mockMvc.perform(get("/api/activities/nearby")
                        .param("city", "上海")
                        .param("x", "181")
                        .param("y", "31.23"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));

        mockMvc.perform(get("/api/activities/nearby")
                        .param("city", "上海")
                        .param("x", "121.47")
                        .param("y", "31.23")
                        .param("venueType", "MUSEUM"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VENUE_TYPE"));
    }

    @Test
    void shouldReturnActivityDetail() throws Exception {
        ActivityDetailVO detail = new ActivityDetailVO();
        detail.setId(1L);
        detail.setTitle("示例现场活动");
        detail.setPriceMin(new BigDecimal("199.00"));
        detail.setCollected(false);
        when(activityService.queryActivityDetail(1L)).thenReturn(detail);

        mockMvc.perform(get("/api/activities/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.title").value("示例现场活动"))
                .andExpect(jsonPath("$.data.collected").value(false));
    }

    @Test
    void detailShouldMapBusinessErrors() throws Exception {
        when(activityService.queryActivityDetail(0L))
                .thenThrow(new BusinessException(ErrorCode.INVALID_ID));
        when(activityService.queryActivityDetail(999L))
                .thenThrow(new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND));

        mockMvc.perform(get("/api/activities/0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ID"));
        mockMvc.perform(get("/api/activities/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"));
    }

    @Test
    void showtimesShouldReturnCrossVenueContext() throws Exception {
        ActivityShowtimeVO showtime = new ActivityShowtimeVO();
        showtime.setShowtimeId(2L);
        showtime.setVenueId(2L);
        showtime.setVenueName("MovieLife剧场");
        showtime.setHallId(3L);
        showtime.setHallName("主剧场");
        showtime.setStartTime(LocalDateTime.of(2030, 1, 1, 19, 0));
        showtime.setEndTime(LocalDateTime.of(2030, 1, 1, 21, 0));
        showtime.setBasePrice(new BigDecimal("199.00"));
        showtime.setAvailableSeats(6);
        when(activityService.queryActivityShowtimes(eq(1L), any(ActivityShowtimeQueryDTO.class)))
                .thenReturn(Collections.singletonList(showtime));

        mockMvc.perform(get("/api/activities/1/showtimes")
                        .param("date", "2030-01-01")
                        .param("venueId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].showtimeId").value(2))
                .andExpect(jsonPath("$.data[0].venueName").value("MovieLife剧场"))
                .andExpect(jsonPath("$.data[0].availableSeats").value(6));
    }

    @Test
    void showtimesShouldValidateDateAndVenueId() throws Exception {
        mockMvc.perform(get("/api/activities/1/showtimes").param("date", "2030/01/01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));

        mockMvc.perform(get("/api/activities/1/showtimes").param("venueId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }
}
