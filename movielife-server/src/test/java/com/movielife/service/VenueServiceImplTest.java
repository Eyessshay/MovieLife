package com.movielife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.constants.RedisConstants;
import com.movielife.dto.VenueDetailCacheDTO;
import com.movielife.entity.Venue;
import com.movielife.enums.VenueType;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.VenueMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.service.impl.VenueServiceImpl;
import com.movielife.vo.VenueDetailVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VenueServiceImplTest {

    @Mock
    private VenueMapper venueMapper;
    @Mock
    private HallMapper hallMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private ObjectMapper objectMapper;
    private VenueServiceImpl venueService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        venueService = new VenueServiceImpl(venueMapper, hallMapper, redisTemplate, objectMapper);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void cacheHitShouldNotQueryVenueTable() throws Exception {
        when(valueOperations.get("venue:detail:1"))
                .thenReturn(objectMapper.writeValueAsString(cacheDTO()));

        VenueDetailVO result = venueService.queryVenueDetail(1L);

        assertThat(result.getName()).isEqualTo("MovieLife影城");
        assertThat(result.getType()).isEqualTo(VenueType.CINEMA);
        verify(venueMapper, never()).selectById(any());
    }

    @Test
    void cacheMissShouldQueryDatabaseAndWriteRandomizedCache() {
        when(valueOperations.get("venue:detail:1")).thenReturn(null);
        when(venueMapper.selectById(1L)).thenReturn(enabledVenue());

        VenueDetailVO result = venueService.queryVenueDetail(1L);

        assertThat(result.getName()).isEqualTo("MovieLife影城");
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq("venue:detail:1"), any(String.class), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isBetween(
                RedisConstants.VENUE_DETAIL_TTL,
                RedisConstants.VENUE_DETAIL_TTL.plusMinutes(
                        RedisConstants.VENUE_DETAIL_TTL_RANDOM_MINUTES
                )
        );
    }

    @Test
    void missingVenueShouldWriteShortEmptyCache() {
        when(valueOperations.get("venue:detail:999")) .thenReturn(null);
        when(venueMapper.selectById(999L)).thenReturn(null);

        assertVenueNotFound(999L);

        verify(valueOperations).set(
                "venue:detail:999",
                "",
                RedisConstants.VENUE_DETAIL_NULL_TTL
        );
    }

    @Test
    void disabledVenueShouldBeHiddenAndWriteShortEmptyCache() {
        Venue venue = enabledVenue();
        venue.setStatus(0);
        when(valueOperations.get("venue:detail:1")).thenReturn(null);
        when(venueMapper.selectById(1L)).thenReturn(venue);

        assertVenueNotFound(1L);

        verify(valueOperations).set(
                "venue:detail:1",
                "",
                RedisConstants.VENUE_DETAIL_NULL_TTL
        );
    }

    @Test
    void emptyCacheShouldBlockRepeatedDatabaseLookup() {
        when(valueOperations.get("venue:detail:999")).thenReturn("");

        assertVenueNotFound(999L);

        verify(venueMapper, never()).selectById(any());
    }

    @Test
    void corruptCacheShouldBeDeletedAndRebuiltFromDatabase() {
        when(valueOperations.get("venue:detail:1")).thenReturn("abc");
        when(venueMapper.selectById(1L)).thenReturn(enabledVenue());

        VenueDetailVO result = venueService.queryVenueDetail(1L);

        assertThat(result.getType()).isEqualTo(VenueType.CINEMA);
        verify(redisTemplate).delete("venue:detail:1");
        verify(valueOperations).set(eq("venue:detail:1"), any(String.class), any(Duration.class));
    }

    @Test
    void redisReadFailureShouldFallBackToDatabase() {
        when(valueOperations.get("venue:detail:1")).thenThrow(new RuntimeException("redis unavailable"));
        when(venueMapper.selectById(1L)).thenReturn(enabledVenue());

        VenueDetailVO result = venueService.queryVenueDetail(1L);

        assertThat(result.getName()).isEqualTo("MovieLife影城");
        verify(venueMapper).selectById(1L);
    }

    @Test
    void redisWriteFailureShouldNotFailRequest() {
        when(valueOperations.get("venue:detail:1")).thenReturn(null);
        when(venueMapper.selectById(1L)).thenReturn(enabledVenue());
        doThrow(new RuntimeException("redis unavailable"))
                .when(valueOperations).set(eq("venue:detail:1"), any(String.class), any(Duration.class));

        VenueDetailVO result = venueService.queryVenueDetail(1L);

        assertThat(result.getName()).isEqualTo("MovieLife影城");
    }

    @Test
    void nonPositiveIdShouldBeRejected() {
        assertThatThrownBy(() -> venueService.queryVenueDetail(0L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_ID);
    }

    private void assertVenueNotFound(Long id) {
        assertThatThrownBy(() -> venueService.queryVenueDetail(id))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.VENUE_NOT_FOUND);
    }

    private Venue enabledVenue() {
        Venue venue = new Venue();
        venue.setId(1L);
        venue.setName("MovieLife影城");
        venue.setType("cinema");
        venue.setCity("上海");
        venue.setAddress("徐汇区示例路1号");
        venue.setLongitude(new BigDecimal("121.437000"));
        venue.setLatitude(new BigDecimal("31.188000"));
        venue.setPhone("021-60000001");
        venue.setAvgScore(new BigDecimal("4.50"));
        venue.setStatus(1);
        return venue;
    }

    private VenueDetailCacheDTO cacheDTO() {
        VenueDetailCacheDTO dto = new VenueDetailCacheDTO();
        dto.setId(1L);
        dto.setName("MovieLife影城");
        dto.setType("cinema");
        dto.setCity("上海");
        dto.setAddress("徐汇区示例路1号");
        dto.setLongitude(new BigDecimal("121.437000"));
        dto.setLatitude(new BigDecimal("31.188000"));
        dto.setPhone("021-60000001");
        dto.setAvgScore(new BigDecimal("4.50"));
        return dto;
    }
}
