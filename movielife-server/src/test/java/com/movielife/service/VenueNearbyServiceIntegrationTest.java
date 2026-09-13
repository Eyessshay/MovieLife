package com.movielife.service;

import com.movielife.constants.RedisConstants;
import com.movielife.dto.VenueNearbyQueryDTO;
import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Showtime;
import com.movielife.entity.Venue;
import com.movielife.enums.VenueType;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.vo.VenueNearbyVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "movielife.startup-check.enabled=false")
@Transactional
class VenueNearbyServiceIntegrationTest {

    private static final String TEST_CITY = "NEARBY测试市";
    private static final String CINEMA_KEY = RedisConstants.VENUE_GEO_KEY + TEST_CITY + ":CINEMA";
    private static final String THEATER_KEY = RedisConstants.VENUE_GEO_KEY + TEST_CITY + ":THEATER";
    private static final String MISSING_CITY = "NEARBY空城市";
    private static final String MISSING_KEY = RedisConstants.VENUE_GEO_KEY + MISSING_CITY + ":CINEMA";

    @Autowired
    private VenueService venueService;
    @Autowired
    private VenueMapper venueMapper;
    @Autowired
    private HallMapper hallMapper;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private ShowtimeMapper showtimeMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private Venue nearest;
    private Venue farther;
    private Hall nearestHall;
    private Hall fartherHall;

    @BeforeEach
    void setUp() {
        clearKeys();
        nearest = insertVenue("附近影院A", "cinema", 1, "121.000000", "31.000000");
        farther = insertVenue("附近影院B", "cinema", 1, "121.010000", "31.000000");
        Venue disabled = insertVenue("停用剧场", "theater", 0, "121.000000", "31.000000");
        nearestHall = insertHall(nearest, "测试厅A");
        fartherHall = insertHall(farther, "测试厅B");

        stringRedisTemplate.opsForGeo().add(CINEMA_KEY, Arrays.asList(
                geoLocation(nearest),
                geoLocation(farther)
        ));
        stringRedisTemplate.opsForGeo().add(THEATER_KEY, Collections.singletonList(
                geoLocation(disabled)
        ));
    }

    @AfterEach
    void tearDown() {
        clearKeys();
    }

    @Test
    void shouldReturnNearbyCinemasInDistanceOrder() {
        List<VenueNearbyVO> result = venueService.queryNearbyVenues(query(TEST_CITY, VenueType.CINEMA, 5000, 1, 10));

        assertThat(result).extracting(VenueNearbyVO::getId)
                .containsExactly(nearest.getId(), farther.getId());
        assertThat(result.get(0).getDistance()).isLessThan(result.get(1).getDistance());
        assertThat(result.get(1).getDistance()).isGreaterThan(100D);
    }

    @Test
    void shouldSupportGeoPaginationAndOutOfRangePage() {
        assertThat(venueService.queryNearbyVenues(query(TEST_CITY, VenueType.CINEMA, 5000, 1, 1)))
                .extracting(VenueNearbyVO::getId)
                .containsExactly(nearest.getId());
        assertThat(venueService.queryNearbyVenues(query(TEST_CITY, VenueType.CINEMA, 5000, 2, 1)))
                .extracting(VenueNearbyVO::getId)
                .containsExactly(farther.getId());
        assertThat(venueService.queryNearbyVenues(query(TEST_CITY, VenueType.CINEMA, 5000, 3, 1)))
                .isEmpty();
    }

    @Test
    void shouldFilterByRadiusAndIgnoreDisabledVenue() {
        assertThat(venueService.queryNearbyVenues(query(TEST_CITY, VenueType.CINEMA, 100, 1, 10)))
                .extracting(VenueNearbyVO::getId)
                .containsExactly(nearest.getId());
        assertThat(venueService.queryNearbyVenues(query(TEST_CITY, VenueType.THEATER, 5000, 1, 10)))
                .isEmpty();
    }

    @Test
    void missingGeoKeyShouldReturnEmptyList() {
        assertThat(venueService.queryNearbyVenues(query(MISSING_CITY, VenueType.CINEMA, 5000, 1, 10)))
                .isEmpty();
    }

    @Test
    void geoMemberWithoutDatabaseVenueShouldBeIgnored() {
        stringRedisTemplate.opsForGeo().add(
                CINEMA_KEY,
                new Point(121.000000D, 31.000000D),
                "999999999"
        );

        assertThat(venueService.queryNearbyVenues(query(TEST_CITY, VenueType.CINEMA, 5000, 1, 10)))
                .extracting(VenueNearbyVO::getId)
                .containsExactly(nearest.getId(), farther.getId());
    }

