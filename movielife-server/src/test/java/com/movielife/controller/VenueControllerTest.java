package com.movielife.controller;

import com.movielife.service.VenueService;
import com.movielife.vo.VenueDetailVO;
import com.movielife.vo.PageVO;
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
}
