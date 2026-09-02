package com.movielife.controller;

import com.movielife.service.VenueService;
import com.movielife.vo.VenueDetailVO;
import com.movielife.vo.HallVO;
import com.movielife.vo.PageVO;
import com.movielife.vo.VenueNearbyVO;
import com.movielife.enums.VenueType;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VenueController.class)
class VenueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VenueService venueService;
    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void shouldAllowPublicVenueListQuery() throws Exception {
        when(venueService.queryVenues(any()))
                .thenReturn(new PageVO<>(Collections.emptyList(), 0L, 1, 20));

        mockMvc.perform(get("/api/venues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }

    @Test
    void shouldRejectInvalidPage() throws Exception {
        mockMvc.perform(get("/api/venues").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
    }

    @Test
    void shouldRejectOversizedPage() throws Exception {
        mockMvc.perform(get("/api/venues").param("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAGE_SIZE_TOO_LARGE"));
    }

    @Test
    void shouldRejectInvalidVenueType() throws Exception {
        mockMvc.perform(get("/api/venues").param("type", "ABC"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VENUE_TYPE"));
    }

    @Test
    void shouldAllowPublicNearbyVenueQuery() throws Exception {
        VenueNearbyVO nearby = new VenueNearbyVO();
        nearby.setId(1L);
        nearby.setName("MovieLife影城");
        nearby.setType(VenueType.CINEMA);
        nearby.setDistance(12.5D);
        when(venueService.queryNearbyVenues(any()))
                .thenReturn(Collections.singletonList(nearby));

        mockMvc.perform(get("/api/venues/nearby")
                        .param("city", "上海")
                        .param("type", "CINEMA")
                        .param("x", "121.437")
                        .param("y", "31.188"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].type").value("CINEMA"))
                .andExpect(jsonPath("$.data[0].distance").value(12.5));
    }

    @Test
    void nearbyQueryShouldRejectUnsupportedBusinessType() throws Exception {
        mockMvc.perform(get("/api/venues/nearby")
                        .param("city", "上海")
                        .param("type", "CINEMA")
                        .param("x", "121.437")
                        .param("y", "31.188")
                        .param("bizType", "ACTIVITY")
                        .param("bizId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BIZ_TYPE"));
    }

    @Test
    void nearbyQueryShouldRejectOutOfRangeLongitude() throws Exception {
        mockMvc.perform(validNearbyRequest("180.1", "31.188"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    void nearbyQueryShouldRejectOutOfRangeLatitude() throws Exception {
        mockMvc.perform(validNearbyRequest("121.437", "-90.1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    void nearbyQueryShouldRejectDistanceOutsideSupportedRange() throws Exception {
        mockMvc.perform(validNearbyRequest().param("distance", "99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));

        mockMvc.perform(validNearbyRequest().param("distance", "50001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    void nearbyQueryShouldRejectInvalidPagination() throws Exception {
        mockMvc.perform(validNearbyRequest().param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));

        mockMvc.perform(validNearbyRequest().param("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAGE_SIZE_TOO_LARGE"));
    }

    @Test
    void nearbyQueryShouldRequireBizTypeAndBizIdTogether() throws Exception {
        mockMvc.perform(validNearbyRequest().param("bizType", "MOVIE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));

        mockMvc.perform(validNearbyRequest().param("bizId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    void shouldAllowPublicVenueDetailQuery() throws Exception {
        VenueDetailVO detail = new VenueDetailVO();
        detail.setId(1L);
        detail.setName("MovieLife影城");
        detail.setType(VenueType.CINEMA);
        when(venueService.queryVenueDetail(1L)).thenReturn(detail);

        mockMvc.perform(get("/api/venues/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.type").value("CINEMA"));
    }

    @Test
    void shouldReturnInvalidIdForZero() throws Exception {
        when(venueService.queryVenueDetail(0L))
                .thenThrow(new BusinessException(ErrorCode.INVALID_ID));

        mockMvc.perform(get("/api/venues/0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ID"));
    }

    @Test
    void shouldReturnNotFoundForMissingVenue() throws Exception {
        when(venueService.queryVenueDetail(999999L))
                .thenThrow(new BusinessException(ErrorCode.VENUE_NOT_FOUND));

        mockMvc.perform(get("/api/venues/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VENUE_NOT_FOUND"));
    }

    @Test
    void shouldAllowPublicVenueHallQuery() throws Exception {
        HallVO first = hall(1L, 1L, "1号厅");
        HallVO second = hall(2L, 1L, "2号厅");
        when(venueService.queryVenueHalls(1L)).thenReturn(Arrays.asList(first, second));

        mockMvc.perform(get("/api/venues/1/halls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("1号厅"))
                .andExpect(jsonPath("$.data[1].name").value("2号厅"))
                .andExpect(jsonPath("$.data[0].seatTemplateId").doesNotExist());
    }

    @Test
    void hallQueryShouldReturnInvalidIdForZero() throws Exception {
        when(venueService.queryVenueHalls(0L))
                .thenThrow(new BusinessException(ErrorCode.INVALID_ID));

        mockMvc.perform(get("/api/venues/0/halls"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ID"));
    }

    @Test
    void hallQueryShouldReturnNotFoundForMissingVenue() throws Exception {
        when(venueService.queryVenueHalls(999999L))
                .thenThrow(new BusinessException(ErrorCode.VENUE_NOT_FOUND));

        mockMvc.perform(get("/api/venues/999999/halls"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VENUE_NOT_FOUND"));
    }

    private HallVO hall(Long id, Long venueId, String name) {
        HallVO hall = new HallVO();
        hall.setId(id);
        hall.setVenueId(venueId);
        hall.setName(name);
        return hall;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validNearbyRequest() {
        return validNearbyRequest("121.437", "31.188");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validNearbyRequest(
            String x,
            String y) {
        return get("/api/venues/nearby")
                .param("city", "上海")
                .param("type", "CINEMA")
                .param("x", x)
                .param("y", y);
    }
}