    @Test
    void movieFilterShouldKeepOnlyFutureOnSaleShowtimes() {
        insertShowtime(nearest, nearestHall, 1L, LocalDateTime.now().plusDays(1), 1);
        insertShowtime(farther, fartherHall, 1L, LocalDateTime.now().minusDays(1), 1);
        insertShowtime(farther, fartherHall, 1L, LocalDateTime.now().plusDays(2), 0);

        assertThat(venueService.queryNearbyVenues(movieQuery(1L, 1, 10)))
                .extracting(VenueNearbyVO::getId)
                .containsExactly(nearest.getId());
    }

    @Test
    void movieFilterShouldPreserveGeoDistanceOrder() {
        insertShowtime(nearest, nearestHall, 1L, LocalDateTime.now().plusDays(1), 1);
        insertShowtime(farther, fartherHall, 1L, LocalDateTime.now().plusDays(1), 1);

        assertThat(venueService.queryNearbyVenues(movieQuery(1L, 1, 10)))
                .extracting(VenueNearbyVO::getId)
                .containsExactly(nearest.getId(), farther.getId());
    }

    @Test
    void multipleValidShowtimesAtSameVenueShouldReturnVenueOnce() {
        insertShowtime(nearest, nearestHall, 1L, LocalDateTime.now().plusDays(1), 1);
        insertShowtime(nearest, nearestHall, 1L, LocalDateTime.now().plusDays(2), 1);

        assertThat(venueService.queryNearbyVenues(movieQuery(1L, 1, 10)))
                .extracting(VenueNearbyVO::getId)
                .containsExactly(nearest.getId());
    }

    @Test
    void movieFilterShouldPaginateAfterShowtimeFiltering() {
        insertShowtime(farther, fartherHall, 1L, LocalDateTime.now().plusDays(1), 1);

        assertThat(venueService.queryNearbyVenues(movieQuery(1L, 1, 1)))
                .extracting(VenueNearbyVO::getId)
                .containsExactly(farther.getId());
    }

    @Test
    void existingMovieWithoutNearbyShowtimeShouldReturnEmptyList() {
        assertThat(venueService.queryNearbyVenues(movieQuery(1L, 1, 10))).isEmpty();
    }

