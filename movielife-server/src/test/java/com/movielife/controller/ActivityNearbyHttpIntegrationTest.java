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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "movielife.startup-check.enabled=false"
)
class ActivityNearbyHttpIntegrationTest {

    private static final String TEST_CITY = "STEP113HTTP";
    private static final String THEATER_KEY = RedisConstants.VENUE_GEO_KEY + TEST_CITY + ":THEATER";
    private static final String LIVEHOUSE_KEY = RedisConstants.VENUE_GEO_KEY + TEST_CITY + ":LIVEHOUSE";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private VenueMapper venueMapper;
    @Autowired private HallMapper hallMapper;
    @Autowired private ShowtimeMapper showtimeMapper;

    private final List<Long> activityIds = new ArrayList<>();
    private final List<Long> venueIds = new ArrayList<>();
    private final List<Long> hallIds = new ArrayList<>();
    private final List<Long> showtimeIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        redisTemplate.delete(Arrays.asList(THEATER_KEY, LIVEHOUSE_KEY));

        Activity drama = insertActivity("Step113巡演话剧", "drama", 1);
        Activity talkshow = insertActivity("Step113脱口秀", "talkshow", 1);
        Venue theater = insertVenue("Step113最近剧场", "theater", "121.471000");
        Venue livehouse = insertVenue("Step113较远现场", "livehouse", "121.480000");
        Hall theaterHall = insertHall(theater, "Step113剧场主厅");
        Hall livehouseHall = insertHall(livehouse, "Step113现场主厅");

        insertShowtime(drama, theater, theaterHall, 3, 60);
        insertShowtime(drama, theater, theaterHall, 4, 50);
        insertShowtime(drama, livehouse, livehouseHall, 1, 80);
        insertShowtime(talkshow, livehouse, livehouseHall, 2, 0);

