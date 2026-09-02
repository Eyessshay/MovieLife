package com.movielife.controller;

import com.movielife.service.VenueService;
import com.movielife.vo.PageVO;
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
}