    @Test
    void missingMovieShouldReturnMovieNotFound() {
        assertThatThrownBy(() -> venueService.queryNearbyVenues(movieQuery(999999999L, 1, 10)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.MOVIE_NOT_FOUND);
    }

    @Test
    void activityFilterShouldKeepFutureOnSaleVenuesInGeoOrderAndDeduplicate() {
        Activity activity = insertActivity("附近活动", 1);
        insertActivityShowtime(nearest, nearestHall, activity,
                LocalDateTime.now().plusDays(1), 1);
        insertActivityShowtime(nearest, nearestHall, activity,
                LocalDateTime.now().plusDays(2), 1);
        insertActivityShowtime(farther, fartherHall, activity,
                LocalDateTime.now().minusDays(1), 1);
        insertActivityShowtime(farther, fartherHall, activity,
                LocalDateTime.now().plusDays(2), 0);

        assertThat(venueService.queryNearbyVenues(activityQuery(activity.getId(), 1, 10)))
                .extracting(VenueNearbyVO::getId)
                .containsExactly(nearest.getId());
    }

    @Test
    void activityFilterShouldPaginateAfterShowtimeFiltering() {
        Activity activity = insertActivity("过滤后分页活动", 1);
        insertActivityShowtime(farther, fartherHall, activity,
                LocalDateTime.now().plusDays(1), 1);

        assertThat(venueService.queryNearbyVenues(activityQuery(activity.getId(), 1, 1)))
                .extracting(VenueNearbyVO::getId)
                .containsExactly(farther.getId());
    }

    @Test
    void unavailableActivityShouldReturnActivityNotFound() {
        Activity offline = insertActivity("已下线附近活动", 2);

        assertThatThrownBy(() -> venueService.queryNearbyVenues(
                activityQuery(offline.getId(), 1, 10)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVITY_NOT_FOUND);

        assertThatThrownBy(() -> venueService.queryNearbyVenues(
                activityQuery(999999999L, 1, 10)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVITY_NOT_FOUND);
    }

    private VenueNearbyQueryDTO query(String city,
                                      VenueType type,
                                      int distance,
                                      int page,
                                      int pageSize) {
        VenueNearbyQueryDTO dto = new VenueNearbyQueryDTO();
        dto.setCity(city);
        dto.setType(type);
        dto.setX(121.000000D);
        dto.setY(31.000000D);
        dto.setDistance(distance);
        dto.setPage(page);
        dto.setPageSize(pageSize);
        return dto;
    }

    private VenueNearbyQueryDTO movieQuery(Long movieId, int page, int pageSize) {
        VenueNearbyQueryDTO dto = query(TEST_CITY, VenueType.CINEMA, 5000, page, pageSize);
        dto.setBizType("MOVIE");
        dto.setBizId(movieId);
        return dto;
    }

    private VenueNearbyQueryDTO activityQuery(Long activityId, int page, int pageSize) {
        VenueNearbyQueryDTO dto = query(TEST_CITY, VenueType.CINEMA, 5000, page, pageSize);
        dto.setBizType("ACTIVITY");
        dto.setBizId(activityId);
        return dto;
    }

    private Activity insertActivity(String title, int status) {
        Activity activity = new Activity();
        activity.setTitle(title);
        activity.setType("talkshow");
        activity.setCity(TEST_CITY);
        activity.setDescription("Step 11.2 附近活动测试");
        activity.setPriceMin(new BigDecimal("99.00"));
        activity.setPriceMax(new BigDecimal("299.00"));
        activity.setStatus(status);
        activityMapper.insert(activity);
        return activity;
    }

    private Showtime insertActivityShowtime(Venue venue,
                                             Hall hall,
                                             Activity activity,
                                             LocalDateTime startTime,
                                             int status) {
        Showtime showtime = new Showtime();
        showtime.setBizType("ACTIVITY");
        showtime.setBizId(activity.getId());
        showtime.setVenueId(venue.getId());
        showtime.setHallId(hall.getId());
        showtime.setStartTime(startTime);
        showtime.setEndTime(startTime.plusHours(2));
        showtime.setBasePrice(new BigDecimal("199.00"));
        showtime.setTotalSeats(100);
        showtime.setAvailableSeats(100);
        showtime.setStatus(status);
        showtimeMapper.insert(showtime);
        return showtime;
    }

    private RedisGeoCommands.GeoLocation<String> geoLocation(Venue venue) {
        return new RedisGeoCommands.GeoLocation<>(
                String.valueOf(venue.getId()),
                new Point(venue.getLongitude().doubleValue(), venue.getLatitude().doubleValue())
        );
    }

    private Venue insertVenue(String name,
                              String type,
                              int status,
                              String longitude,
                              String latitude) {
        Venue venue = new Venue();
        venue.setName(name);
        venue.setType(type);
        venue.setCity(TEST_CITY);
        venue.setAddress("NEARBY测试地址");
        venue.setLongitude(new BigDecimal(longitude));
        venue.setLatitude(new BigDecimal(latitude));
        venue.setAvgScore(BigDecimal.ZERO);
        venue.setStatus(status);
        venueMapper.insert(venue);
        return venue;
    }

    private Hall insertHall(Venue venue, String name) {
        Hall hall = new Hall();
        hall.setVenueId(venue.getId());
        hall.setName(name);
        hallMapper.insert(hall);
        return hall;
    }

    private Showtime insertShowtime(Venue venue,
                                    Hall hall,
                                    Long movieId,
                                    LocalDateTime startTime,
                                    int status) {
        Showtime showtime = new Showtime();
        showtime.setBizType("MOVIE");
        showtime.setBizId(movieId);
        showtime.setVenueId(venue.getId());
        showtime.setHallId(hall.getId());
        showtime.setStartTime(startTime);
        showtime.setEndTime(startTime.plusHours(2));
        showtime.setBasePrice(new BigDecimal("45.00"));
        showtime.setTotalSeats(100);
        showtime.setAvailableSeats(100);
        showtime.setStatus(status);
        showtimeMapper.insert(showtime);
        return showtime;
    }

    private void clearKeys() {
        stringRedisTemplate.delete(CINEMA_KEY);
        stringRedisTemplate.delete(THEATER_KEY);
        stringRedisTemplate.delete(MISSING_KEY);
    }
}
