package com.movielife.service.impl;

import com.movielife.dto.ActivityNearbyQueryDTO;
import com.movielife.dto.ActivityShowtimeQueryDTO;
import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Showtime;
import com.movielife.entity.Venue;
import com.movielife.enums.VenueType;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.vo.ActivityShowtimeVO;
import com.movielife.vo.ActivityNearbyVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityServiceImplTest {

    @Mock private ActivityMapper activityMapper;
    @Mock private ShowtimeMapper showtimeMapper;
    @Mock private VenueMapper venueMapper;
    @Mock private HallMapper hallMapper;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private GeoOperations<String, String> geoOperations;

    private ActivityServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ActivityServiceImpl(
                activityMapper, showtimeMapper, venueMapper, hallMapper, redisTemplate);
        lenient().when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
    }

    @Test
    void detailShouldReturnOnlyActiveActivity() {
        Activity activity = activity(1L, 1);
        activity.setDescription("现场演出");
        when(activityMapper.selectById(1L)).thenReturn(activity);

        assertThat(service.queryActivityDetail(1L).getDescription()).isEqualTo("现场演出");

        when(activityMapper.selectById(2L)).thenReturn(activity(2L, 0));
        assertError(() -> service.queryActivityDetail(2L), ErrorCode.ACTIVITY_NOT_FOUND);
        assertError(() -> service.queryActivityDetail(0L), ErrorCode.INVALID_ID);
    }

    @Test
    void showtimesShouldBatchRelationsAndFilterDisabledVenue() {
        when(activityMapper.selectById(1L)).thenReturn(activity(1L, 1));
        Showtime enabled = showtime(10L, 20L, 30L);
        Showtime disabled = showtime(11L, 21L, 31L);
        when(showtimeMapper.selectList(any())).thenReturn(Arrays.asList(enabled, disabled));
        when(venueMapper.selectBatchIds(any())).thenReturn(Arrays.asList(
                venue(20L, "有效剧场", 1), venue(21L, "停用剧场", 0)));
        when(hallMapper.selectBatchIds(any())).thenReturn(Arrays.asList(
                hall(30L, 20L, "主剧场"), hall(31L, 21L, "停用厅")));

        List<ActivityShowtimeVO> result = service.queryActivityShowtimes(
                1L, new ActivityShowtimeQueryDTO());

        assertThat(result).extracting(ActivityShowtimeVO::getShowtimeId).containsExactly(10L);
        assertThat(result.get(0).getVenueName()).isEqualTo("有效剧场");
        verify(venueMapper).selectBatchIds(any());
        verify(hallMapper).selectBatchIds(any());
    }

    @Test
    void emptyShowtimesShouldAvoidRelationQueries() {
        when(activityMapper.selectById(1L)).thenReturn(activity(1L, 1));
        when(showtimeMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertThat(service.queryActivityShowtimes(1L, new ActivityShowtimeQueryDTO())).isEmpty();
        verify(venueMapper, never()).selectBatchIds(any());
        verify(hallMapper, never()).selectBatchIds(any());
    }

    @Test
    void brokenShowtimeRelationShouldFailClosed() {
        when(activityMapper.selectById(1L)).thenReturn(activity(1L, 1));
        when(showtimeMapper.selectList(any())).thenReturn(Collections.singletonList(
                showtime(10L, 20L, 30L)));
        when(venueMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(
                venue(20L, "有效剧场", 1)));
        when(hallMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(
                hall(30L, 999L, "错误归属厅")));

        assertError(() -> service.queryActivityShowtimes(
                1L, new ActivityShowtimeQueryDTO()), ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void nearbyShouldMergeVenueTypesAndChooseNearestVenueThenNextShowtime() {
        ActivityNearbyQueryDTO query = nearbyQuery();
        when(geoOperations.radius(eq("venue:geo:上海:CINEMA"), any(Circle.class), any()))
                .thenReturn(geoResults(geoResult("10", 100D)));
        when(geoOperations.radius(eq("venue:geo:上海:THEATER"), any(Circle.class), any()))
                .thenReturn(geoResults(geoResult("20", 200D), geoResult("10", 300D)));
        when(venueMapper.selectBatchIds(any())).thenReturn(Arrays.asList(
                nearbyVenue(10L, "cinema", "最近影院"),
                nearbyVenue(20L, "theater", "较远剧场")));

        Showtime activityOneFar = nearbyShowtime(101L, 1L, 20L, 201L, 2);
        Showtime activityOneNear = nearbyShowtime(102L, 1L, 10L, 101L, 3);
        Showtime activityTwoLater = nearbyShowtime(202L, 2L, 20L, 201L, 4);
        Showtime activityTwoNext = nearbyShowtime(201L, 2L, 20L, 201L, 1);
        when(showtimeMapper.selectFutureActivityShowtimesByVenueIds(any()))
                .thenReturn(Arrays.asList(
                        activityOneFar, activityOneNear, activityTwoLater, activityTwoNext));
        when(activityMapper.selectBatchIds(any())).thenReturn(Arrays.asList(
                activity(1L, 1), activity(2L, 1)));
        when(hallMapper.selectBatchIds(any())).thenReturn(Arrays.asList(
                hall(101L, 10L, "影院厅"), hall(201L, 20L, "剧场厅")));

        List<ActivityNearbyVO> result = service.queryNearbyActivities(query);

        assertThat(result).extracting(ActivityNearbyVO::getId).containsExactly(1L, 2L);
        assertThat(result.get(0).getNearestVenueId()).isEqualTo(10L);
        assertThat(result.get(0).getNextShowtimeId()).isEqualTo(102L);
        assertThat(result.get(1).getNearestVenueId()).isEqualTo(20L);
        assertThat(result.get(1).getNextShowtimeId()).isEqualTo(201L);
        verify(venueMapper).selectBatchIds(any());
        verify(showtimeMapper).selectFutureActivityShowtimesByVenueIds(any());
        verify(activityMapper).selectBatchIds(any());
        verify(hallMapper).selectBatchIds(any());
        verify(activityMapper, never()).selectById(any());
        verify(venueMapper, never()).selectById(any());
        verify(hallMapper, never()).selectById(any());
    }

    @Test
    void nearbyShouldFilterByActivityTypeBeforePaging() {
        ActivityNearbyQueryDTO query = nearbyQuery();
        query.setVenueType(VenueType.CINEMA);
        query.setType("drama");
        query.setPageSize(1);
        when(geoOperations.radius(eq("venue:geo:上海:CINEMA"), any(Circle.class), any()))
                .thenReturn(geoResults(geoResult("10", 100D), geoResult("20", 200D)));
        when(venueMapper.selectBatchIds(any())).thenReturn(Arrays.asList(
                nearbyVenue(10L, "cinema", "最近影院"),
                nearbyVenue(20L, "cinema", "较远影院")));
        when(showtimeMapper.selectFutureActivityShowtimesByVenueIds(any())).thenReturn(Arrays.asList(
                nearbyShowtime(101L, 1L, 10L, 101L, 1),
                nearbyShowtime(201L, 2L, 20L, 201L, 1)));
        Activity filtered = activity(1L, 1);
        filtered.setType("talkshow");
        Activity matching = activity(2L, 1);
        matching.setType("drama");
        when(activityMapper.selectBatchIds(any())).thenReturn(Arrays.asList(filtered, matching));
        when(hallMapper.selectBatchIds(any())).thenReturn(Arrays.asList(
                hall(101L, 10L, "一号厅"), hall(201L, 20L, "二号厅")));

        assertThat(service.queryNearbyActivities(query))
                .extracting(ActivityNearbyVO::getId)
                .containsExactly(2L);
    }

    private Activity activity(Long id, int status) {
        Activity value = new Activity();
        value.setId(id);
        value.setTitle("示例活动" + id);
        value.setType("talkshow");
        value.setCity("上海");
        value.setPriceMin(new BigDecimal("199.00"));
        value.setPriceMax(new BigDecimal("299.00"));
        value.setStatus(status);
        return value;
    }

    private Showtime showtime(Long id, Long venueId, Long hallId) {
        Showtime value = new Showtime();
        value.setId(id);
        value.setBizType("ACTIVITY");
        value.setBizId(1L);
        value.setVenueId(venueId);
        value.setHallId(hallId);
        value.setStartTime(LocalDateTime.now().plusDays(1));
        value.setEndTime(LocalDateTime.now().plusDays(1).plusHours(2));
        value.setBasePrice(new BigDecimal("199.00"));
        value.setTotalSeats(6);
        value.setAvailableSeats(6);
        value.setStatus(1);
        return value;
    }

    private Venue venue(Long id, String name, int status) {
        Venue value = new Venue();
        value.setId(id);
        value.setName(name);
        value.setStatus(status);
        return value;
    }

    private Venue nearbyVenue(Long id, String type, String name) {
        Venue value = venue(id, name, 1);
        value.setType(type);
        value.setCity("上海");
        value.setAddress("测试地址");
        value.setLongitude(new BigDecimal("121.47"));
        value.setLatitude(new BigDecimal("31.23"));
        return value;
    }

    private Hall hall(Long id, Long venueId, String name) {
        Hall value = new Hall();
        value.setId(id);
        value.setVenueId(venueId);
        value.setName(name);
        return value;
    }

    private ActivityNearbyQueryDTO nearbyQuery() {
        ActivityNearbyQueryDTO query = new ActivityNearbyQueryDTO();
        query.setCity("上海");
        query.setX(121.47D);
        query.setY(31.23D);
        return query;
    }

    private Showtime nearbyShowtime(Long id,
                                    Long activityId,
                                    Long venueId,
                                    Long hallId,
                                    int daysFromNow) {
        Showtime value = showtime(id, venueId, hallId);
        value.setBizId(activityId);
        value.setStartTime(LocalDateTime.now().plusDays(daysFromNow));
        value.setEndTime(value.getStartTime().plusHours(2));
        return value;
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

    private void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(expected);
    }
}
