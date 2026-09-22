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
import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.entity.User;
import com.movielife.entity.Venue;
import com.movielife.entity.VenueStaff;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.AiSummaryTaskMapper;
import com.movielife.mapper.CollectionMapper;
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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "movielife.startup-check.enabled=false"
)
class Stage15AcceptanceHttpIntegrationTest {

    private static final AtomicLong PHONE_SEQUENCE = new AtomicLong(17000000000L);

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
    @Autowired private CollectionMapper collectionMapper;
    @Autowired private AiSummaryTaskMapper aiSummaryTaskMapper;

    private final Set<Long> showtimeIds = new LinkedHashSet<>();
    private final Set<Long> seatIds = new LinkedHashSet<>();
    private final Set<Long> hallIds = new LinkedHashSet<>();
    private final Set<Long> activityIds = new LinkedHashSet<>();
    private final Set<Long> staffRelationIds = new LinkedHashSet<>();
    private final Set<Long> venueIds = new LinkedHashSet<>();
    private final Set<Long> userIds = new LinkedHashSet<>();
    private final Set<String> loginTokens = new LinkedHashSet<>();
    private final Set<String> loginPhones = new LinkedHashSet<>();
    private final Set<String> lockTokens = new LinkedHashSet<>();

    private User staff;
    private User buyer;
    private User ordinary;
    private User inactiveStaff;
    private User otherStaff;
    private Venue venue;
    private Venue otherVenue;
    private Activity activity;
    private Hall hall;
    private List<Seat> seats;
    private LoginSession staffSession;
    private LoginSession buyerSession;
    private LoginSession ordinarySession;
    private LoginSession inactiveSession;
    private LoginSession otherStaffSession;

