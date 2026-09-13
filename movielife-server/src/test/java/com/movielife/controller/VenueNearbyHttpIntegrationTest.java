package com.movielife.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.constants.RedisConstants;
import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Showtime;
import com.movielife.entity.Venue;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.VenueMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "movielife.startup-check.enabled=false"
)
class VenueNearbyHttpIntegrationTest {

    private static final String TEST_CITY = "STEP112HTTP";
    private static final String GEO_KEY = RedisConstants.VENUE_GEO_KEY + TEST_CITY + ":THEATER";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private VenueMapper venueMapper;
    @Autowired private HallMapper hallMapper;
    @Autowired private ShowtimeMapper showtimeMapper;

    private Long activityId;
    private Long showtimeId;
    private final List<Long> venueIds = new ArrayList<>();
    private final List<Long> hallIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        redisTemplate.delete(GEO_KEY);

        Activity activity = new Activity();
        activity.setTitle("Step112Http附近活动");
        activity.setType("drama");
        activity.setCity(TEST_CITY);
        activity.setDescription("Step 11.2 真实 HTTP 联调");
        activity.setPriceMin(new BigDecimal("88.00"));
        activity.setPriceMax(new BigDecimal("188.00"));
        activity.setStatus(1);
        activityMapper.insert(activity);
        activityId = activity.getId();

        Venue nearestWithoutShowtime = insertVenue("Step112最近但无场次剧场");
        Venue fartherWithShowtime = insertVenue("Step112较远有场次剧场");
        Hall hall = insertHall(fartherWithShowtime);

        Showtime showtime = new Showtime();
        showtime.setBizType("ACTIVITY");
        showtime.setBizId(activityId);
        showtime.setVenueId(fartherWithShowtime.getId());
        showtime.setHallId(hall.getId());
        showtime.setStartTime(LocalDateTime.now().plusDays(2));
        showtime.setEndTime(LocalDateTime.now().plusDays(2).plusHours(2));
        showtime.setBasePrice(new BigDecimal("88.00"));
        showtime.setTotalSeats(100);
        showtime.setAvailableSeats(100);
        showtime.setStatus(1);
        showtimeMapper.insert(showtime);
        showtimeId = showtime.getId();

        redisTemplate.opsForGeo().add(GEO_KEY, new Point(121.470000D, 31.230000D),
                nearestWithoutShowtime.getId().toString());
        redisTemplate.opsForGeo().add(GEO_KEY, new Point(121.480000D, 31.230000D),
                fartherWithShowtime.getId().toString());
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(GEO_KEY);
        if (showtimeId != null) {
            showtimeMapper.deleteById(showtimeId);
        }
        for (Long hallId : hallIds) {
            hallMapper.deleteById(hallId);
        }
        for (Long venueId : venueIds) {
            venueMapper.deleteById(venueId);
        }
        if (activityId != null) {
            activityMapper.deleteById(activityId);
        }
    }

    @Test
    void activityNearbyHttpShouldFilterBeforePagingAndRemainReadOnly() throws Exception {
        Activity activityBefore = activityMapper.selectById(activityId);
        Showtime showtimeBefore = showtimeMapper.selectById(showtimeId);
        Set<ZSetOperations.TypedTuple<String>> geoBefore =
                redisTemplate.opsForZSet().rangeWithScores(GEO_KEY, 0, -1);

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/venues/nearby?city=" + TEST_CITY
                        + "&type=THEATER&bizType=ACTIVITY&bizId=" + activityId
                        + "&x=121.47&y=31.23&page=1&pageSize=1",
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo("OK");
        assertThat(body.path("data")).hasSize(1);
        assertThat(body.path("data").get(0).path("id").asLong())
                .isEqualTo(showtimeBefore.getVenueId());
        assertThat(body.path("data").get(0).path("distance").asDouble()).isPositive();

        assertThat(activityMapper.selectById(activityId)).isEqualTo(activityBefore);
        assertThat(showtimeMapper.selectById(showtimeId)).isEqualTo(showtimeBefore);
        assertThat(redisTemplate.opsForZSet().rangeWithScores(GEO_KEY, 0, -1))
                .isEqualTo(geoBefore);
    }

    private Venue insertVenue(String name) {
        Venue venue = new Venue();
        venue.setName(name);
        venue.setType("theater");
        venue.setCity(TEST_CITY);
        venue.setAddress("Step 11.2 HTTP 测试地址");
        venue.setLongitude(new BigDecimal("121.470000"));
        venue.setLatitude(new BigDecimal("31.230000"));
        venue.setAvgScore(BigDecimal.ZERO);
        venue.setStatus(1);
        venueMapper.insert(venue);
        venueIds.add(venue.getId());
        return venue;
    }

    private Hall insertHall(Venue venue) {
        Hall hall = new Hall();
        hall.setVenueId(venue.getId());
        hall.setName("Step112活动厅");
        hallMapper.insert(hall);
        hallIds.add(hall.getId());
        return hall;
    }
}
