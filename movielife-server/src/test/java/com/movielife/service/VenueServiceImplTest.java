package com.movielife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.constants.RedisConstants;
import com.movielife.dto.VenueDetailCacheDTO;
import com.movielife.dto.VenueNearbyQueryDTO;
import com.movielife.entity.Movie;
import com.movielife.entity.Venue;
import com.movielife.enums.VenueType;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.VenueMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.service.impl.VenueServiceImpl;
import com.movielife.vo.VenueDetailVO;
import com.movielife.vo.VenueNearbyVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
    private MovieMapper movieMapper;
    @Mock
    private ShowtimeMapper showtimeMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private GeoOperations<String, String> geoOperations;

    private ObjectMapper objectMapper;
    private VenueServiceImpl venueService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        venueService = new VenueServiceImpl(
                venueMapper,
                hallMapper,
                movieMapper,
                showtimeMapper,
                redisTemplate,
                objectMapper
        );
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
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

    @Test
    void nearbyQueryShouldBatchLoadVenuesAndRestoreGeoOrder() {
        Venue first = enabledVenue();
        Venue second = enabledVenue();
        second.setId(2L);
        second.setName("第二家影院");
        when(geoOperations.radius(eq("venue:geo:上海:CINEMA"), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenReturn(geoResults(geoResult("1", 100D), geoResult("2", 300D)));
        when(venueMapper.selectList(any())).thenReturn(Arrays.asList(second, first));

        List<VenueNearbyVO> result = venueService.queryNearbyVenues(nearbyQuery(1, 10));

        assertThat(result).extracting(VenueNearbyVO::getId).containsExactly(1L, 2L);
        assertThat(result).extracting(VenueNearbyVO::getDistance).containsExactly(100D, 300D);
        verify(venueMapper).selectList(any());
    }

    @Test
    void nearbyQueryShouldApplyPageWindowBeforeBatchLoading() {
        Venue second = enabledVenue();
        second.setId(2L);
        when(geoOperations.radius(eq("venue:geo:上海:CINEMA"), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenReturn(geoResults(geoResult("1", 100D), geoResult("2", 300D)));
        when(venueMapper.selectList(any())).thenReturn(Collections.singletonList(second));

        List<VenueNearbyVO> result = venueService.queryNearbyVenues(nearbyQuery(2, 1));

        assertThat(result).extracting(VenueNearbyVO::getId).containsExactly(2L);
    }

    @Test
    void nearbyQueryBeyondAvailableResultsShouldReturnEmptyWithoutDatabaseQuery() {
        when(geoOperations.radius(eq("venue:geo:上海:CINEMA"), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenReturn(geoResults(geoResult("1", 100D), geoResult("2", 300D)));

        List<VenueNearbyVO> result = venueService.queryNearbyVenues(nearbyQuery(3, 1));

        assertThat(result).isEmpty();
        verify(venueMapper, never()).selectList(any());
    }

    @Test
    void redisGeoFailureShouldReturnUnifiedServiceError() {
        when(geoOperations.radius(eq("venue:geo:上海:CINEMA"), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenThrow(new RuntimeException("redis unavailable"));

        assertThatThrownBy(() -> venueService.queryNearbyVenues(nearbyQuery(1, 10)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void movieFilterShouldKeepOnlyFutureShowtimeVenuesInGeoOrder() {
        Venue first = enabledVenue();
        Venue second = enabledVenue();
        second.setId(2L);
        Venue third = enabledVenue();
        third.setId(3L);
        VenueNearbyQueryDTO dto = nearbyQuery(1, 10);
        dto.setBizType("MOVIE");
        dto.setBizId(9L);
        when(movieMapper.selectById(9L)).thenReturn(new Movie());
        when(geoOperations.radius(eq("venue:geo:上海:CINEMA"), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenReturn(geoResults(
                        geoResult("1", 100D),
                        geoResult("2", 200D),
                        geoResult("3", 300D)
                ));
        when(venueMapper.selectList(any())).thenReturn(Arrays.asList(third, second, first));
        when(showtimeMapper.selectVenueIdsWithFutureMovieShowtimes(eq(9L), any()))
                .thenReturn(Arrays.asList(3L, 1L));

        List<VenueNearbyVO> result = venueService.queryNearbyVenues(dto);

        assertThat(result).extracting(VenueNearbyVO::getId).containsExactly(1L, 3L);
    }

    @Test
    void movieFilterShouldFilterBeforePagination() {
        Venue first = enabledVenue();
        Venue second = enabledVenue();
        second.setId(2L);
        VenueNearbyQueryDTO dto = nearbyQuery(1, 1);
        dto.setBizType("MOVIE");
        dto.setBizId(9L);
        when(movieMapper.selectById(9L)).thenReturn(new Movie());
        when(geoOperations.radius(eq("venue:geo:上海:CINEMA"), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenReturn(geoResults(geoResult("1", 100D), geoResult("2", 200D)));
        when(venueMapper.selectList(any())).thenReturn(Arrays.asList(first, second));
        when(showtimeMapper.selectVenueIdsWithFutureMovieShowtimes(eq(9L), any()))
                .thenReturn(Collections.singletonList(2L));

        List<VenueNearbyVO> result = venueService.queryNearbyVenues(dto);

        assertThat(result).extracting(VenueNearbyVO::getId).containsExactly(2L);
    }

    @Test
    void missingMovieShouldFailBeforeGeoQuery() {
        VenueNearbyQueryDTO dto = nearbyQuery(1, 10);
        dto.setBizType("MOVIE");
        dto.setBizId(999L);
        when(movieMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> venueService.queryNearbyVenues(dto))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.MOVIE_NOT_FOUND);
        verify(geoOperations, never()).radius(eq("venue:geo:上海:CINEMA"), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class));
    }

    @Test
    void unsupportedBusinessTypeShouldBeRejected() {
        VenueNearbyQueryDTO dto = nearbyQuery(1, 10);
        dto.setBizType("ACTIVITY");
        dto.setBizId(1L);

        assertThatThrownBy(() -> venueService.queryNearbyVenues(dto))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_BIZ_TYPE);
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

    private VenueNearbyQueryDTO nearbyQuery(int page, int pageSize) {
        VenueNearbyQueryDTO dto = new VenueNearbyQueryDTO();
        dto.setCity("上海");
        dto.setType(VenueType.CINEMA);
        dto.setX(121.437D);
        dto.setY(31.188D);
        dto.setDistance(5000);
        dto.setPage(page);
        dto.setPageSize(pageSize);
        return dto;
    }

    private GeoResult<RedisGeoCommands.GeoLocation<String>> geoResult(String id, double meters) {
        RedisGeoCommands.GeoLocation<String> location =
                new RedisGeoCommands.GeoLocation<>(id, new Point(0D, 0D));
        return new GeoResult<>(location, new Distance(meters / 1000D, Metrics.KILOMETERS));
    }

    @SafeVarargs
    private final GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults(
            GeoResult<RedisGeoCommands.GeoLocation<String>>... results) {
        return new GeoResults<>(Arrays.asList(results));
    }
}
