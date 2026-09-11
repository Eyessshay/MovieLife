package com.movielife.controller;

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
import com.movielife.entity.Venue;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.TicketOrderItemMapper;
import com.movielife.mapper.TicketOrderMapper;
import com.movielife.mapper.VenueMapper;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
    @Autowired private TicketOrderMapper ticketOrderMapper;
    @Autowired private TicketOrderItemMapper ticketOrderItemMapper;

    private Long activityId;
    private Long venueId;
    private Long hallId;
    private Long showtimeId;
    private final Set<Long> seatIds = new LinkedHashSet<>();
    private final Set<String> loginTokens = new LinkedHashSet<>();
    private final Set<String> loginPhones = new LinkedHashSet<>();
    private final Set<String> lockTokens = new LinkedHashSet<>();

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

        int seatIndex = 0;
        for (Long seatId : seatIds) {
            ShowtimeSeat state = new ShowtimeSeat();
            state.setShowtimeId(showtimeId);
            state.setSeatId(seatId);
            state.setStatus(0);
            state.setPrice(seatIndex++ == 0
                    ? new BigDecimal("120.00") : new BigDecimal("180.00"));
            state.setVersion(0);
            showtimeSeatMapper.insert(state);
        }
    }

    @AfterEach
    void cleanup() {
        if (showtimeId != null) {
            List<TicketOrder> orders = ticketOrderMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TicketOrder>()
                            .eq(TicketOrder::getShowtimeId, showtimeId));
            for (TicketOrder order : orders) {
                ticketOrderItemMapper.delete(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TicketOrderItem>()
                                .eq(TicketOrderItem::getOrderId, order.getId()));
            }
            ticketOrderMapper.delete(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TicketOrder>()
                            .eq(TicketOrder::getShowtimeId, showtimeId));
            showtimeSeatMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ShowtimeSeat>()
                    .eq(ShowtimeSeat::getShowtimeId, showtimeId));
            showtimeMapper.deleteById(showtimeId);
        }
        for (String lockToken : lockTokens) {
            redisTemplate.delete(RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + lockToken);
        }
        for (Long seatId : seatIds) {
            if (showtimeId != null) {
                redisTemplate.delete(RedisConstants.SEAT_LOCK_KEY_PREFIX + showtimeId + ":" + seatId);
            }
        }
        for (String token : loginTokens) {
            redisTemplate.delete(RedisConstants.LOGIN_TOKEN_KEY + token);
        }
        for (String phone : loginPhones) {
            redisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + phone);
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

        ResponseEntity<String> genericDetailResponse = restTemplate.getForEntity(
                "/api/showtimes/" + showtimeId, String.class);
        assertThat(genericDetailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode genericDetail = objectMapper.readTree(genericDetailResponse.getBody());
        assertThat(genericDetail.path("data").path("bizType").asText())
                .isEqualTo("ACTIVITY");
        assertThat(genericDetail.path("data").path("activityId").asLong())
                .isEqualTo(activityId);
        assertThat(genericDetail.path("data").path("activityName").asText())
                .isEqualTo("Step102Http固定座位活动");
        assertThat(genericDetail.path("data").path("movieId").isNull()).isTrue();

        ResponseEntity<String> seatMapResponse = restTemplate.getForEntity(
                "/api/showtimes/" + showtimeId + "/seats", String.class);
        assertThat(seatMapResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode seatMap = objectMapper.readTree(seatMapResponse.getBody());
        assertThat(seatMap.path("data").path("showtimeId").asLong())
                .isEqualTo(showtimeId);
        assertThat(seatMap.path("data").path("seats")).hasSize(2);
        assertThat(seatMap.path("data").path("seats").get(0).path("status").asInt())
                .isEqualTo(0);

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

    @Test
    void seededActivityShowtimeShouldExposeSixPhysicalSeatsWithoutWrites() throws Exception {
        Showtime seededBefore = showtimeMapper.selectById(2L);
        Activity seededActivityBefore = activityMapper.selectById(1L);
        Set<String> locksBefore = redisKeys(RedisConstants.SEAT_LOCK_KEY_PREFIX + "*");

        ResponseEntity<String> detailResponse = restTemplate.getForEntity(
                "/api/showtimes/2", String.class);
        assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode detail = objectMapper.readTree(detailResponse.getBody());
        assertThat(detail.path("data").path("bizType").asText()).isEqualTo("ACTIVITY");
        assertThat(detail.path("data").path("activityId").asLong()).isEqualTo(1L);

        ResponseEntity<String> seatMapResponse = restTemplate.getForEntity(
                "/api/showtimes/2/seats", String.class);
        assertThat(seatMapResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode seatMap = objectMapper.readTree(seatMapResponse.getBody());
        assertThat(seatMap.path("data").path("seats")).hasSize(6);

        assertThat(showtimeMapper.selectById(2L)).isEqualTo(seededBefore);
        assertThat(activityMapper.selectById(1L)).isEqualTo(seededActivityBefore);
        assertThat(redisKeys(RedisConstants.SEAT_LOCK_KEY_PREFIX + "*")).isEqualTo(locksBefore);
    }

    @Test
    void activityLockReleaseAndOrderCreationShouldUseRedisQualificationAndMysqlFacts()
            throws Exception {
        LoginSession owner = login("13800000021", "246810");
        List<Long> selected = new ArrayList<>(seatIds);
        String seatJson = selected.get(1) + "," + selected.get(0);
        Showtime beforeLock = showtimeMapper.selectById(showtimeId);

        ResponseEntity<String> firstLock = post(
                "/api/showtimes/" + showtimeId + "/seats/lock",
                "{\"seatIds\":[" + seatJson + "]}", owner.token);
        assertThat(firstLock.getStatusCode()).isEqualTo(HttpStatus.OK);
        String releasedToken = json(firstLock).path("data").path("lockToken").asText();
        lockTokens.add(releasedToken);
        assertThat(releasedToken).isNotBlank();
        for (Long seatId : selected) {
            assertThat(redisTemplate.opsForValue().get(seatKey(seatId)))
                    .isEqualTo(releasedToken);
        }
        assertThat(showtimeMapper.selectById(showtimeId)).isEqualTo(beforeLock);
        assertThat(showtimeSeatMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ShowtimeSeat>()
                        .eq(ShowtimeSeat::getShowtimeId, showtimeId)))
                .allSatisfy(state -> assertThat(state.getStatus()).isZero());

        ResponseEntity<String> release = post(
                "/api/showtimes/" + showtimeId + "/seats/release",
                "{\"lockToken\":\"" + releasedToken + "\"}", owner.token);
        assertThat(release.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(redisTemplate.opsForValue().get(tokenKey(releasedToken))).isNull();
        for (Long seatId : selected) {
            assertThat(redisTemplate.opsForValue().get(seatKey(seatId))).isNull();
        }
        assertThat(showtimeMapper.selectById(showtimeId)).isEqualTo(beforeLock);

        ResponseEntity<String> secondLock = post(
                "/api/showtimes/" + showtimeId + "/seats/lock",
                "{\"seatIds\":[" + seatJson + "]}", owner.token);
        assertThat(secondLock.getStatusCode()).isEqualTo(HttpStatus.OK);
        String consumedToken = json(secondLock).path("data").path("lockToken").asText();
        lockTokens.add(consumedToken);
        assertThat(consumedToken).isNotEqualTo(releasedToken);

        String orderBody = "{\"showtimeId\":" + showtimeId
                + ",\"seatIds\":[" + seatJson + "]"
                + ",\"lockToken\":\"" + consumedToken + "\"}";
        ResponseEntity<String> create = post("/api/orders", orderBody, owner.token);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode created = json(create).path("data");
        long orderId = created.path("orderId").asLong();
        assertThat(orderId).isPositive();
        assertThat(created.path("status").asText()).isEqualTo("UNPAID");
        assertThat(created.path("totalAmount").decimalValue()).isEqualByComparingTo("300.00");

        TicketOrder order = ticketOrderMapper.selectById(orderId);
        assertThat(order.getUserId()).isEqualTo(owner.userId);
        assertThat(order.getLockToken()).isEqualTo(consumedToken);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("300.00");
        List<TicketOrderItem> items = ticketOrderItemMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TicketOrderItem>()
                        .eq(TicketOrderItem::getOrderId, orderId)
                        .orderByAsc(TicketOrderItem::getSeatId));
        assertThat(items).hasSize(2);
        assertThat(items).extracting(TicketOrderItem::getPrice)
                .containsExactlyInAnyOrder(new BigDecimal("120.00"), new BigDecimal("180.00"));
        assertThat(showtimeSeatMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ShowtimeSeat>()
                        .eq(ShowtimeSeat::getShowtimeId, showtimeId)))
                .allSatisfy(state -> {
                    assertThat(state.getStatus()).isEqualTo(1);
                    assertThat(state.getLockOrderId()).isEqualTo(orderId);
                });
        assertThat(showtimeMapper.selectById(showtimeId).getAvailableSeats()).isZero();
        assertThat(redisTemplate.opsForValue().get(tokenKey(consumedToken))).isNull();
        for (Long seatId : selected) {
            assertThat(redisTemplate.opsForValue().get(seatKey(seatId))).isNull();
        }

        ResponseEntity<String> retry = post("/api/orders", orderBody, owner.token);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(retry).path("data").path("orderId").asLong()).isEqualTo(orderId);
        assertThat(ticketOrderMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TicketOrder>()
                        .eq(TicketOrder::getLockToken, consumedToken))).isEqualTo(1);
        assertThat(ticketOrderItemMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TicketOrderItem>()
                        .eq(TicketOrderItem::getOrderId, orderId))).isEqualTo(2);

        String mismatchBody = "{\"showtimeId\":" + showtimeId
                + ",\"seatIds\":[" + selected.get(0) + "]"
                + ",\"lockToken\":\"" + consumedToken + "\"}";
        ResponseEntity<String> mismatch = post("/api/orders", mismatchBody, owner.token);
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(mismatch).path("code").asText()).isEqualTo("ORDER_REQUEST_MISMATCH");
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

    private LoginSession login(String phone, String code) throws Exception {
        loginPhones.add(phone);
        redisTemplate.opsForValue().set(
                RedisConstants.LOGIN_CODE_KEY + phone, code, 5, TimeUnit.MINUTES);
        ResponseEntity<String> response = post("/api/user/login",
                "{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(response);
        String token = body.path("data").path("token").asText();
        long userId = body.path("data").path("user").path("id").asLong();
        loginTokens.add(token);
        return new LoginSession(token, userId);
    }

    private ResponseEntity<String> post(String path, String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private String seatKey(Long seatId) {
        return RedisConstants.SEAT_LOCK_KEY_PREFIX + showtimeId + ":" + seatId;
    }

    private String tokenKey(String lockToken) {
        return RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + lockToken;
    }

    private static final class LoginSession {
        private final String token;
        private final long userId;

        private LoginSession(String token, long userId) {
            this.token = token;
            this.userId = userId;
        }
    }
}