        redisTemplate.opsForGeo().add(
                THEATER_KEY, new Point(121.471000D, 31.230000D), theater.getId().toString());
        redisTemplate.opsForGeo().add(
                LIVEHOUSE_KEY, new Point(121.480000D, 31.230000D), livehouse.getId().toString());
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(Arrays.asList(THEATER_KEY, LIVEHOUSE_KEY));
        showtimeIds.forEach(showtimeMapper::deleteById);
        hallIds.forEach(hallMapper::deleteById);
        venueIds.forEach(venueMapper::deleteById);
        activityIds.forEach(activityMapper::deleteById);
    }

    @Test
    void nearbyHttpShouldMergeTypesDeduplicateChooseContextAndRemainReadOnly() throws Exception {
        Map<Long, Activity> activitiesBefore = loadActivities();
        Map<Long, Venue> venuesBefore = loadVenues();
        Map<Long, Showtime> showtimesBefore = loadShowtimes();
        Set<ZSetOperations.TypedTuple<String>> theaterGeoBefore =
                redisTemplate.opsForZSet().rangeWithScores(THEATER_KEY, 0, -1);
        Set<ZSetOperations.TypedTuple<String>> livehouseGeoBefore =
                redisTemplate.opsForZSet().rangeWithScores(LIVEHOUSE_KEY, 0, -1);

        ResponseEntity<String> allTypesResponse = restTemplate.getForEntity(
                "/api/activities/nearby?city=" + TEST_CITY
                        + "&x=121.47&y=31.23&distance=5000&page=1&pageSize=20",
                String.class);

        assertThat(allTypesResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode allTypes = objectMapper.readTree(allTypesResponse.getBody());
        assertThat(allTypes.path("code").asText()).isEqualTo("OK");
        assertThat(allTypes.path("data")).hasSize(2);
        JsonNode drama = allTypes.path("data").get(0);
        assertThat(drama.path("title").asText()).isEqualTo("Step113巡演话剧");
        assertThat(drama.path("nearestVenueName").asText()).isEqualTo("Step113最近剧场");
        assertThat(drama.path("nearestVenueType").asText()).isEqualTo("THEATER");
        assertThat(LocalDateTime.parse(drama.path("nextStartTime").asText()))
                .isEqualTo(showtimesBefore.get(drama.path("nextShowtimeId").asLong())
                        .getStartTime());
        assertThat(allTypes.path("data").get(1).path("nextAvailableSeats").asInt())
                .isZero();

        ResponseEntity<String> filteredResponse = restTemplate.getForEntity(
                "/api/activities/nearby?city=" + TEST_CITY
                        + "&x=121.47&y=31.23&venueType=LIVEHOUSE&type=talkshow"
                        + "&page=1&pageSize=1",
                String.class);
        assertThat(filteredResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode filtered = objectMapper.readTree(filteredResponse.getBody());
        assertThat(filtered.path("data")).hasSize(1);
        assertThat(filtered.path("data").get(0).path("title").asText())
                .isEqualTo("Step113脱口秀");

        assertThat(loadActivities()).isEqualTo(activitiesBefore);
        assertThat(loadVenues()).isEqualTo(venuesBefore);
        assertThat(loadShowtimes()).isEqualTo(showtimesBefore);
        assertThat(redisTemplate.opsForZSet().rangeWithScores(THEATER_KEY, 0, -1))
                .isEqualTo(theaterGeoBefore);
        assertThat(redisTemplate.opsForZSet().rangeWithScores(LIVEHOUSE_KEY, 0, -1))
                .isEqualTo(livehouseGeoBefore);
    }

    private Activity insertActivity(String title, String type, int status) {
        Activity activity = new Activity();
        activity.setTitle(title);
        activity.setType(type);
        activity.setCity("巡演总部城市");
        activity.setDescription("Step 11.3 真实 HTTP 联调");
        activity.setPriceMin(new BigDecimal("99.00"));
        activity.setPriceMax(new BigDecimal("299.00"));
        activity.setStatus(status);
        activityMapper.insert(activity);
        activityIds.add(activity.getId());
        return activity;
    }

    private Venue insertVenue(String name, String type, String longitude) {
        Venue venue = new Venue();
        venue.setName(name);
        venue.setType(type);
        venue.setCity(TEST_CITY);
        venue.setAddress("Step 11.3 HTTP 测试地址");
        venue.setLongitude(new BigDecimal(longitude));
        venue.setLatitude(new BigDecimal("31.230000"));
        venue.setAvgScore(BigDecimal.ZERO);
        venue.setStatus(1);
        venueMapper.insert(venue);
        venueIds.add(venue.getId());
        return venue;
    }

    private Hall insertHall(Venue venue, String name) {
        Hall hall = new Hall();
        hall.setVenueId(venue.getId());
        hall.setName(name);
        hallMapper.insert(hall);
        hallIds.add(hall.getId());
        return hall;
    }

    private void insertShowtime(Activity activity,
                                Venue venue,
                                Hall hall,
                                int daysFromNow,
                                int availableSeats) {
        LocalDateTime startTime = LocalDateTime.now().plusDays(daysFromNow)
                .withSecond(0).withNano(0);
        Showtime showtime = new Showtime();
        showtime.setBizType("ACTIVITY");
        showtime.setBizId(activity.getId());
        showtime.setVenueId(venue.getId());
        showtime.setHallId(hall.getId());
        showtime.setStartTime(startTime);
        showtime.setEndTime(startTime.plusHours(2));
        showtime.setBasePrice(new BigDecimal("99.00"));
        showtime.setTotalSeats(100);
        showtime.setAvailableSeats(availableSeats);
        showtime.setStatus(1);
        showtimeMapper.insert(showtime);
        showtimeIds.add(showtime.getId());
    }

    private Map<Long, Activity> loadActivities() {
        Map<Long, Activity> result = new LinkedHashMap<>();
        activityMapper.selectBatchIds(activityIds).forEach(value -> result.put(value.getId(), value));
        return result;
    }

    private Map<Long, Venue> loadVenues() {
        Map<Long, Venue> result = new LinkedHashMap<>();
        venueMapper.selectBatchIds(venueIds).forEach(value -> result.put(value.getId(), value));
        return result;
    }

    private Map<Long, Showtime> loadShowtimes() {
        Map<Long, Showtime> result = new LinkedHashMap<>();
        showtimeMapper.selectBatchIds(showtimeIds).forEach(value -> result.put(value.getId(), value));
        return result;
    }
}