    @BeforeEach
    void setUp() throws Exception {
        staff = insertUser("Stage15场次管理员");
        buyer = insertUser("Stage15购票用户");
        ordinary = insertUser("Stage15普通用户");
        inactiveStaff = insertUser("Stage15停用员工");
        otherStaff = insertUser("Stage15其他场馆员工");
        venue = insertVenue("Stage15主场馆");
        otherVenue = insertVenue("Stage15其他场馆");
        insertStaff(staff.getId(), venue.getId(), 1);
        insertStaff(inactiveStaff.getId(), venue.getId(), 0);
        insertStaff(otherStaff.getId(), otherVenue.getId(), 1);
        activity = insertActivity("Stage15固定座位活动");
        hall = insertHall(venue.getId(), "Stage15主厅");
        seats = new ArrayList<>();
        seats.add(insertSeat("A01", 1, 1));
        seats.add(insertSeat("A02", 1, 2));
        insertDisabledSeat("A03", 1, 3);

        staffSession = login(staff, "153001");
        buyerSession = login(buyer, "153002");
        ordinarySession = login(ordinary, "153003");
        inactiveSession = login(inactiveStaff, "153004");
        otherStaffSession = login(otherStaff, "153005");
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
        if (!showtimeIds.isEmpty()) showtimeMapper.deleteBatchIds(showtimeIds);
        lockTokens.forEach(token -> redisTemplate.delete(
                RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + token));
        for (Long showtimeId : showtimeIds) {
            for (Long seatId : seatIds) {
                redisTemplate.delete(RedisConstants.SEAT_LOCK_KEY_PREFIX
                        + showtimeId + ":" + seatId);
            }
        }
        loginTokens.forEach(token -> redisTemplate.delete(RedisConstants.LOGIN_TOKEN_KEY + token));
        loginPhones.forEach(phone -> redisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + phone));
        if (!seatIds.isEmpty()) seatMapper.deleteBatchIds(seatIds);
        if (!hallIds.isEmpty()) hallMapper.deleteBatchIds(hallIds);
        if (!staffRelationIds.isEmpty()) venueStaffMapper.deleteBatchIds(staffRelationIds);
        if (!activityIds.isEmpty()) activityMapper.deleteBatchIds(activityIds);
        if (!venueIds.isEmpty()) venueMapper.deleteBatchIds(venueIds);
        if (!userIds.isEmpty()) userMapper.deleteBatchIds(userIds);
    }

    @Test
    void managementCreatedShowtimeShouldCompleteActivityTicketLifecycle() throws Exception {
        SideEffectSnapshot before = sideEffects();
        LocalDateTime start = future(10);
        long showtimeId = createShowtime("stage15-full-flow", start, staffSession.token);

        ResponseEntity<String> management = get("/api/admin/venues/" + venue.getId()
                + "/activity-showtimes?page=1&pageSize=20", staffSession.token);
        assertThat(management.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(management).path("data").path("list")).anySatisfy(item ->
                assertThat(item.path("showtimeId").asLong()).isEqualTo(showtimeId));

        ResponseEntity<String> publicShowtimes = get("/api/activities/" + activity.getId()
                + "/showtimes?date=" + start.toLocalDate() + "&venueId=" + venue.getId(), null);
        assertThat(publicShowtimes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(publicShowtimes).path("data")).anySatisfy(item ->
                assertThat(item.path("showtimeId").asLong()).isEqualTo(showtimeId));

        ResponseEntity<String> seatMap = get("/api/showtimes/" + showtimeId + "/seats", null);
        assertThat(seatMap.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(seatMap).path("data").path("seats")).hasSize(2);
        assertInitialInventory(showtimeId);

        LockedOrderRequest locked = lock(showtimeId, seats, buyerSession.token);
        long orderId = createOrder(locked, buyerSession.token);
        assertInventory(showtimeId, 0, 1, orderId);

        ResponseEntity<String> pay = post("/api/orders/" + orderId + "/pay", "{}",
                buyerSession.token);
        assertThat(pay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertInventory(showtimeId, 0, 2, orderId);

        ResponseEntity<String> issue = post("/api/orders/" + orderId + "/issue", null,
                buyerSession.token);
        assertThat(issue.getStatusCode()).isEqualTo(HttpStatus.OK);
        String ticketCode = json(issue).path("data").get(0).path("ticketCode").asText();
        ResponseEntity<String> verify = post("/api/admin/tickets/verify",
                "{\"ticketCode\":\"" + ticketCode + "\"}", staffSession.token);
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(verify).path("data").path("ticketStatus").asInt()).isEqualTo(2);
        assertThat(ticketOrderMapper.selectById(orderId).getStatus()).isEqualTo("PAID");
        assertThat(queryItems(orderId)).extracting(TicketOrderItem::getTicketStatus)
                .containsExactlyInAnyOrder(2, 1);
        assertNoManagementSideEffects(before);
    }

    @Test
    void ordinaryInactiveAndOtherVenueUsersShouldNotManageTargetVenue() throws Exception {
        CreateActivityShowtimeDTO request = request("stage15-permission", future(12));
        assertError(postCreate(request, ordinarySession.token),
                HttpStatus.FORBIDDEN, "VENUE_MANAGE_FORBIDDEN");
        assertError(postCreate(request, inactiveSession.token),
                HttpStatus.FORBIDDEN, "VENUE_MANAGE_FORBIDDEN");
        assertError(postCreate(request, otherStaffSession.token),
                HttpStatus.FORBIDDEN, "VENUE_MANAGE_FORBIDDEN");

        for (String token : new String[]{ordinarySession.token, inactiveSession.token,
                otherStaffSession.token}) {
            assertError(get("/api/admin/venues/" + venue.getId()
                            + "/activity-showtimes?page=1&pageSize=20", token),
                    HttpStatus.FORBIDDEN, "VENUE_MANAGE_FORBIDDEN");
        }
        assertThat(showtimeMapper.selectCount(new LambdaQueryWrapper<Showtime>()
                .eq(Showtime::getVenueId, venue.getId()))).isZero();
    }

    @Test
    void concurrentIdempotentAndOverlappingCreatesShouldConverge() throws Exception {
        LocalDateTime idempotentStart = future(15);
        CreateActivityShowtimeDTO sameRequest = request("stage15-same-request", idempotentStart);
        List<ResponseEntity<String>> sameResults = concurrentCreate(sameRequest, sameRequest);
        assertThat(sameResults).allSatisfy(response ->
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK));
        long firstId = json(sameResults.get(0)).path("data").path("showtimeId").asLong();
        long secondId = json(sameResults.get(1)).path("data").path("showtimeId").asLong();
        assertThat(secondId).isEqualTo(firstId);
        showtimeIds.add(firstId);
        assertThat(showtimeMapper.selectCount(new LambdaQueryWrapper<Showtime>()
                .eq(Showtime::getVenueId, venue.getId())
                .eq(Showtime::getCreateRequestId, "stage15-same-request"))).isEqualTo(1);
        assertThat(states(firstId)).hasSize(2);
        assertInitialInventory(firstId);

        LocalDateTime overlapStart = future(18);
        List<ResponseEntity<String>> overlapResults = concurrentCreate(
                request("stage15-overlap-a", overlapStart),
                request("stage15-overlap-b", overlapStart.plusMinutes(30)));
        assertThat(overlapResults.stream().filter(response ->
                response.getStatusCode() == HttpStatus.OK).count()).isEqualTo(1);
        assertThat(overlapResults.stream().filter(response ->
                response.getStatusCode() == HttpStatus.CONFLICT).count()).isEqualTo(1);
        for (ResponseEntity<String> response : overlapResults) {
            JsonNode body = json(response);
            if (response.getStatusCode() == HttpStatus.OK) {
                showtimeIds.add(body.path("data").path("showtimeId").asLong());
            } else {
                assertThat(body.path("code").asText()).isEqualTo("SHOWTIME_SCHEDULE_CONFLICT");
            }
        }
    }

    @Test
    void stopBranchAndOrderTransitionsShouldPreserveInventoryAndBoundaries() throws Exception {
        SideEffectSnapshot before = sideEffects();
        long lockedThenStopped = createShowtime(
                "stage15-stop-before-order", future(21), staffSession.token);
        LockedOrderRequest preexistingLock = lock(lockedThenStopped,
                Collections.singletonList(seats.get(0)), buyerSession.token);
        assertOk(postStatus(lockedThenStopped, "stop-sale"));

        ResponseEntity<String> newLock = post("/api/showtimes/" + lockedThenStopped
                        + "/seats/lock", "{\"seatIds\":[" + seats.get(1).getId() + "]}",
                buyerSession.token);
        assertError(newLock, HttpStatus.NOT_FOUND, "SHOWTIME_NOT_FOUND");
        ResponseEntity<String> blockedOrder = post("/api/orders",
                preexistingLock.orderBody, buyerSession.token);
        assertError(blockedOrder, HttpStatus.NOT_FOUND, "SHOWTIME_NOT_FOUND");
        assertThat(redisTemplate.opsForValue().get(tokenKey(preexistingLock.lockToken)))
                .isNotBlank();
        assertInitialInventory(lockedThenStopped);

        long existingOrderShowtime = createShowtime(
                "stage15-existing-order", future(24), staffSession.token);
        LockedOrderRequest cancelLock = lock(existingOrderShowtime, seats, buyerSession.token);
        long canceledOrderId = createOrder(cancelLock, buyerSession.token);
        assertInventory(existingOrderShowtime, 0, 1, canceledOrderId);
        assertOk(postStatus(existingOrderShowtime, "stop-sale"));
        assertOk(post("/api/orders/" + canceledOrderId + "/cancel", null,
                buyerSession.token));
        assertThat(ticketOrderMapper.selectById(canceledOrderId).getStatus())
                .isEqualTo("CANCELED");
        assertInventory(existingOrderShowtime, 2, 0, null);

        assertOk(postStatus(existingOrderShowtime, "resume-sale"));
        LockedOrderRequest paidLock = lock(existingOrderShowtime, seats, buyerSession.token);
        long paidOrderId = createOrder(paidLock, buyerSession.token);
        assertOk(post("/api/orders/" + paidOrderId + "/pay", "{}", buyerSession.token));
        assertInventory(existingOrderShowtime, 0, 2, paidOrderId);
        assertOk(post("/api/orders/" + paidOrderId + "/refund", null,
                buyerSession.token));
        assertThat(ticketOrderMapper.selectById(paidOrderId).getStatus())
                .isEqualTo("REFUNDED");
        assertInventory(existingOrderShowtime, 2, 0, null);
        assertNoManagementSideEffects(before);
    }

    private List<ResponseEntity<String>> concurrentCreate(CreateActivityShowtimeDTO first,
                                                           CreateActivityShowtimeDTO second)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ResponseEntity<String>> firstFuture = executor.submit(() -> {
                start.await();
                return postCreate(first, staffSession.token);
            });
            Future<ResponseEntity<String>> secondFuture = executor.submit(() -> {
                start.await();
                return postCreate(second, staffSession.token);
            });
            start.countDown();
            List<ResponseEntity<String>> results = new ArrayList<>();
            results.add(firstFuture.get(10, TimeUnit.SECONDS));
            results.add(secondFuture.get(10, TimeUnit.SECONDS));
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private long createShowtime(String requestId, LocalDateTime start, String token)
            throws Exception {
        ResponseEntity<String> response = postCreate(request(requestId, start), token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        long showtimeId = json(response).path("data").path("showtimeId").asLong();
        showtimeIds.add(showtimeId);
        return showtimeId;
    }

    private ResponseEntity<String> postCreate(CreateActivityShowtimeDTO request, String token) {
        return exchange("/api/admin/venues/" + venue.getId() + "/activity-showtimes",
                HttpMethod.POST, request, token);
    }

    private ResponseEntity<String> postStatus(long showtimeId, String action) {
        return post("/api/admin/venues/" + venue.getId() + "/activity-showtimes/"
                + showtimeId + "/" + action, null, staffSession.token);
    }

    private CreateActivityShowtimeDTO request(String requestId, LocalDateTime start) {
        CreateActivityShowtimeDTO value = new CreateActivityShowtimeDTO();
        value.setRequestId(requestId);
        value.setActivityId(activity.getId());
        value.setHallId(hall.getId());
        value.setStartTime(start);
        value.setEndTime(start.plusHours(2));
        value.setBasePrice(new BigDecimal("120.00"));
        return value;
    }

    private LockedOrderRequest lock(long showtimeId, List<Seat> selected, String token)
            throws Exception {
        StringBuilder seatJson = new StringBuilder();
        for (Seat seat : selected) {
            if (seatJson.length() > 0) seatJson.append(',');
            seatJson.append(seat.getId());
        }
        ResponseEntity<String> response = post("/api/showtimes/" + showtimeId
                + "/seats/lock", "{\"seatIds\":[" + seatJson + "]}", token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String lockToken = json(response).path("data").path("lockToken").asText();
        lockTokens.add(lockToken);
        String orderBody = "{\"showtimeId\":" + showtimeId + ",\"seatIds\":["
                + seatJson + "],\"lockToken\":\"" + lockToken + "\"}";
        return new LockedOrderRequest(lockToken, orderBody);
    }

    private long createOrder(LockedOrderRequest locked, String token) throws Exception {
        ResponseEntity<String> response = post("/api/orders", locked.orderBody, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        long orderId = json(response).path("data").path("orderId").asLong();
        assertThat(redisTemplate.opsForValue().get(tokenKey(locked.lockToken))).isNull();
        return orderId;
    }

    private void assertInitialInventory(long showtimeId) {
        Showtime persisted = showtimeMapper.selectById(showtimeId);
        assertThat(persisted.getTotalSeats()).isEqualTo(2);
        assertThat(persisted.getAvailableSeats()).isEqualTo(2);
        assertThat(states(showtimeId)).hasSize(2).allSatisfy(state -> {
            assertThat(state.getStatus()).isZero();
            assertThat(state.getLockOrderId()).isNull();
        });
    }

    private void assertInventory(long showtimeId, int availableSeats, int seatStatus,
                                 Long orderId) {
        Showtime persisted = showtimeMapper.selectById(showtimeId);
        assertThat(persisted.getTotalSeats()).isEqualTo(2);
        assertThat(persisted.getAvailableSeats()).isEqualTo(availableSeats);
        assertThat(states(showtimeId)).hasSize(2).allSatisfy(state -> {
            assertThat(state.getStatus()).isEqualTo(seatStatus);
            assertThat(state.getLockOrderId()).isEqualTo(orderId);
        });
    }

    private List<ShowtimeSeat> states(long showtimeId) {
        return showtimeSeatMapper.selectList(new LambdaQueryWrapper<ShowtimeSeat>()
                .eq(ShowtimeSeat::getShowtimeId, showtimeId)
                .orderByAsc(ShowtimeSeat::getSeatId));
    }

    private List<TicketOrderItem> queryItems(long orderId) {
        return ticketOrderItemMapper.selectList(new LambdaQueryWrapper<TicketOrderItem>()
                .eq(TicketOrderItem::getOrderId, orderId)
                .orderByAsc(TicketOrderItem::getId));
    }

    private SideEffectSnapshot sideEffects() {
        return new SideEffectSnapshot(collectionMapper.selectCount(null),
                aiSummaryTaskMapper.selectCount(null), stableRedisKeys(),
                redisTemplate.opsForZSet().score("venue:geo:Stage15市:THEATER",
                        String.valueOf(venue.getId())));
    }

    private void assertNoManagementSideEffects(SideEffectSnapshot before) {
        assertThat(collectionMapper.selectCount(null)).isEqualTo(before.collectionCount);
        assertThat(aiSummaryTaskMapper.selectCount(null)).isEqualTo(before.aiTaskCount);
        assertThat(stableRedisKeys()).isEqualTo(before.stableRedisKeys);
        assertThat(redisTemplate.opsForZSet().score("venue:geo:Stage15市:THEATER",
                String.valueOf(venue.getId()))).isEqualTo(before.geoScore);
    }

    private Set<String> stableRedisKeys() {
        Set<String> keys = redisTemplate.keys("*");
        if (keys == null) return Collections.emptySet();
        Set<String> stable = new LinkedHashSet<>();
        for (String key : keys) {
            if (!key.startsWith(RedisConstants.SEAT_LOCK_KEY_PREFIX)
                    && !key.startsWith(RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX)) {
                stable.add(key);
            }
        }
        return stable;
    }

    private void assertOk(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void assertError(ResponseEntity<String> response, HttpStatus status, String code)
            throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(json(response).path("code").asText()).isEqualTo(code);
    }

    private ResponseEntity<String> post(String path, String body, String token) {
        return exchange(path, HttpMethod.POST, body, token);
    }

    private ResponseEntity<String> get(String path, String token) {
        return exchange(path, HttpMethod.GET, null, token);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method,
                                            Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private String tokenKey(String lockToken) {
        return RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + lockToken;
    }

    private LocalDateTime future(int days) {
        return LocalDateTime.now().plusDays(days).withNano(0);
    }

    private User insertUser(String nickname) {
        User value = new User();
        value.setPhone(String.valueOf(PHONE_SEQUENCE.incrementAndGet()));
        value.setNickName(nickname);
        assertThat(userMapper.insert(value)).isEqualTo(1);
        userIds.add(value.getId());
        return value;
    }

    private Venue insertVenue(String name) {
        Venue value = new Venue();
        value.setName(name);
        value.setType("theater");
        value.setCity("Stage15市");
        value.setAddress(name + "地址");
        value.setAvgScore(BigDecimal.ZERO);
        value.setStatus(1);
        assertThat(venueMapper.insert(value)).isEqualTo(1);
        venueIds.add(value.getId());
        return value;
    }

    private void insertStaff(long userId, long venueId, int status) {
        VenueStaff value = new VenueStaff();
        value.setUserId(userId);
        value.setVenueId(venueId);
        value.setStatus(status);
        assertThat(venueStaffMapper.insert(value)).isEqualTo(1);
        staffRelationIds.add(value.getId());
    }

    private Activity insertActivity(String title) {
        Activity value = new Activity();
        value.setTitle(title);
        value.setType("drama");
        value.setCity("Stage15市");
        value.setDescription("Stage 15 跨链路验收");
        value.setPriceMin(new BigDecimal("100.00"));
        value.setPriceMax(new BigDecimal("150.00"));
        value.setStatus(1);
        assertThat(activityMapper.insert(value)).isEqualTo(1);
        activityIds.add(value.getId());
        return value;
    }

    private Hall insertHall(long venueId, String name) {
        Hall value = new Hall();
        value.setVenueId(venueId);
        value.setName(name);
        assertThat(hallMapper.insert(value)).isEqualTo(1);
        hallIds.add(value.getId());
        return value;
    }

    private Seat insertSeat(String number, int row, int column) {
        Seat value = seat(number, row, column, 1);
        seatsAdd(value);
        return value;
    }

    private void insertDisabledSeat(String number, int row, int column) {
        seatsAdd(seat(number, row, column, 0));
    }

    private Seat seat(String number, int row, int column, int status) {
        Seat value = new Seat();
        value.setHallId(hall.getId());
        value.setSeatNo(number);
        value.setRowNo(row);
        value.setColNo(column);
        value.setSeatType("NORMAL");
        value.setBaseStatus(status);
        return value;
    }

    private void seatsAdd(Seat value) {
        assertThat(seatMapper.insert(value)).isEqualTo(1);
        seatIds.add(value.getId());
    }

    private LoginSession login(User user, String code) throws Exception {
        loginPhones.add(user.getPhone());
        redisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + user.getPhone(),
                code, 5, TimeUnit.MINUTES);
        ResponseEntity<String> response = post("/api/user/login",
                "{\"phone\":\"" + user.getPhone() + "\",\"code\":\""
                        + code + "\"}", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = json(response).path("data");
        String token = data.path("token").asText();
        assertThat(data.path("user").path("id").asLong()).isEqualTo(user.getId());
        loginTokens.add(token);
        return new LoginSession(token);
    }

    private static final class LoginSession {
        private final String token;

        private LoginSession(String token) {
            this.token = token;
        }
    }

    private static final class LockedOrderRequest {
        private final String lockToken;
        private final String orderBody;

        private LockedOrderRequest(String lockToken, String orderBody) {
            this.lockToken = lockToken;
            this.orderBody = orderBody;
        }
    }

    private static final class SideEffectSnapshot {
        private final long collectionCount;
        private final long aiTaskCount;
        private final Set<String> stableRedisKeys;
        private final Double geoScore;

        private SideEffectSnapshot(long collectionCount, long aiTaskCount,
                                   Set<String> stableRedisKeys, Double geoScore) {
            this.collectionCount = collectionCount;
            this.aiTaskCount = aiTaskCount;
            this.stableRedisKeys = stableRedisKeys;
            this.geoScore = geoScore;
        }
    }
}
