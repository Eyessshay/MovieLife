package com.movielife.controller;

import com.movielife.constants.RedisConstants;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.dto.CreateActivityShowtimeDTO;
import com.movielife.service.ActivityShowtimeAdminService;
import com.movielife.vo.AdminActivityShowtimeVO;
import com.movielife.vo.PageVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ActivityShowtimeAdminController.class)
class ActivityShowtimeAdminControllerTest {

    private static final String AUTHORIZATION = "Bearer activity-showtime-admin-token";

    @Autowired private MockMvc mockMvc;
    @MockBean private ActivityShowtimeAdminService activityShowtimeAdminService;
    @MockBean private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUpAuthentication() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisConstants.LOGIN_TOKEN_KEY + "activity-showtime-admin-token"))
                .thenReturn("7");
    }

    @Test
    void shouldListAuthorizedVenueActivityShowtimes() throws Exception {
        PageVO<AdminActivityShowtimeVO> page = new PageVO<>(
                Collections.singletonList(showtime()), 1L, 2, 10);
        when(activityShowtimeAdminService.queryShowtimes(eq(7L), eq(501L), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/admin/venues/501/activity-showtimes")
                        .header("Authorization", AUTHORIZATION)
                        .param("page", "2")
                        .param("pageSize", "10")
                        .param("showtimeStatus", "0")
                        .param("startDate", "2026-09-01")
                        .param("endDate", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.list[0].showtimeId").value(1001))
                .andExpect(jsonPath("$.data.list[0].activityTitle").value("固定座位话剧"))
                .andExpect(jsonPath("$.data.list[0].hallName").value("一号厅"))
                .andExpect(jsonPath("$.data.list[0].status").value(0))
                .andExpect(jsonPath("$.data.list[0].totalSeats").value(100))
                .andExpect(jsonPath("$.data.list[0].availableSeats").value(80));
    }

    @Test
    void shouldReturnActivityShowtimeDetail() throws Exception {
        when(activityShowtimeAdminService.queryShowtime(7L, 501L, 1001L))
                .thenReturn(showtime());

        mockMvc.perform(get("/api/admin/venues/501/activity-showtimes/1001")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.showtimeId").value(1001))
                .andExpect(jsonPath("$.data.activityId").value(301));
    }

    @Test
    void shouldCreateActivityShowtime() throws Exception {
        when(activityShowtimeAdminService.createShowtime(eq(7L), eq(501L), any()))
                .thenReturn(showtime());

        mockMvc.perform(post("/api/admin/venues/501/activity-showtimes")
                        .header("Authorization", AUTHORIZATION)
                        .contentType("application/json")
                        .content("{\"requestId\":\"req-1502-1\",\"activityId\":301,"
                                + "\"hallId\":601,\"startTime\":\"2035-09-20T19:30:00\","
                                + "\"endTime\":\"2035-09-20T21:30:00\",\"basePrice\":180.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.showtimeId").value(1001))
                .andExpect(jsonPath("$.data.totalSeats").value(100));
    }

    @Test
    void shouldRejectInvalidCreateBodyBeforeCallingService() throws Exception {
        mockMvc.perform(post("/api/admin/venues/501/activity-showtimes")
                        .header("Authorization", AUTHORIZATION)
                        .contentType("application/json")
                        .content("{\"requestId\":\"bad request\",\"activityId\":0,"
                                + "\"hallId\":601,\"startTime\":\"2035-09-20T19:30:00\","
                                + "\"endTime\":\"2035-09-20T21:30:00\",\"basePrice\":180.001}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));

        verify(activityShowtimeAdminService, never())
                .createShowtime(any(), any(), any(CreateActivityShowtimeDTO.class));
    }

    @Test
    void shouldStopAndResumeSale() throws Exception {
        AdminActivityShowtimeVO stopped = showtime();
        stopped.setStatus(0);
        when(activityShowtimeAdminService.stopSale(7L, 501L, 1001L)).thenReturn(stopped);
        when(activityShowtimeAdminService.resumeSale(7L, 501L, 1001L)).thenReturn(showtime());

        mockMvc.perform(post("/api/admin/venues/501/activity-showtimes/1001/stop-sale")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(0));
        mockMvc.perform(post("/api/admin/venues/501/activity-showtimes/1001/resume-sale")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.showtimeId").value(1001));
    }

    @Test
    void shouldMapSaleStatusConflict() throws Exception {
        doThrow(new BusinessException(ErrorCode.SHOWTIME_SALE_STATUS_CONFLICT))
                .when(activityShowtimeAdminService).stopSale(7L, 501L, 1001L);

        mockMvc.perform(post("/api/admin/venues/501/activity-showtimes/1001/stop-sale")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHOWTIME_SALE_STATUS_CONFLICT"));
    }

    @Test
    void shouldRequireAuthenticationBeforeCallingService() throws Exception {
        mockMvc.perform(get("/api/admin/venues/501/activity-showtimes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verify(activityShowtimeAdminService, never()).queryShowtimes(any(), any(), any());
    }

    @Test
    void shouldValidatePaginationStatusAndDateFormat() throws Exception {
        assertBadRequest("?page=0", "INVALID_PAGE");
        assertBadRequest("?pageSize=101", "PAGE_SIZE_TOO_LARGE");
        assertBadRequest("?showtimeStatus=3", "INVALID_SHOWTIME_STATUS");
        assertBadRequest("?startDate=2026/09/01", "INVALID_PARAM");

        verify(activityShowtimeAdminService, never()).queryShowtimes(any(), any(), any());
    }

    @Test
    void shouldMapManagementAndIsolationErrors() throws Exception {
        doThrow(new BusinessException(ErrorCode.VENUE_MANAGE_FORBIDDEN))
                .when(activityShowtimeAdminService).queryShowtime(7L, 501L, 1001L);
        mockMvc.perform(get("/api/admin/venues/501/activity-showtimes/1001")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("VENUE_MANAGE_FORBIDDEN"));

        org.mockito.Mockito.reset(activityShowtimeAdminService);
        doThrow(new BusinessException(ErrorCode.SHOWTIME_NOT_FOUND))
                .when(activityShowtimeAdminService).queryShowtime(7L, 501L, 1002L);
        mockMvc.perform(get("/api/admin/venues/501/activity-showtimes/1002")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOWTIME_NOT_FOUND"));
    }

    private void assertBadRequest(String query, String code) throws Exception {
        mockMvc.perform(get("/api/admin/venues/501/activity-showtimes" + query)
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(code));
    }

    private AdminActivityShowtimeVO showtime() {
        AdminActivityShowtimeVO vo = new AdminActivityShowtimeVO();
        vo.setShowtimeId(1001L);
        vo.setActivityId(301L);
        vo.setActivityTitle("固定座位话剧");
        vo.setHallId(601L);
        vo.setHallName("一号厅");
        vo.setStartTime(LocalDateTime.of(2026, 9, 20, 19, 30));
        vo.setEndTime(LocalDateTime.of(2026, 9, 20, 21, 30));
        vo.setBasePrice(new BigDecimal("180.00"));
        vo.setStatus(0);
        vo.setTotalSeats(100);
        vo.setAvailableSeats(80);
        return vo;
    }
}
