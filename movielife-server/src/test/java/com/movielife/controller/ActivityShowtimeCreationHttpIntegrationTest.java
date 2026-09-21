package com.movielife.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.constants.RedisConstants;
import com.movielife.dto.CreateActivityShowtimeDTO;
import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Seat;
import com.movielife.entity.Showtime;
import com.movielife.entity.ShowtimeSeat;
import com.movielife.entity.User;
import com.movielife.entity.Venue;
import com.movielife.entity.VenueStaff;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.UserMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.mapper.VenueStaffMapper;
import com.movielife.service.ActivityShowtimeAdminService;
import com.movielife.vo.AdminActivityShowtimeVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "movielife.startup-check.enabled=false"
)
class ActivityShowtimeCreationHttpIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private ActivityShowtimeAdminService adminService;
    @Autowired private UserMapper userMapper;
    @Autowired private VenueMapper venueMapper;
    @Autowired private VenueStaffMapper venueStaffMapper;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private HallMapper hallMapper;
    @Autowired private SeatMapper seatMapper;
    @Autowired private ShowtimeMapper showtimeMapper;
    @SpyBean private ShowtimeSeatMapper showtimeSeatMapper;

    private final Set<Long> showtimeIds = new LinkedHashSet<>();
    private final Set<Long> seatIds = new LinkedHashSet<>();
    private final Set<Long> hallIds = new LinkedHashSet<>();
    private final Set<Long> activityIds = new LinkedHashSet<>();
    private final Set<Long> venueStaffIds = new LinkedHashSet<>();
    private final Set<Long> venueIds = new LinkedHashSet<>();
    private final Set<Long> userIds = new LinkedHashSet<>();
    private final Set<String> loginTokens = new LinkedHashSet<>();

    private User staff;
    private Venue venue;
    private Venue otherVenue;
    private Activity activity;
    private Hall hall;
    private Hall emptyHall;
    private Hall otherHall;
    private String staffToken;
    private String ordinaryToken;

    @BeforeEach
    void setUp() {
        staff = insertUser("Step152场次管理员");
        User ordinary = insertUser("Step152普通用户");
        venue = insertVenue("Step152主场馆", 1);
        otherVenue = insertVenue("Step152其他场馆", 1);
        insertStaff(staff.getId(), venue.getId(), 1);
        staffToken = login(staff.getId());
        ordinaryToken = login(ordinary.getId());
        activity = insertActivity("Step152固定座位活动", 1);
        hall = insertHall(venue.getId(), "Step152主厅");
        emptyHall = insertHall(venue.getId(), "Step152空厅");
        otherHall = insertHall(otherVenue.getId(), "Step152其他厅");
        insertSeat(hall.getId(), "A01", 1, 1, 1);
        insertSeat(hall.getId(), "A02", 1, 2, 1);
        insertSeat(hall.getId(), "A03", 1, 3, 0);
    }

    @AfterEach
    void cleanUp() {
        reset(showtimeSeatMapper);
        if (!showtimeIds.isEmpty()) {
            showtimeSeatMapper.delete(new LambdaQueryWrapper<ShowtimeSeat>()
                    .in(ShowtimeSeat::getShowtimeId, showtimeIds));
            showtimeMapper.deleteBatchIds(showtimeIds);
        }
        if (!seatIds.isEmpty()) {
            seatMapper.deleteBatchIds(seatIds);
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
    void httpCreateShouldInitializeConsistentInventoryWithoutNewRedisState() throws Exception {
        Set<String> redisKeys = redisKeys();
        CreateActivityShowtimeDTO request = request("http-create", hall.getId(), future(10));

        JsonNode body = post(request, staffToken, HttpStatus.OK);
        Long showtimeId = body.path("data").path("showtimeId").asLong();
        showtimeIds.add(showtimeId);

        assertThat(body.path("data").path("status").asInt()).isEqualTo(1);
        assertThat(body.path("data").path("totalSeats").asInt()).isEqualTo(2);
        assertThat(body.path("data").path("availableSeats").asInt()).isEqualTo(2);
        Showtime persisted = showtimeMapper.selectById(showtimeId);
        assertThat(persisted.getBizType()).isEqualTo("ACTIVITY");
        assertThat(persisted.getCreateRequestId()).isEqualTo("http-create");
        assertThat(persisted.getTotalSeats()).isEqualTo(2);
        assertThat(persisted.getAvailableSeats()).isEqualTo(2);
        List<ShowtimeSeat> states = showtimeSeatMapper.selectList(
                new LambdaQueryWrapper<ShowtimeSeat>()
                        .eq(ShowtimeSeat::getShowtimeId, showtimeId));
        assertThat(states).hasSize(2);
        assertThat(states).extracting(ShowtimeSeat::getStatus).containsOnly(0);
        assertThat(states).extracting(ShowtimeSeat::getPrice)
                .containsOnly(new BigDecimal("180.00"));
        assertThat(redisKeys()).isEqualTo(redisKeys);
    }

    @Test
    void requestIdShouldBeIdempotentAndMismatchShouldFail() throws Exception {
        CreateActivityShowtimeDTO request = request("idempotent", hall.getId(), future(20));
        JsonNode first = post(request, staffToken, HttpStatus.OK);
        Long showtimeId = first.path("data").path("showtimeId").asLong();
        showtimeIds.add(showtimeId);

        JsonNode repeated = post(request, staffToken, HttpStatus.OK);
        assertThat(repeated.path("data").path("showtimeId").asLong()).isEqualTo(showtimeId);
        assertThat(showtimeMapper.selectCount(new LambdaQueryWrapper<Showtime>()
                .eq(Showtime::getVenueId, venue.getId())
                .eq(Showtime::getCreateRequestId, "idempotent"))).isEqualTo(1);

        request.setBasePrice(new BigDecimal("181.00"));
        JsonNode mismatch = post(request, staffToken, HttpStatus.CONFLICT);
        assertThat(mismatch.path("code").asText())
                .isEqualTo("SHOWTIME_CREATE_REQUEST_MISMATCH");
    }

    @Test
    void stoppedShowtimeShouldStillBlockOverlappingSchedule() throws Exception {
        CreateActivityShowtimeDTO firstRequest = request("stopped-source", hall.getId(), future(30));
        Long firstId = post(firstRequest, staffToken, HttpStatus.OK)
                .path("data").path("showtimeId").asLong();
        showtimeIds.add(firstId);
        Showtime stopped = showtimeMapper.selectById(firstId);
        stopped.setStatus(0);
        assertThat(showtimeMapper.updateById(stopped)).isEqualTo(1);

        CreateActivityShowtimeDTO overlap = request("stopped-overlap", hall.getId(),
                firstRequest.getStartTime().plusMinutes(30));
        JsonNode conflict = post(overlap, staffToken, HttpStatus.CONFLICT);
        assertThat(conflict.path("code").asText()).isEqualTo("SHOWTIME_SCHEDULE_CONFLICT");
    }

    @Test
    void shouldEnforceVenueActivityHallSeatAndPermissionBoundaries() throws Exception {
        CreateActivityShowtimeDTO request = request("boundary-empty", emptyHall.getId(), future(40));
        assertThat(post(request, staffToken, HttpStatus.CONFLICT).path("code").asText())
                .isEqualTo("SHOWTIME_SEAT_REQUIRED");

        request = request("boundary-hall", otherHall.getId(), future(41));
        assertThat(post(request, staffToken, HttpStatus.NOT_FOUND).path("code").asText())
                .isEqualTo("HALL_NOT_FOUND");

        request = request("boundary-login", hall.getId(), future(42));
        assertThat(post(request, ordinaryToken, HttpStatus.FORBIDDEN).path("code").asText())
                .isEqualTo("VENUE_MANAGE_FORBIDDEN");

        activity.setStatus(2);
        assertThat(activityMapper.updateById(activity)).isEqualTo(1);
        request = request("boundary-activity", hall.getId(), future(43));
        assertThat(post(request, staffToken, HttpStatus.NOT_FOUND).path("code").asText())
                .isEqualTo("ACTIVITY_NOT_FOUND");
        activity.setStatus(1);
        assertThat(activityMapper.updateById(activity)).isEqualTo(1);

        venue.setStatus(0);
        assertThat(venueMapper.updateById(venue)).isEqualTo(1);
        request = request("boundary-venue", hall.getId(), future(44));
        assertThat(post(request, staffToken, HttpStatus.NOT_FOUND).path("code").asText())
                .isEqualTo("VENUE_NOT_FOUND");
    }

    @Test
    void failedSeatBatchShouldRollBackShowtime() {
        CreateActivityShowtimeDTO request = request("rollback-batch", hall.getId(), future(50));
        doReturn(1).when(showtimeSeatMapper).insertBatch(anyList());

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> adminService.createShowtime(staff.getId(), venue.getId(), request)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INTERNAL_ERROR));
        reset(showtimeSeatMapper);
        assertThat(showtimeMapper.selectByVenueAndCreateRequestId(
                venue.getId(), "rollback-batch")).isNull();
    }

    @Test
    void concurrentOverlappingRequestsShouldSerializeOnHall() throws Exception {
        CreateActivityShowtimeDTO first = request("concurrent-a", hall.getId(), future(60));
        CreateActivityShowtimeDTO second = request("concurrent-b", hall.getId(),
                first.getStartTime().plusMinutes(10));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        futures.add(executor.submit(() -> invokeAfter(start, first)));
        futures.add(executor.submit(() -> invokeAfter(start, second)));
        start.countDown();

        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get());
        }
        executor.shutdownNow();
        List<AdminActivityShowtimeVO> successes = new ArrayList<>();
        List<BusinessException> failures = new ArrayList<>();
        for (Object result : results) {
            if (result instanceof AdminActivityShowtimeVO) {
                successes.add((AdminActivityShowtimeVO) result);
            } else {
                failures.add((BusinessException) result);
            }
        }
        assertThat(successes).hasSize(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).getErrorCode()).isEqualTo(ErrorCode.SHOWTIME_SCHEDULE_CONFLICT);
        showtimeIds.add(successes.get(0).getShowtimeId());
    }

    private Object invokeAfter(CountDownLatch start, CreateActivityShowtimeDTO request)
            throws InterruptedException {
        start.await();
        try {
            return adminService.createShowtime(staff.getId(), venue.getId(), request);
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private JsonNode post(CreateActivityShowtimeDTO request, String token,
                          HttpStatus expectedStatus) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/venues/" + venue.getId() + "/activity-showtimes",
                HttpMethod.POST, new HttpEntity<>(request, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        return objectMapper.readTree(response.getBody());
    }

    private CreateActivityShowtimeDTO request(String requestId, Long hallId,
                                               LocalDateTime startTime) {
        CreateActivityShowtimeDTO request = new CreateActivityShowtimeDTO();
        request.setRequestId(requestId);
        request.setActivityId(activity.getId());
        request.setHallId(hallId);
        request.setStartTime(startTime);
        request.setEndTime(startTime.plusHours(2));
        request.setBasePrice(new BigDecimal("180.00"));
        return request;
    }

    private LocalDateTime future(int days) {
        return LocalDateTime.now().plusDays(days).withNano(0);
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

    private Venue insertVenue(String name, int status) {
        Venue value = new Venue();
        value.setName(name);
        value.setType("theater");
        value.setCity("Step152市");
        value.setAddress(name + "地址");
        value.setAvgScore(BigDecimal.ZERO);
        value.setStatus(status);
        assertThat(venueMapper.insert(value)).isEqualTo(1);
        venueIds.add(value.getId());
        return value;
    }

    private void insertStaff(Long userId, Long venueId, int status) {
        VenueStaff value = new VenueStaff();
        value.setUserId(userId);
        value.setVenueId(venueId);
        value.setStatus(status);
        assertThat(venueStaffMapper.insert(value)).isEqualTo(1);
        venueStaffIds.add(value.getId());
    }

    private Activity insertActivity(String title, int status) {
        Activity value = new Activity();
        value.setTitle(title);
        value.setType("drama");
        value.setCity("Step152市");
        value.setDescription("Step 15.2 创建测试");
        value.setPriceMin(new BigDecimal("100.00"));
        value.setPriceMax(new BigDecimal("200.00"));
        value.setStatus(status);
        assertThat(activityMapper.insert(value)).isEqualTo(1);
        activityIds.add(value.getId());
        return value;
    }

    private Hall insertHall(Long venueId, String name) {
        Hall value = new Hall();
        value.setVenueId(venueId);
        value.setName(name);
        assertThat(hallMapper.insert(value)).isEqualTo(1);
        hallIds.add(value.getId());
        return value;
    }

    private void insertSeat(Long hallId, String seatNo, int row, int col, int status) {
        Seat value = new Seat();
        value.setHallId(hallId);
        value.setSeatNo(seatNo);
        value.setRowNo(row);
        value.setColNo(col);
        value.setSeatType("NORMAL");
        value.setBaseStatus(status);
        assertThat(seatMapper.insert(value)).isEqualTo(1);
        seatIds.add(value.getId());
    }

    private String login(Long userId) {
        String token = "step152-" + UUID.randomUUID();
        redisTemplate.opsForValue().set(RedisConstants.LOGIN_TOKEN_KEY + token,
                String.valueOf(userId));
        loginTokens.add(token);
        return token;
    }
}
