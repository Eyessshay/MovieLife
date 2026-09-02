package com.movielife.service;

import com.movielife.constants.RedisConstants;
import com.movielife.entity.Venue;
import com.movielife.mapper.VenueMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

@SpringBootTest(properties = "movielife.startup-check.enabled=false")
@Transactional
class VenueGeoServiceIntegrationTest {

    private static final String TEST_CITY = "GEO测试市";
    private static final String CINEMA_KEY = RedisConstants.VENUE_GEO_KEY + TEST_CITY + ":CINEMA";
    private static final String THEATER_KEY = RedisConstants.VENUE_GEO_KEY + TEST_CITY + ":THEATER";

    @Autowired
    private VenueGeoService venueGeoService;
    @Autowired
    private VenueMapper venueMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    @AfterEach
    void clearTestGeoKeys() {
        stringRedisTemplate.delete(CINEMA_KEY);
        stringRedisTemplate.delete(THEATER_KEY);
    }

    @Test
    void shouldInitializeEligibleVenuesByCityAndTypeIdempotently() {
        Venue cinema = insertVenue("GEO测试影院", "cinema", 1, "113.100000", "23.100000");
        Venue theater = insertVenue("GEO测试剧场", "theater", 1, "113.200000", "23.200000");
        Venue disabled = insertVenue("GEO停用影院", "cinema", 0, "113.300000", "23.300000");
        Venue missingCoordinate = insertVenue("GEO无坐标影院", "cinema", 1, null, "23.400000");

        venueGeoService.initializeVenueGeo();
        venueGeoService.initializeVenueGeo();

        assertThat(stringRedisTemplate.opsForZSet().zCard(CINEMA_KEY)).isEqualTo(1L);
        assertThat(stringRedisTemplate.opsForZSet().zCard(THEATER_KEY)).isEqualTo(1L);
        assertPosition(CINEMA_KEY, cinema.getId(), 113.100000, 23.100000);
        assertPosition(THEATER_KEY, theater.getId(), 113.200000, 23.200000);
        assertThat(position(CINEMA_KEY, disabled.getId())).isNull();
        assertThat(position(CINEMA_KEY, missingCoordinate.getId())).isNull();
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
        venue.setAddress("GEO测试地址");
        venue.setLongitude(longitude == null ? null : new BigDecimal(longitude));
        venue.setLatitude(latitude == null ? null : new BigDecimal(latitude));
        venue.setAvgScore(BigDecimal.ZERO);
        venue.setStatus(status);
        venueMapper.insert(venue);
        return venue;
    }

    private void assertPosition(String key,
                                Long venueId,
                                double longitude,
                                double latitude) {
        Point point = position(key, venueId);
        assertThat(point).isNotNull();
        assertThat(point.getX()).isCloseTo(longitude, offset(0.00001));
        assertThat(point.getY()).isCloseTo(latitude, offset(0.00001));
    }

    private Point position(String key, Long venueId) {
        List<Point> positions = stringRedisTemplate.opsForGeo()
                .position(key, String.valueOf(venueId));
        return positions == null || positions.isEmpty() ? null : positions.get(0);
    }
}
