package com.movielife.service;

import com.movielife.constants.RedisConstants;
import com.movielife.dto.ActivityNearbyQueryDTO;
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
import com.movielife.vo.ActivityNearbyVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "movielife.startup-check.enabled=false")
@Transactional
class ActivityNearbyBoundaryIntegrationTest {

    private static final String TEST_CITY = "STEP114边界市";
    private static final String CINEMA_KEY = RedisConstants.VENUE_GEO_KEY + TEST_CITY + ":CINEMA";
    private static final String THEATER_KEY = RedisConstants.VENUE_GEO_KEY + TEST_CITY + ":THEATER";

    @Autowired private ActivityService activityService;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private VenueMapper venueMapper;
    @Autowired private HallMapper hallMapper;
    @Autowired private ShowtimeMapper showtimeMapper;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    @AfterEach
    void clearGeoKeys() {
        redisTemplate.delete(CINEMA_KEY);
        redisTemplate.delete(THEATER_KEY);
    }

    @Test
    void missingGeoKeysShouldReturnEmptyList() {
        assertThat(activityService.queryNearbyActivities(query())).isEmpty();
    }

    @Test
    void shouldIgnoreStaleVenuesOfflineActivitiesAndInvalidShowtimesButKeepSoldOutActivity() {
        Activity soldOutActivity = insertActivity("售罄仍可发现", 1);
        Activity offlineActivity = insertActivity("已下线活动", 0);
        Activity stoppedActivity = insertActivity("停售场次活动", 1);
        Activity endedActivity = insertActivity("已结束活动", 1);
        Activity wrongBizActivity = insertActivity("错误业务类型活动", 1);
        Activity disabledVenueActivity = insertActivity("停用场馆活动", 1);

        Venue staleNearest = insertVenue("即将删除的场馆", "cinema", 1, "121.470010");
        Venue invalidShowtimeVenue = insertVenue("无有效场次场馆", "cinema", 1, "121.470020");
        Venue soldOutVenue = insertVenue("售罄活动场馆", "theater", 1, "121.470030");
        Venue offlineVenue = insertVenue("下线活动场馆", "theater", 1, "121.470040");
        Venue disabledVenue = insertVenue("停用场馆", "theater", 0, "121.470050");

        Hall invalidHall = insertHall(invalidShowtimeVenue);
        Hall soldOutHall = insertHall(soldOutVenue);
        Hall offlineHall = insertHall(offlineVenue);
        Hall disabledHall = insertHall(disabledVenue);

        insertShowtime(stoppedActivity, invalidShowtimeVenue, invalidHall,
                "ACTIVITY", LocalDateTime.now().plusDays(1), 0, 10);
        insertShowtime(endedActivity, invalidShowtimeVenue, invalidHall,
                "ACTIVITY", LocalDateTime.now().minusDays(1), 1, 10);
        insertShowtime(wrongBizActivity, invalidShowtimeVenue, invalidHall,
                "MOVIE", LocalDateTime.now().plusDays(1), 1, 10);
        Showtime soldOut = insertShowtime(soldOutActivity, soldOutVenue, soldOutHall,
                "ACTIVITY", LocalDateTime.now().plusDays(2), 1, 0);
        insertShowtime(offlineActivity, offlineVenue, offlineHall,
                "ACTIVITY", LocalDateTime.now().plusDays(1), 1, 10);
        insertShowtime(disabledVenueActivity, disabledVenue, disabledHall,
                "ACTIVITY", LocalDateTime.now().plusDays(1), 1, 10);

        addGeo(CINEMA_KEY, staleNearest);
        addGeo(CINEMA_KEY, invalidShowtimeVenue);
        addGeo(THEATER_KEY, soldOutVenue);
        addGeo(THEATER_KEY, offlineVenue);
        addGeo(THEATER_KEY, disabledVenue);
        venueMapper.deleteById(staleNearest.getId());
        redisTemplate.opsForGeo().add(
                CINEMA_KEY, new Point(121.470001D, 31.230000D), "999999999");

        List<ActivityNearbyVO> result = activityService.queryNearbyActivities(query());

        assertThat(result).extracting(ActivityNearbyVO::getId)
                .containsExactly(soldOutActivity.getId());
        assertThat(result.get(0).getNextShowtimeId()).isEqualTo(soldOut.getId());
        assertThat(result.get(0).getNextAvailableSeats()).isZero();
    }

    @Test
    void hallOwnedByAnotherVenueShouldFailClosed() {
        Activity activity = insertActivity("错误场馆影厅归属", 1);
        Venue showtimeVenue = insertVenue("场次场馆", "theater", 1, "121.471000");
        Venue hallVenue = insertVenue("影厅实际场馆", "theater", 1, "121.472000");
        Hall wrongHall = insertHall(hallVenue);
        insertShowtime(activity, showtimeVenue, wrongHall,
                "ACTIVITY", LocalDateTime.now().plusDays(1), 1, 10);
        addGeo(THEATER_KEY, showtimeVenue);

        assertThatThrownBy(() -> activityService.queryNearbyActivities(query()))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    private ActivityNearbyQueryDTO query() {
        ActivityNearbyQueryDTO query = new ActivityNearbyQueryDTO();
        query.setCity(TEST_CITY);
        query.setX(121.47D);
        query.setY(31.23D);
        query.setDistance(5000);
        query.setPage(1);
        query.setPageSize(1);
        return query;
    }

    private Activity insertActivity(String title, int status) {
        Activity value = new Activity();
        value.setTitle(title);
        value.setType("drama");
        value.setCity(TEST_CITY);
        value.setDescription("Step 11.4 边界测试");
        value.setPriceMin(new BigDecimal("99.00"));
        value.setPriceMax(new BigDecimal("299.00"));
        value.setStatus(status);
        activityMapper.insert(value);
        return value;
    }

    private Venue insertVenue(String name, String type, int status, String longitude) {
        Venue value = new Venue();
        value.setName(name);
        value.setType(type);
        value.setCity(TEST_CITY);
        value.setAddress("Step 11.4 测试地址");
        value.setLongitude(new BigDecimal(longitude));
        value.setLatitude(new BigDecimal("31.230000"));
        value.setAvgScore(BigDecimal.ZERO);
        value.setStatus(status);
        venueMapper.insert(value);
        return value;
    }

    private Hall insertHall(Venue venue) {
        Hall value = new Hall();
        value.setVenueId(venue.getId());
        value.setName("Step114厅" + venue.getId());
        hallMapper.insert(value);
        return value;
    }

    private Showtime insertShowtime(Activity activity,
                                    Venue venue,
                                    Hall hall,
                                    String bizType,
                                    LocalDateTime start,
                                    int status,
                                    int availableSeats) {
        Showtime value = new Showtime();
        value.setBizType(bizType);
        value.setBizId(activity.getId());
        value.setVenueId(venue.getId());
        value.setHallId(hall.getId());
        value.setStartTime(start.withNano(0));
        value.setEndTime(start.plusHours(2).withNano(0));
        value.setBasePrice(new BigDecimal("99.00"));
        value.setTotalSeats(10);
        value.setAvailableSeats(availableSeats);
        value.setStatus(status);
        showtimeMapper.insert(value);
        return value;
    }

    private void addGeo(String key, Venue venue) {
        redisTemplate.opsForGeo().add(key,
                new Point(venue.getLongitude().doubleValue(), venue.getLatitude().doubleValue()),
                venue.getId().toString());
    }
}
