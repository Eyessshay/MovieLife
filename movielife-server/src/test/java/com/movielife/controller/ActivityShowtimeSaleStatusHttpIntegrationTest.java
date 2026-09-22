package com.movielife.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.constants.RedisConstants;
import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Seat;
import com.movielife.entity.Showtime;
import com.movielife.entity.ShowtimeSeat;
import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.entity.User;
import com.movielife.entity.Venue;
import com.movielife.entity.VenueStaff;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.TicketOrderItemMapper;
import com.movielife.mapper.TicketOrderMapper;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "movielife.startup-check.enabled=false"
)
class ActivityShowtimeSaleStatusHttpIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private UserMapper userMapper;
    @Autowired private VenueMapper venueMapper;
    @Autowired private VenueStaffMapper venueStaffMapper;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private HallMapper hallMapper;
    @Autowired private SeatMapper seatMapper;
    @Autowired private ShowtimeMapper showtimeMapper;
    @Autowired private ShowtimeSeatMapper showtimeSeatMapper;
    @Autowired private TicketOrderMapper ticketOrderMapper;
    @Autowired private TicketOrderItemMapper ticketOrderItemMapper;

    private final Set<Long> showtimeIds = new LinkedHashSet<>();
    private final Set<Long> seatIds = new LinkedHashSet<>();
    private final Set<Long> hallIds = new LinkedHashSet<>();
    private final Set<Long> activityIds = new LinkedHashSet<>();
    private final Set<Long> venueStaffIds = new LinkedHashSet<>();
    private final Set<Long> venueIds = new LinkedHashSet<>();
    private final Set<Long> userIds = new LinkedHashSet<>();
    private final Set<String> loginTokens = new LinkedHashSet<>();
    private final Set<String> lockTokens = new LinkedHashSet<>();

    private User staff;
    private User buyer;
    private Venue venue;
    private Venue otherVenue;
    private Activity activity;
    private Hall hall;
    private List<Seat> seats;
    private Showtime showtime;
    private String staffToken;
    private String buyerToken;
    private String ordinaryToken;

    @BeforeEach
    void setUp() {
        staff = insertUser("Step153场次管理员");
        buyer = insertUser("Step153购票用户");
        User ordinary = insertUser("Step153普通用户");
        venue = insertVenue("Step153主场馆", 1);
        otherVenue = insertVenue("Step153其他场馆", 1);
        insertStaff(staff.getId(), venue.getId(), 1);
        insertStaff(staff.getId(), otherVenue.getId(), 1);
        activity = insertActivity("Step153固定座位活动", 1);
        hall = insertHall(venue.getId(), "Step153主厅");
        seats = Arrays.asList(insertSeat(hall.getId(), "A01", 1),
                insertSeat(hall.getId(), "A02", 2));
        showtime = insertShowtime(LocalDateTime.now().plusDays(1).withNano(0), 1);
        staffToken = login(staff.getId());
        buyerToken = login(buyer.getId());
        ordinaryToken = login(ordinary.getId());
    }

    @AfterEach
    void cleanUp() {
        for (Long showtimeId : showtimeIds) {
            List<TicketOrder> orders = ticketOrderMapper.selectList(
                    new LambdaQueryWrapper<TicketOrder>()
                            .eq(TicketOrder::getShowtimeId, showtimeId));
            for (TicketOrder order : orders) {
                ticketOrderItemMapper.delete(new LambdaQueryWrapper<TicketOrderItem>()
                        .eq(TicketOrderItem::getOrderId, order.getId()));
            }
            ticketOrderMapper.delete(new LambdaQueryWrapper<TicketOrder>()
                    .eq(TicketOrder::getShowtimeId, showtimeId));
            showtimeSeatMapper.delete(new LambdaQueryWrapper<ShowtimeSeat>()
                    .eq(ShowtimeSeat::getShowtimeId, showtimeId));
        }
        if (!showtimeIds.isEmpty()) {
            showtimeMapper.deleteBatchIds(showtimeIds);
        }
        lockTokens.forEach(token -> redisTemplate.delete(
                RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + token));
        for (Long showtimeId : showtimeIds) {
            for (Long seatId : seatIds) {
                redisTemplate.delete(RedisConstants.SEAT_LOCK_KEY_PREFIX
                        + showtimeId + ":" + seatId);
            }
        }
        loginTokens.forEach(token -> redisTemplate.delete(RedisConstants.LOGIN_TOKEN_KEY + token));
        if (!seatIds.isEmpty()) seatMapper.deleteBatchIds(seatIds);
        if (!hallIds.isEmpty()) hallMapper.deleteBatchIds(hallIds);
        if (!venueStaffIds.isEmpty()) venueStaffMapper.deleteBatchIds(venueStaffIds);
        if (!activityIds.isEmpty()) activityMapper.deleteBatchIds(activityIds);
        if (!venueIds.isEmpty()) venueMapper.deleteBatchIds(venueIds);
        if (!userIds.isEmpty()) userMapper.deleteBatchIds(userIds);
    }

    @Test
    void stopAndResumeShouldBeIdempotentAndOnlyChangeSaleStatus() throws Exception {
        Set<String> keysBefore = redisKeys();
        List<ShowtimeSeat> seatsBefore = states(showtime.getId());

        assertStatus(postStatus(showtime.getId(), "stop-sale", staffToken),
                HttpStatus.OK, "OK");
        assertStatus(postStatus(showtime.getId(), "stop-sale", staffToken),
                HttpStatus.OK, "OK");
        assertThat(showtimeMapper.selectById(showtime.getId()).getStatus()).isZero();
        assertInventoryUnchanged(seatsBefore);

        assertStatus(postStatus(showtime.getId(), "resume-sale", staffToken),
                HttpStatus.OK, "OK");
        assertStatus(postStatus(showtime.getId(), "resume-sale", staffToken),
                HttpStatus.OK, "OK");
        assertThat(showtimeMapper.selectById(showtime.getId()).getStatus()).isEqualTo(1);
        assertInventoryUnchanged(seatsBefore);
        assertThat(redisKeys()).isEqualTo(keysBefore);
    }

    @Test
    void shouldEnforceStaffVenueTimeAndManualStatusBoundaries() throws Exception {
        assertStatus(postStatus(showtime.getId(), "stop-sale", ordinaryToken),
                HttpStatus.FORBIDDEN, "VENUE_MANAGE_FORBIDDEN");
        assertStatus(postStatus(otherVenue.getId(), showtime.getId(), "stop-sale", staffToken),
                HttpStatus.NOT_FOUND, "SHOWTIME_NOT_FOUND");

        showtime.setStartTime(LocalDateTime.now().minusMinutes(1));
        showtime.setEndTime(LocalDateTime.now().plusHours(1));
        assertThat(showtimeMapper.updateById(showtime)).isEqualTo(1);
        assertStatus(postStatus(showtime.getId(), "stop-sale", staffToken),
                HttpStatus.CONFLICT, "SHOWTIME_SALE_STATUS_CONFLICT");

        showtime.setStartTime(LocalDateTime.now().plusDays(1));
        showtime.setEndTime(showtime.getStartTime().plusHours(2));
        showtime.setStatus(2);
        assertThat(showtimeMapper.updateById(showtime)).isEqualTo(1);
        assertStatus(postStatus(showtime.getId(), "stop-sale", staffToken),
                HttpStatus.CONFLICT, "SHOWTIME_SALE_STATUS_CONFLICT");
    }

    @Test
    void resumeShouldRevalidateDependenciesAndInventoryFacts() throws Exception {
        stop();
        activity.setStatus(0);
        assertThat(activityMapper.updateById(activity)).isEqualTo(1);
        assertStatus(postStatus(showtime.getId(), "resume-sale", staffToken),
                HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND");
        activity.setStatus(1);
        assertThat(activityMapper.updateById(activity)).isEqualTo(1);

        venue.setStatus(0);
        assertThat(venueMapper.updateById(venue)).isEqualTo(1);
        assertStatus(postStatus(showtime.getId(), "resume-sale", staffToken),
                HttpStatus.NOT_FOUND, "VENUE_NOT_FOUND");
        venue.setStatus(1);
        assertThat(venueMapper.updateById(venue)).isEqualTo(1);

        hall.setVenueId(otherVenue.getId());
        assertThat(hallMapper.updateById(hall)).isEqualTo(1);
        assertStatus(postStatus(showtime.getId(), "resume-sale", staffToken),
                HttpStatus.NOT_FOUND, "HALL_NOT_FOUND");
        hall.setVenueId(venue.getId());
        assertThat(hallMapper.updateById(hall)).isEqualTo(1);

        ShowtimeSeat removed = states(showtime.getId()).get(0);
        assertThat(showtimeSeatMapper.deleteById(removed.getId())).isEqualTo(1);
        assertStatus(postStatus(showtime.getId(), "resume-sale", staffToken),
                HttpStatus.CONFLICT, "SHOWTIME_INVENTORY_INCONSISTENT");
    }

    @Test
    void resumeShouldRejectAvailableSeatMismatch() throws Exception {
        stop();
        showtime = showtimeMapper.selectById(showtime.getId());
        showtime.setAvailableSeats(1);
        assertThat(showtimeMapper.updateById(showtime)).isEqualTo(1);

        assertStatus(postStatus(showtime.getId(), "resume-sale", staffToken),
                HttpStatus.CONFLICT, "SHOWTIME_INVENTORY_INCONSISTENT");
        assertThat(showtimeMapper.selectById(showtime.getId()).getStatus()).isZero();
    }

    @Test
    void preexistingLockTokenShouldNotCreateOrderAfterStopAndShouldRemainForExpiry()
            throws Exception {
        LockedRequest locked = lockSeats();
        stop();

        ResponseEntity<String> response = post("/api/orders", locked.orderBody, buyerToken);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(response).path("code").asText()).isEqualTo("SHOWTIME_NOT_FOUND");
        assertThat(ticketOrderMapper.selectCount(new LambdaQueryWrapper<TicketOrder>()
                .eq(TicketOrder::getShowtimeId, showtime.getId()))).isZero();
        assertThat(redisTemplate.opsForValue().get(tokenKey(locked.lockToken))).isNotBlank();
        for (Seat seat : seats) {
            assertThat(redisTemplate.opsForValue().get(seatKey(seat.getId())))
                    .isEqualTo(locked.lockToken);
        }
        assertThat(showtimeMapper.selectById(showtime.getId()).getAvailableSeats()).isEqualTo(2);
        assertThat(states(showtime.getId())).allSatisfy(state -> {
            assertThat(state.getStatus()).isZero();
            assertThat(state.getLockOrderId()).isNull();
        });
    }

    @Test
    void existingUnpaidOrderShouldStillCancelWhileStopped() throws Exception {
        LockedRequest locked = lockSeats();
        ResponseEntity<String> create = post("/api/orders", locked.orderBody, buyerToken);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        long orderId = json(create).path("data").path("orderId").asLong();
        assertThat(showtimeMapper.selectById(showtime.getId()).getAvailableSeats()).isZero();

        stop();
        ResponseEntity<String> cancel = post("/api/orders/" + orderId + "/cancel", null,
                buyerToken);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ticketOrderMapper.selectById(orderId).getStatus()).isEqualTo("CANCELED");
        Showtime stopped = showtimeMapper.selectById(showtime.getId());
        assertThat(stopped.getStatus()).isZero();
        assertThat(stopped.getAvailableSeats()).isEqualTo(2);
        assertThat(states(showtime.getId())).allSatisfy(state -> {
            assertThat(state.getStatus()).isZero();
            assertThat(state.getLockOrderId()).isNull();
        });
    }

    @Test
    void concurrentOrderAndStopShouldConvergeWithoutPartialOrder() throws Exception {
        LockedRequest locked = lockSeats();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<ResponseEntity<String>> orderFuture = executor.submit(() -> {
            start.await();
            return post("/api/orders", locked.orderBody, buyerToken);
        });
        Future<ResponseEntity<String>> stopFuture = executor.submit(() -> {
            start.await();
            return postStatus(showtime.getId(), "stop-sale", staffToken);
        });
        start.countDown();
        ResponseEntity<String> orderResponse = orderFuture.get();
        ResponseEntity<String> stopResponse = stopFuture.get();
        executor.shutdownNow();

        assertThat(stopResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(showtimeMapper.selectById(showtime.getId()).getStatus()).isZero();
        long orderCount = ticketOrderMapper.selectCount(new LambdaQueryWrapper<TicketOrder>()
                .eq(TicketOrder::getShowtimeId, showtime.getId()));
        if (orderResponse.getStatusCode() == HttpStatus.OK) {
            assertThat(orderCount).isEqualTo(1);
            assertThat(showtimeMapper.selectById(showtime.getId()).getAvailableSeats()).isZero();
            assertThat(states(showtime.getId())).allSatisfy(state -> {
                assertThat(state.getStatus()).isEqualTo(1);
                assertThat(state.getLockOrderId()).isNotNull();
            });
        } else {
            assertThat(orderResponse.getStatusCode().is4xxClientError()
                    || orderResponse.getStatusCode().is5xxServerError()).isTrue();
            assertThat(orderCount).isZero();
            assertThat(showtimeMapper.selectById(showtime.getId()).getAvailableSeats()).isEqualTo(2);
            assertThat(states(showtime.getId())).allSatisfy(state -> {
                assertThat(state.getStatus()).isZero();
                assertThat(state.getLockOrderId()).isNull();
            });
        }
    }

    private void stop() throws Exception {
        assertStatus(postStatus(showtime.getId(), "stop-sale", staffToken),
                HttpStatus.OK, "OK");
    }

    private LockedRequest lockSeats() throws Exception {
        String seatJson = seats.get(0).getId() + "," + seats.get(1).getId();
        ResponseEntity<String> response = post(
                "/api/showtimes/" + showtime.getId() + "/seats/lock",
                "{\"seatIds\":[" + seatJson + "]}", buyerToken);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String lockToken = json(response).path("data").path("lockToken").asText();
        lockTokens.add(lockToken);
        String body = "{\"showtimeId\":" + showtime.getId()
                + ",\"seatIds\":[" + seatJson + "],\"lockToken\":\""
                + lockToken + "\"}";
        return new LockedRequest(lockToken, body);
    }

    private ResponseEntity<String> postStatus(Long showtimeId, String action, String token) {
        return postStatus(venue.getId(), showtimeId, action, token);
    }

    private ResponseEntity<String> postStatus(Long venueId, Long showtimeId,
                                               String action, String token) {
        return post("/api/admin/venues/" + venueId + "/activity-showtimes/"
                + showtimeId + "/" + action, null, token);
    }

    private ResponseEntity<String> post(String path, String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private void assertStatus(ResponseEntity<String> response, HttpStatus status, String code)
            throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(json(response).path("code").asText()).isEqualTo(code);
    }

    private void assertInventoryUnchanged(List<ShowtimeSeat> before) {
        Showtime persisted = showtimeMapper.selectById(showtime.getId());
        assertThat(persisted.getTotalSeats()).isEqualTo(2);
        assertThat(persisted.getAvailableSeats()).isEqualTo(2);
        assertThat(states(showtime.getId())).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(before);
    }

    private List<ShowtimeSeat> states(Long showtimeId) {
        return showtimeSeatMapper.selectList(new LambdaQueryWrapper<ShowtimeSeat>()
                .eq(ShowtimeSeat::getShowtimeId, showtimeId)
                .orderByAsc(ShowtimeSeat::getSeatId));
    }

    private String seatKey(Long seatId) {
        return RedisConstants.SEAT_LOCK_KEY_PREFIX + showtime.getId() + ":" + seatId;
    }

    private String tokenKey(String lockToken) {
        return RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + lockToken;
    }

    private Set<String> redisKeys() {
        Set<String> keys = redisTemplate.keys("*");
        return keys == null ? new LinkedHashSet<>() : new LinkedHashSet<>(keys);
    }

    private User insertUser(String nickname) {
        User value = new User();
        value.setPhone("18" + UUID.randomUUID().toString().replace("-", "").substring(0, 9));
        value.setNickName(nickname);
        assertThat(userMapper.insert(value)).isEqualTo(1);
        userIds.add(value.getId());
        return value;
    }

    private Venue insertVenue(String name, int status) {
        Venue value = new Venue();
        value.setName(name);
        value.setType("theater");
        value.setCity("Step153市");
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
        value.setCity("Step153市");
        value.setDescription("Step 15.3 状态测试");
        value.setPriceMin(new BigDecimal("80.00"));
        value.setPriceMax(new BigDecimal("120.00"));
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

    private Seat insertSeat(Long hallId, String seatNo, int col) {
        Seat value = new Seat();
        value.setHallId(hallId);
        value.setSeatNo(seatNo);
        value.setRowNo(1);
        value.setColNo(col);
        value.setSeatType("NORMAL");
        value.setBaseStatus(1);
        assertThat(seatMapper.insert(value)).isEqualTo(1);
        seatIds.add(value.getId());
        return value;
    }

    private Showtime insertShowtime(LocalDateTime startTime, int status) {
        Showtime value = new Showtime();
        value.setBizType("ACTIVITY");
        value.setBizId(activity.getId());
        value.setVenueId(venue.getId());
        value.setHallId(hall.getId());
        value.setStartTime(startTime);
        value.setEndTime(startTime.plusHours(2));
        value.setBasePrice(new BigDecimal("100.00"));
        value.setTotalSeats(seats.size());
        value.setAvailableSeats(seats.size());
        value.setStatus(status);
        assertThat(showtimeMapper.insert(value)).isEqualTo(1);
        showtimeIds.add(value.getId());
        for (Seat seat : seats) {
            ShowtimeSeat state = new ShowtimeSeat();
            state.setShowtimeId(value.getId());
            state.setSeatId(seat.getId());
            state.setStatus(0);
            state.setPrice(new BigDecimal("100.00"));
            state.setVersion(0);
            assertThat(showtimeSeatMapper.insert(state)).isEqualTo(1);
        }
        return value;
    }

    private String login(Long userId) {
        String token = "step153-" + UUID.randomUUID();
        redisTemplate.opsForValue().set(RedisConstants.LOGIN_TOKEN_KEY + token,
                String.valueOf(userId));
        loginTokens.add(token);
        return token;
    }

    private static final class LockedRequest {
        private final String lockToken;
        private final String orderBody;

        private LockedRequest(String lockToken, String orderBody) {
            this.lockToken = lockToken;
            this.orderBody = orderBody;
        }
    }
}
