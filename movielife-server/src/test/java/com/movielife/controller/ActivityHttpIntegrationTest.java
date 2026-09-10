package com.movielife.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.constants.RedisConstants;
import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Seat;
import com.movielife.entity.Showtime;
import com.movielife.entity.ShowtimeSeat;
import com.movielife.entity.Venue;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.VenueMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "movielife.startup-check.enabled=false"
)
class ActivityHttpIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private VenueMapper venueMapper;
    @Autowired private HallMapper hallMapper;
    @Autowired private SeatMapper seatMapper;
    @Autowired private ShowtimeMapper showtimeMapper;
    @Autowired private ShowtimeSeatMapper showtimeSeatMapper;

    private Long activityId;
    private Long venueId;
    private Long hallId;
    private Long showtimeId;
    private final Set<Long> seatIds = new LinkedHashSet<>();

    @BeforeEach
    void setUp() {
        Activity activity = new Activity();
        activity.setTitle("Step102Http固定座位活动");
        activity.setType("drama");
        activity.setCity("上海");
        activity.setDescription("真实 HTTP 查询测试");
        activity.setPriceMin(new BigDecimal("120.00"));
        activity.setPriceMax(new BigDecimal("180.00"));
        activity.setStatus(1);
        activityMapper.insert(activity);
        activityId = activity.getId();

        Venue venue = new Venue();
        venue.setName("Step102Http剧场");
        venue.setType("theater");
        venue.setCity("上海");
        venue.setAddress("Step 10.2 HTTP 测试地址");
        venue.setAvgScore(BigDecimal.ZERO);
        venue.setStatus(1);
        venueMapper.insert(venue);
        venueId = venue.getId();

        Hall hall = new Hall();
        hall.setVenueId(venueId);
        hall.setName("Step102Http主厅");
        hallMapper.insert(hall);
        hallId = hall.getId();

        insertSeat(1, 1, "H01", "NORMAL");
        insertSeat(1, 2, "H02", "VIP");

        LocalDateTime startTime = LocalDate.now().plusDays(3).atTime(19, 0);
        Showtime showtime = new Showtime();
        showtime.setBizType("ACTIVITY");
        showtime.setBizId(activityId);
        showtime.setVenueId(venueId);
        showtime.setHallId(hallId);
        showtime.setStartTime(startTime);
        showtime.setEndTime(startTime.plusHours(2));
        showtime.setBasePrice(new BigDecimal("120.00"));
        showtime.setTotalSeats(2);
        showtime.setAvailableSeats(2);
        showtime.setStatus(1);
        showtimeMapper.insert(showtime);
        showtimeId = showtime.getId();

        for (Long seatId : seatIds) {
            ShowtimeSeat state = new ShowtimeSeat();
            state.setShowtimeId(showtimeId);
            state.setSeatId(seatId);
            state.setStatus(0);
            state.setPrice(new BigDecimal("120.00"));
            state.setVersion(0);
            showtimeSeatMapper.insert(state);
        }
    }

    @AfterEach
    void cleanup() {
        if (showtimeId != null) {
            showtimeSeatMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ShowtimeSeat>()
                    .eq(ShowtimeSeat::getShowtimeId, showtimeId));
            showtimeMapper.deleteById(showtimeId);
        }
        for (Long seatId : seatIds) {
            seatMapper.deleteById(seatId);
        }
        if (hallId != null) {
            hallMapper.deleteById(hallId);
        }
        if (venueId != null) {
            venueMapper.deleteById(venueId);
        }
        if (activityId != null) {
            activityMapper.deleteById(activityId);
        }
    }

    @Test
    void completeReadOnlyHttpFlowShouldLeaveMysqlAndRedisUnchanged() throws Exception {
        Activity activityBefore = activityMapper.selectById(activityId);
        Showtime showtimeBefore = showtimeMapper.selectById(showtimeId);
        Set<String> locksBefore = redisKeys(RedisConstants.SEAT_LOCK_KEY_PREFIX + "*");

        ResponseEntity<String> listResponse = restTemplate.getForEntity(
                "/api/activities?keyword=Step102Http&page=1&pageSize=20", String.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode list = objectMapper.readTree(listResponse.getBody());
        assertThat(list.path("data").path("list")).anySatisfy(item ->
                assertThat(item.path("id").asLong()).isEqualTo(activityId));

        ResponseEntity<String> detailResponse = restTemplate.getForEntity(
                "/api/activities/" + activityId, String.class);
        assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode detail = objectMapper.readTree(detailResponse.getBody());
        assertThat(detail.path("data").path("title").asText())
                .isEqualTo("Step102Http固定座位活动");

        String date = LocalDate.now().plusDays(3).toString();
        ResponseEntity<String> showtimeResponse = restTemplate.getForEntity(
                "/api/activities/" + activityId + "/showtimes?date=" + date
                        + "&venueId=" + venueId,
                String.class);
        assertThat(showtimeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode showtimes = objectMapper.readTree(showtimeResponse.getBody());
        assertThat(showtimes.path("data").get(0).path("showtimeId").asLong())
                .isEqualTo(showtimeId);
        assertThat(showtimes.path("data").get(0).path("venueId").asLong())
                .isEqualTo(venueId);
        assertThat(showtimes.path("data").get(0).path("availableSeats").asInt())
                .isEqualTo(2);

        assertThat(activityMapper.selectById(activityId)).isEqualTo(activityBefore);
        assertThat(showtimeMapper.selectById(showtimeId)).isEqualTo(showtimeBefore);
        assertThat(redisKeys(RedisConstants.SEAT_LOCK_KEY_PREFIX + "*")).isEqualTo(locksBefore);
    }

    private void insertSeat(int row, int column, String number, String type) {
        Seat seat = new Seat();
        seat.setHallId(hallId);
        seat.setRowNo(row);
        seat.setColNo(column);
        seat.setSeatNo(number);
        seat.setSeatType(type);
        seat.setBaseStatus(1);
        seatMapper.insert(seat);
        seatIds.add(seat.getId());
    }

    private Set<String> redisKeys(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        return keys == null ? Collections.emptySet() : new LinkedHashSet<>(keys);
    }
}
