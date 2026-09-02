package com.movielife.service;

import com.movielife.constants.RedisConstants;
import com.movielife.dto.VenueNearbyQueryDTO;
import com.movielife.entity.Venue;
import com.movielife.enums.VenueType;
import com.movielife.mapper.VenueMapper;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
    private StringRedisTemplate stringRedisTemplate;

    private Venue nearest;
    private Venue farther;

    @BeforeEach
    void setUp() {
        clearKeys();
        nearest = insertVenue("附近影院A", "cinema", 1, "121.000000", "31.000000");
        farther = insertVenue("附近影院B", "cinema", 1, "121.010000", "31.000000");
        Venue disabled = insertVenue("停用剧场", "theater", 0, "121.000000", "31.000000");

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

    private void clearKeys() {
        stringRedisTemplate.delete(CINEMA_KEY);
        stringRedisTemplate.delete(THEATER_KEY);
        stringRedisTemplate.delete(MISSING_KEY);
    }
}
