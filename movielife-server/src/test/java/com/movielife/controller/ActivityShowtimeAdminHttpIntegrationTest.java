package com.movielife.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.constants.RedisConstants;
import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Showtime;
import com.movielife.entity.User;
import com.movielife.entity.Venue;
import com.movielife.entity.VenueStaff;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.UserMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.mapper.VenueStaffMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "movielife.startup-check.enabled=false"
)
class ActivityShowtimeAdminHttpIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private UserMapper userMapper;
    @Autowired private VenueMapper venueMapper;
    @Autowired private VenueStaffMapper venueStaffMapper;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private HallMapper hallMapper;
    @Autowired private ShowtimeMapper showtimeMapper;

    private final Set<Long> showtimeIds = new LinkedHashSet<>();
    private final Set<Long> hallIds = new LinkedHashSet<>();
    private final Set<Long> activityIds = new LinkedHashSet<>();
    private final Set<Long> venueStaffIds = new LinkedHashSet<>();
    private final Set<Long> venueIds = new LinkedHashSet<>();
    private final Set<Long> userIds = new LinkedHashSet<>();
    private final Set<String> loginTokens = new LinkedHashSet<>();

    private Long venueId;
    private Long otherVenueId;
    private Long onSaleShowtimeId;
    private Long stoppedShowtimeId;
    private Long finishedShowtimeId;
    private Long movieShowtimeId;
    private Long otherVenueShowtimeId;
    private String staffToken;
    private String ordinaryToken;
    private String otherVenueStaffToken;
    private String inactiveStaffToken;

    @BeforeEach
    void setUp() {
        User staff = insertUser("场次管理员");
        User ordinary = insertUser("普通登录用户");
        User otherStaff = insertUser("其他场馆管理员");
        User inactiveStaff = insertUser("停用场馆员工");
        staffToken = login(staff.getId(), "staff");
        ordinaryToken = login(ordinary.getId(), "ordinary");
        otherVenueStaffToken = login(otherStaff.getId(), "other-staff");
        inactiveStaffToken = login(inactiveStaff.getId(), "inactive-staff");

        Venue venue = insertVenue("Step151主场馆");
        Venue otherVenue = insertVenue("Step151其他场馆");
        venueId = venue.getId();
        otherVenueId = otherVenue.getId();
        insertStaff(staff.getId(), venueId, 1);
        insertStaff(otherStaff.getId(), otherVenueId, 1);
        insertStaff(inactiveStaff.getId(), venueId, 0);

        Activity activity = insertActivity("Step151固定座位活动");
        Hall hall = insertHall(venueId, "Step151主厅");
        Hall otherHall = insertHall(otherVenueId, "Step151其他厅");

        stoppedShowtimeId = insertShowtime("ACTIVITY", activity.getId(), venueId, hall.getId(),
                LocalDateTime.of(2020, 6, 1, 19, 0), 0, 100, 80).getId();
        finishedShowtimeId = insertShowtime("ACTIVITY", activity.getId(), venueId, hall.getId(),
                LocalDateTime.of(2035, 5, 1, 19, 0), 2, 100, 0).getId();
        onSaleShowtimeId = insertShowtime("ACTIVITY", activity.getId(), venueId, hall.getId(),
                LocalDateTime.of(2035, 5, 2, 19, 0), 1, 100, 60).getId();
        movieShowtimeId = insertShowtime("MOVIE", 1L, venueId, hall.getId(),
                LocalDateTime.of(2035, 5, 3, 19, 0), 1, 100, 100).getId();
        otherVenueShowtimeId = insertShowtime("ACTIVITY", activity.getId(), otherVenueId,
                otherHall.getId(), LocalDateTime.of(2035, 5, 4, 19, 0), 1, 80, 80).getId();
    }

    @AfterEach
    void cleanUp() {
        if (!showtimeIds.isEmpty()) {
            showtimeMapper.deleteBatchIds(showtimeIds);
        }
        if (!hallIds.isEmpty()) {
            hallMapper.deleteBatchIds(hallIds);
        }
        if (!venueStaffIds.isEmpty()) {
            venueStaffMapper.deleteBatchIds(venueStaffIds);
        }
        if (!activityIds.isEmpty()) {
            activityMapper.deleteBatchIds(activityIds);
        }
        if (!venueIds.isEmpty()) {
            venueMapper.deleteBatchIds(venueIds);
        }
        if (!userIds.isEmpty()) {
            userMapper.deleteBatchIds(userIds);
        }
        loginTokens.forEach(token -> redisTemplate.delete(RedisConstants.LOGIN_TOKEN_KEY + token));
    }

    @Test
    void authorizedStaffShouldSeeAllOwnActivityStatusesWithStableFilteringAndPagination()
            throws Exception {
        JsonNode all = getJson("/api/admin/venues/" + venueId
                + "/activity-showtimes?page=1&pageSize=20", staffToken, HttpStatus.OK);
        assertThat(all.path("data").path("total").asLong()).isEqualTo(3);
        assertThat(all.path("data").path("list").size()).isEqualTo(3);
        assertThat(all.path("data").path("list").get(0).path("showtimeId").asLong())
                .isEqualTo(onSaleShowtimeId);
        assertThat(all.path("data").path("list").get(1).path("showtimeId").asLong())
                .isEqualTo(finishedShowtimeId);
        assertThat(all.path("data").path("list").get(2).path("showtimeId").asLong())
                .isEqualTo(stoppedShowtimeId);
        assertThat(all.path("data").path("list").get(0).path("activityTitle").asText())
                .isEqualTo("Step151固定座位活动");
        assertThat(all.path("data").path("list").get(0).path("hallName").asText())
                .isEqualTo("Step151主厅");

        JsonNode filtered = getJson("/api/admin/venues/" + venueId
                        + "/activity-showtimes?showtimeStatus=0"
                        + "&startDate=2020-01-01&endDate=2020-12-31",
                staffToken, HttpStatus.OK);
        assertThat(filtered.path("data").path("total").asLong()).isEqualTo(1);
        assertThat(filtered.path("data").path("list").get(0).path("showtimeId").asLong())
                .isEqualTo(stoppedShowtimeId);

        JsonNode secondPage = getJson("/api/admin/venues/" + venueId
                + "/activity-showtimes?page=2&pageSize=1", staffToken, HttpStatus.OK);
        assertThat(secondPage.path("data").path("total").asLong()).isEqualTo(3);
        assertThat(secondPage.path("data").path("list").get(0).path("showtimeId").asLong())
                .isEqualTo(finishedShowtimeId);
    }

    @Test
    void detailShouldReturnStoppedShowtimeButHideMovieAndOtherVenue() throws Exception {
        JsonNode detail = getJson("/api/admin/venues/" + venueId
                        + "/activity-showtimes/" + stoppedShowtimeId,
                staffToken, HttpStatus.OK);
        assertThat(detail.path("data").path("status").asInt()).isZero();
        assertThat(detail.path("data").path("totalSeats").asInt()).isEqualTo(100);
        assertThat(detail.path("data").path("availableSeats").asInt()).isEqualTo(80);

        assertError("/api/admin/venues/" + venueId + "/activity-showtimes/"
                + movieShowtimeId, staffToken, HttpStatus.NOT_FOUND, "SHOWTIME_NOT_FOUND");
        assertError("/api/admin/venues/" + venueId + "/activity-showtimes/"
                + otherVenueShowtimeId, staffToken, HttpStatus.NOT_FOUND, "SHOWTIME_NOT_FOUND");
    }

    @Test
    void loginAloneAndOtherVenueStaffShouldNotGrantManagementPermission() throws Exception {
        assertError("/api/admin/venues/" + venueId + "/activity-showtimes",
                null, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        assertError("/api/admin/venues/" + venueId + "/activity-showtimes",
                ordinaryToken, HttpStatus.FORBIDDEN, "VENUE_MANAGE_FORBIDDEN");
        assertError("/api/admin/venues/" + venueId + "/activity-showtimes",
                otherVenueStaffToken, HttpStatus.FORBIDDEN, "VENUE_MANAGE_FORBIDDEN");
        assertError("/api/admin/venues/" + venueId + "/activity-showtimes",
                inactiveStaffToken, HttpStatus.FORBIDDEN, "VENUE_MANAGE_FORBIDDEN");
    }

    @Test
    void queryShouldNotChangeMysqlRowsOrRedisKeySet() throws Exception {
        long showtimeCount = showtimeMapper.selectCount(new LambdaQueryWrapper<Showtime>()
                .in(Showtime::getId, showtimeIds));
        long staffCount = venueStaffMapper.selectCount(new LambdaQueryWrapper<VenueStaff>()
                .in(VenueStaff::getId, venueStaffIds));
        Set<String> redisKeys = redisKeys();

        getJson("/api/admin/venues/" + venueId + "/activity-showtimes",
                staffToken, HttpStatus.OK);
        getJson("/api/admin/venues/" + venueId + "/activity-showtimes/"
                + onSaleShowtimeId, staffToken, HttpStatus.OK);

        assertThat(showtimeMapper.selectCount(new LambdaQueryWrapper<Showtime>()
                .in(Showtime::getId, showtimeIds))).isEqualTo(showtimeCount);
        assertThat(venueStaffMapper.selectCount(new LambdaQueryWrapper<VenueStaff>()
                .in(VenueStaff::getId, venueStaffIds))).isEqualTo(staffCount);
        assertThat(redisKeys()).isEqualTo(redisKeys);
    }

    private JsonNode getJson(String path, String token, HttpStatus expectedStatus) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<String> response = restTemplate.exchange(
                path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        return objectMapper.readTree(response.getBody());
    }

    private void assertError(String path, String token, HttpStatus status, String code)
            throws Exception {
        JsonNode body = getJson(path, token, status);
        assertThat(body.path("code").asText()).isEqualTo(code);
    }

    private Set<String> redisKeys() {
        Set<String> keys = redisTemplate.keys("*");
        return keys == null ? new LinkedHashSet<>() : new LinkedHashSet<>(keys);
    }

    private User insertUser(String nickname) {
        User user = new User();
        user.setPhone("19" + UUID.randomUUID().toString().replace("-", "").substring(0, 9));
        user.setNickName(nickname);
        assertThat(userMapper.insert(user)).isEqualTo(1);
        userIds.add(user.getId());
        return user;
    }

    private String login(Long userId, String prefix) {
        String token = "step151-" + prefix + "-" + UUID.randomUUID();
        redisTemplate.opsForValue().set(RedisConstants.LOGIN_TOKEN_KEY + token,
                String.valueOf(userId));
        loginTokens.add(token);
        return token;
    }

    private Venue insertVenue(String name) {
        Venue venue = new Venue();
        venue.setName(name);
        venue.setType("theater");
        venue.setCity("Step151市");
        venue.setAddress(name + "地址");
        venue.setAvgScore(BigDecimal.ZERO);
        venue.setStatus(1);
        assertThat(venueMapper.insert(venue)).isEqualTo(1);
        venueIds.add(venue.getId());
        return venue;
    }

    private void insertStaff(Long userId, Long targetVenueId, int status) {
        VenueStaff staff = new VenueStaff();
        staff.setUserId(userId);
        staff.setVenueId(targetVenueId);
        staff.setStatus(status);
        assertThat(venueStaffMapper.insert(staff)).isEqualTo(1);
        venueStaffIds.add(staff.getId());
    }

    private Activity insertActivity(String title) {
        Activity activity = new Activity();
        activity.setTitle(title);
        activity.setType("drama");
        activity.setCity("Step151市");
        activity.setDescription("Step 15.1 管理查询测试");
        activity.setPriceMin(new BigDecimal("100.00"));
        activity.setPriceMax(new BigDecimal("200.00"));
        activity.setStatus(1);
        assertThat(activityMapper.insert(activity)).isEqualTo(1);
        activityIds.add(activity.getId());
        return activity;
    }

    private Hall insertHall(Long targetVenueId, String name) {
        Hall hall = new Hall();
        hall.setVenueId(targetVenueId);
        hall.setName(name);
        assertThat(hallMapper.insert(hall)).isEqualTo(1);
        hallIds.add(hall.getId());
        return hall;
    }

    private Showtime insertShowtime(String bizType, Long bizId, Long targetVenueId,
                                    Long hallId, LocalDateTime startTime, int status,
                                    int totalSeats, int availableSeats) {
        Showtime showtime = new Showtime();
        showtime.setBizType(bizType);
        showtime.setBizId(bizId);
        showtime.setVenueId(targetVenueId);
        showtime.setHallId(hallId);
        showtime.setStartTime(startTime);
        showtime.setEndTime(startTime.plusHours(2));
        showtime.setLanguageVersion("现场");
        showtime.setBasePrice(new BigDecimal("180.00"));
        showtime.setTotalSeats(totalSeats);
        showtime.setAvailableSeats(availableSeats);
        showtime.setStatus(status);
        assertThat(showtimeMapper.insert(showtime)).isEqualTo(1);
        showtimeIds.add(showtime.getId());
        return showtime;
    }
}
