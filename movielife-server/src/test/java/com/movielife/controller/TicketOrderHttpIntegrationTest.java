package com.movielife.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.constants.RedisConstants;
import com.movielife.entity.Seat;
import com.movielife.entity.Showtime;
import com.movielife.entity.ShowtimeSeat;
import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.TicketOrderItemMapper;
import com.movielife.mapper.TicketOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "movielife.startup-check.enabled=false"
)
class TicketOrderHttpIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private SeatMapper seatMapper;
    @Autowired private ShowtimeMapper showtimeMapper;
    @Autowired private ShowtimeSeatMapper showtimeSeatMapper;
    @Autowired private TicketOrderMapper ticketOrderMapper;
    @Autowired private TicketOrderItemMapper ticketOrderItemMapper;

    private final List<Long> selectedSeatIds = new ArrayList<>();
    private final Set<String> loginTokens = new LinkedHashSet<>();
    private final Set<String> loginPhones = new LinkedHashSet<>();
    private final Set<String> lockTokens = new LinkedHashSet<>();
    private Long showtimeId;

    @BeforeEach
    void createFutureShowtime() {
        List<Seat> seats = seatMapper.selectList(new LambdaQueryWrapper<Seat>()
                .eq(Seat::getHallId, 1L)
                .eq(Seat::getBaseStatus, 1)
                .orderByAsc(Seat::getId));
        assertThat(seats).hasSizeGreaterThanOrEqualTo(2);
        selectedSeatIds.add(seats.get(0).getId());
        selectedSeatIds.add(seats.get(1).getId());

        Showtime showtime = new Showtime();
        showtime.setBizType("MOVIE");
        showtime.setBizId(1L);
        showtime.setVenueId(1L);
        showtime.setHallId(1L);
        showtime.setStartTime(LocalDateTime.now().plusDays(1).withNano(0));
        showtime.setEndTime(showtime.getStartTime().plusHours(2));
        showtime.setLanguageVersion("国语 2D");
        showtime.setBasePrice(new BigDecimal("10.00"));
        showtime.setTotalSeats(seats.size());
        showtime.setAvailableSeats(seats.size());
        showtime.setStatus(1);
        assertThat(showtimeMapper.insert(showtime)).isEqualTo(1);
        showtimeId = showtime.getId();

        for (int index = 0; index < seats.size(); index++) {
            ShowtimeSeat state = new ShowtimeSeat();
            state.setShowtimeId(showtimeId);
            state.setSeatId(seats.get(index).getId());
            state.setStatus(0);
            if (index == 0) {
                state.setPrice(new BigDecimal("40.00"));
            } else if (index == 1) {
                state.setPrice(new BigDecimal("55.00"));
            } else {
                state.setPrice(new BigDecimal("45.00"));
            }
            state.setVersion(0);
            assertThat(showtimeSeatMapper.insert(state)).isEqualTo(1);
        }
    }

    @AfterEach
    void cleanUp() {
        if (showtimeId != null) {
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
            showtimeMapper.deleteById(showtimeId);
        }
        for (String lockToken : lockTokens) {
            redisTemplate.delete(RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + lockToken);
        }
        for (Long seatId : selectedSeatIds) {
            redisTemplate.delete(RedisConstants.SEAT_LOCK_KEY_PREFIX + showtimeId + ":" + seatId);
        }
        for (String token : loginTokens) {
            redisTemplate.delete(RedisConstants.LOGIN_TOKEN_KEY + token);
        }
        for (String phone : loginPhones) {
            redisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + phone);
        }
        assertThat(redisTemplate.keys(RedisConstants.SEAT_LOCK_KEY_PREFIX + "*")).isEmpty();
    }

    @Test
    void completeHttpFlowShouldCreateOnceCleanRedisAndEnforceOwnership() throws Exception {
        LoginSession owner = login("13800000001", "654321");
        LoginSession anotherUser = login("13800000002", "123456");

        ResponseEntity<String> detailResponse = get("/api/showtimes/" + showtimeId, owner.token);
        assertThat(detailResponse.getStatusCodeValue()).isEqualTo(200);
        assertThat(json(detailResponse).path("data").path("id").asLong()).isEqualTo(showtimeId);

        ResponseEntity<String> seatMapResponse = get(
                "/api/showtimes/" + showtimeId + "/seats", owner.token);
        assertThat(seatMapResponse.getStatusCodeValue()).isEqualTo(200);
        JsonNode seatMap = json(seatMapResponse).path("data").path("seats");
        assertThat(seatMap.isArray()).isTrue();
        for (Long selectedSeatId : selectedSeatIds) {
            boolean available = false;
            for (JsonNode seat : seatMap) {
                if (seat.path("seatId").asLong() == selectedSeatId
                        && seat.path("status").asInt() == 0) {
                    available = true;
                    break;
                }
            }
            assertThat(available).isTrue();
        }

        String seatJson = selectedSeatIds.get(1) + "," + selectedSeatIds.get(0);
        ResponseEntity<String> lockResponse = post(
                "/api/showtimes/" + showtimeId + "/seats/lock",
                "{\"seatIds\":[" + seatJson + "]}", owner.token);
        assertThat(lockResponse.getStatusCodeValue()).isEqualTo(200);
        String lockToken = json(lockResponse).path("data").path("lockToken").asText();
        assertThat(lockToken).isNotBlank();
        lockTokens.add(lockToken);
        for (Long seatId : selectedSeatIds) {
            assertThat(redisTemplate.opsForValue().get(seatKey(seatId))).isEqualTo(lockToken);
        }
        assertThat(redisTemplate.opsForValue().get(tokenKey(lockToken))).isNotBlank();

        int availableBefore = showtimeMapper.selectById(showtimeId).getAvailableSeats();
        String orderBody = "{\"showtimeId\":" + showtimeId
                + ",\"seatIds\":[" + seatJson + "],\"lockToken\":\"" + lockToken + "\"}";
        ResponseEntity<String> createResponse = post("/api/orders", orderBody, owner.token);
        assertThat(createResponse.getStatusCodeValue()).isEqualTo(200);
        JsonNode created = json(createResponse);
        assertThat(created.path("code").asText()).isEqualTo("OK");
        long orderId = created.path("data").path("orderId").asLong();
        assertThat(orderId).isPositive();
        assertThat(created.path("data").path("status").asText()).isEqualTo("UNPAID");
        assertThat(created.path("data").path("totalAmount").decimalValue())
                .isEqualByComparingTo("95.00");
        assertThat(LocalDateTime.parse(created.path("data").path("expireTime").asText()))
                .isAfter(LocalDateTime.now());

        TicketOrder order = ticketOrderMapper.selectById(orderId);
        assertThat(order.getUserId()).isEqualTo(owner.userId);
        assertThat(order.getShowtimeId()).isEqualTo(showtimeId);
        assertThat(order.getLockToken()).isEqualTo(lockToken);
        assertThat(order.getStatus()).isEqualTo("UNPAID");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("95.00");
        assertThat(order.getExpireTime()).isAfter(order.getCreateTime());

        List<TicketOrderItem> items = ticketOrderItemMapper.selectList(
                new LambdaQueryWrapper<TicketOrderItem>()
                        .eq(TicketOrderItem::getOrderId, orderId)
                        .orderByAsc(TicketOrderItem::getSeatId));
        assertThat(items).hasSize(2);
        assertThat(items).extracting(TicketOrderItem::getPrice)
                .containsExactly(new BigDecimal("40.00"), new BigDecimal("55.00"));
        assertThat(items).allSatisfy(item -> {
            assertThat(item.getTicketCode()).isNull();
            assertThat(item.getTicketStatus()).isZero();
        });

        List<ShowtimeSeat> heldSeats = showtimeSeatMapper.selectList(
                new LambdaQueryWrapper<ShowtimeSeat>()
                        .eq(ShowtimeSeat::getShowtimeId, showtimeId)
                        .in(ShowtimeSeat::getSeatId, selectedSeatIds));
        assertThat(heldSeats).hasSize(2).allSatisfy(state -> {
            assertThat(state.getStatus()).isEqualTo(1);
            assertThat(state.getLockOrderId()).isEqualTo(orderId);
        });
        assertThat(showtimeMapper.selectById(showtimeId).getAvailableSeats())
                .isEqualTo(availableBefore - 2);
        assertThat(redisTemplate.opsForValue().get(tokenKey(lockToken))).isNull();
        for (Long seatId : selectedSeatIds) {
            assertThat(redisTemplate.opsForValue().get(seatKey(seatId))).isNull();
        }

        ResponseEntity<String> retryResponse = post("/api/orders", orderBody, owner.token);
        assertThat(retryResponse.getStatusCodeValue()).isEqualTo(200);
        assertThat(json(retryResponse).path("data").path("orderId").asLong()).isEqualTo(orderId);
        assertThat(ticketOrderMapper.selectCount(new LambdaQueryWrapper<TicketOrder>()
                .eq(TicketOrder::getLockToken, lockToken))).isEqualTo(1);
        assertThat(ticketOrderItemMapper.selectCount(new LambdaQueryWrapper<TicketOrderItem>()
                .eq(TicketOrderItem::getOrderId, orderId))).isEqualTo(2);
        assertThat(showtimeMapper.selectById(showtimeId).getAvailableSeats())
                .isEqualTo(availableBefore - 2);

        String mismatchBody = "{\"showtimeId\":" + showtimeId
                + ",\"seatIds\":[" + selectedSeatIds.get(0)
                + "],\"lockToken\":\"" + lockToken + "\"}";
        ResponseEntity<String> mismatch = post("/api/orders", mismatchBody, owner.token);
        assertThat(mismatch.getStatusCodeValue()).isEqualTo(409);
        assertThat(json(mismatch).path("code").asText()).isEqualTo("ORDER_REQUEST_MISMATCH");

        ResponseEntity<String> forbidden = post("/api/orders", orderBody, anotherUser.token);
        assertThat(forbidden.getStatusCodeValue()).isEqualTo(403);
        assertThat(json(forbidden).path("code").asText()).isEqualTo("ORDER_NOT_OWNED");
        assertThat(ticketOrderMapper.selectCount(new LambdaQueryWrapper<TicketOrder>()
                .eq(TicketOrder::getLockToken, lockToken))).isEqualTo(1);
        assertThat(ticketOrderItemMapper.selectCount(new LambdaQueryWrapper<TicketOrderItem>()
                .eq(TicketOrderItem::getOrderId, orderId))).isEqualTo(2);
        assertThat(showtimeMapper.selectById(showtimeId).getAvailableSeats())
                .isEqualTo(availableBefore - 2);

        Long olderOrderId = insertHistoricalOrder(owner.userId, "CANCELED", -1);
        Long paidOrderId = insertHistoricalOrder(anotherUser.userId, "PAID", -2);
        TicketOrder orderBeforeQueries = ticketOrderMapper.selectById(orderId);
        List<TicketOrderItem> itemsBeforeQueries = ticketOrderItemMapper.selectList(
                new LambdaQueryWrapper<TicketOrderItem>()
                        .eq(TicketOrderItem::getOrderId, orderId)
                        .orderByAsc(TicketOrderItem::getId));
        List<ShowtimeSeat> seatsBeforeQueries = showtimeSeatMapper.selectList(
                new LambdaQueryWrapper<ShowtimeSeat>()
                        .eq(ShowtimeSeat::getShowtimeId, showtimeId)
                        .in(ShowtimeSeat::getSeatId, selectedSeatIds)
                        .orderByAsc(ShowtimeSeat::getSeatId));
        Showtime showtimeBeforeQueries = showtimeMapper.selectById(showtimeId);
        String loginValueBeforeQueries = redisTemplate.opsForValue().get(
                RedisConstants.LOGIN_TOKEN_KEY + owner.token);

        ResponseEntity<String> listResponse = get("/api/orders?page=1&pageSize=20", owner.token);
        assertThat(listResponse.getStatusCodeValue()).isEqualTo(200);
        JsonNode orderPage = json(listResponse).path("data");
        assertThat(orderPage.path("total").asLong()).isEqualTo(2);
        assertThat(orderPage.path("page").asInt()).isEqualTo(1);
        assertThat(orderPage.path("pageSize").asInt()).isEqualTo(20);
        assertThat(orderPage.path("list").get(0).path("orderId").asLong()).isEqualTo(orderId);
        assertThat(orderPage.path("list").get(1).path("orderId").asLong())
                .isEqualTo(olderOrderId);
        JsonNode createdListItem = orderPage.path("list").get(0);
        assertThat(createdListItem.path("showtimeId").asLong()).isEqualTo(showtimeId);
        assertThat(createdListItem.path("movieId").asLong()).isEqualTo(1L);
        assertThat(createdListItem.path("venueId").asLong()).isEqualTo(1L);
        assertThat(createdListItem.path("hallId").asLong()).isEqualTo(1L);
        assertThat(createdListItem.path("movieName").asText()).isNotBlank();
        assertThat(createdListItem.path("venueName").asText()).isNotBlank();
        assertThat(createdListItem.path("hallName").asText()).isNotBlank();
        assertThat(createdListItem.path("seatCount").asInt()).isEqualTo(2);

        ResponseEntity<String> detailResponseAfterCreate = get(
                "/api/orders/" + orderId, owner.token);
        assertThat(detailResponseAfterCreate.getStatusCodeValue()).isEqualTo(200);
        JsonNode detail = json(detailResponseAfterCreate).path("data");
        assertThat(detail.path("orderId").asLong()).isEqualTo(orderId);
        assertThat(detail.path("showtimeId").asLong()).isEqualTo(showtimeId);
        assertThat(detail.path("status").asText()).isEqualTo("UNPAID");
        assertThat(detail.path("totalAmount").decimalValue()).isEqualByComparingTo("95.00");
        assertThat(detail.path("items")).hasSize(2);
        assertThat(detail.path("items").get(0).path("rowNo").asInt())
                .isLessThanOrEqualTo(detail.path("items").get(1).path("rowNo").asInt());
        assertThat(detail.path("items").get(0).path("ticketCode").isNull()).isTrue();
        assertThat(detail.path("items").get(0).path("ticketStatus").asInt()).isZero();
        assertThat(detail.path("items").get(0).path("price").decimalValue()
                .add(detail.path("items").get(1).path("price").decimalValue()))
                .isEqualByComparingTo("95.00");

        ResponseEntity<String> hiddenForeignOrder = get(
                "/api/orders/" + orderId, anotherUser.token);
        assertThat(hiddenForeignOrder.getStatusCodeValue()).isEqualTo(404);
        assertThat(json(hiddenForeignOrder).path("code").asText()).isEqualTo("ORDER_NOT_FOUND");
        ResponseEntity<String> missingOrder = get("/api/orders/9223372036854775807", owner.token);
        assertThat(missingOrder.getStatusCodeValue()).isEqualTo(404);
        assertThat(json(missingOrder).path("code").asText()).isEqualTo("ORDER_NOT_FOUND");

        assertThat(ticketOrderMapper.selectById(orderId)).isEqualTo(orderBeforeQueries);
        assertThat(ticketOrderItemMapper.selectList(new LambdaQueryWrapper<TicketOrderItem>()
                .eq(TicketOrderItem::getOrderId, orderId)
                .orderByAsc(TicketOrderItem::getId))).isEqualTo(itemsBeforeQueries);
        assertThat(showtimeSeatMapper.selectList(new LambdaQueryWrapper<ShowtimeSeat>()
                .eq(ShowtimeSeat::getShowtimeId, showtimeId)
                .in(ShowtimeSeat::getSeatId, selectedSeatIds)
                .orderByAsc(ShowtimeSeat::getSeatId))).isEqualTo(seatsBeforeQueries);
        assertThat(showtimeMapper.selectById(showtimeId)).isEqualTo(showtimeBeforeQueries);
        assertThat(redisTemplate.opsForValue().get(RedisConstants.LOGIN_TOKEN_KEY + owner.token))
                .isEqualTo(loginValueBeforeQueries);
        assertThat(redisTemplate.opsForValue().get(tokenKey(lockToken))).isNull();
        for (Long seatId : selectedSeatIds) {
            assertThat(redisTemplate.opsForValue().get(seatKey(seatId))).isNull();
        }

        ResponseEntity<String> foreignCancel = post(
                "/api/orders/" + orderId + "/cancel", null, anotherUser.token);
        assertThat(foreignCancel.getStatusCodeValue()).isEqualTo(404);
        assertThat(json(foreignCancel).path("code").asText()).isEqualTo("ORDER_NOT_FOUND");
        ResponseEntity<String> paidCancel = post(
                "/api/orders/" + paidOrderId + "/cancel", null, anotherUser.token);
        assertThat(paidCancel.getStatusCodeValue()).isEqualTo(409);
        assertThat(json(paidCancel).path("code").asText()).isEqualTo("ORDER_STATUS_CONFLICT");

        ResponseEntity<String> cancelResponse = post(
                "/api/orders/" + orderId + "/cancel", null, owner.token);
        assertThat(cancelResponse.getStatusCodeValue()).isEqualTo(200);
        assertThat(json(cancelResponse).path("code").asText()).isEqualTo("OK");
        assertThat(ticketOrderMapper.selectById(orderId).getStatus()).isEqualTo("CANCELED");
        assertThat(ticketOrderItemMapper.selectCount(new LambdaQueryWrapper<TicketOrderItem>()
                .eq(TicketOrderItem::getOrderId, orderId))).isEqualTo(2);
        assertThat(showtimeMapper.selectById(showtimeId).getAvailableSeats())
                .isEqualTo(availableBefore);
        assertThat(showtimeSeatMapper.selectList(new LambdaQueryWrapper<ShowtimeSeat>()
                .eq(ShowtimeSeat::getShowtimeId, showtimeId)
                .in(ShowtimeSeat::getSeatId, selectedSeatIds))).allSatisfy(state -> {
                    assertThat(state.getStatus()).isZero();
                    assertThat(state.getLockOrderId()).isNull();
                });

        ResponseEntity<String> canceledDetail = get("/api/orders/" + orderId, owner.token);
        assertThat(canceledDetail.getStatusCodeValue()).isEqualTo(200);
        assertThat(json(canceledDetail).path("data").path("status").asText())
                .isEqualTo("CANCELED");
        ResponseEntity<String> seatsAfterCancel = get(
                "/api/showtimes/" + showtimeId + "/seats", owner.token);
        assertThat(seatsAfterCancel.getStatusCodeValue()).isEqualTo(200);
        JsonNode seatMapAfterCancel = json(seatsAfterCancel).path("data").path("seats");
        for (Long selectedSeatId : selectedSeatIds) {
            boolean available = false;
            for (JsonNode seat : seatMapAfterCancel) {
                if (seat.path("seatId").asLong() == selectedSeatId
                        && seat.path("status").asInt() == 0) {
                    available = true;
                    break;
                }
            }
            assertThat(available).isTrue();
        }

        ResponseEntity<String> repeatedCancel = post(
                "/api/orders/" + orderId + "/cancel", null, owner.token);
        assertThat(repeatedCancel.getStatusCodeValue()).isEqualTo(200);
        assertThat(showtimeMapper.selectById(showtimeId).getAvailableSeats())
                .isEqualTo(availableBefore);
        assertThat(ticketOrderMapper.selectById(orderId).getStatus()).isEqualTo("CANCELED");
        assertThat(redisTemplate.opsForValue().get(tokenKey(lockToken))).isNull();
        for (Long seatId : selectedSeatIds) {
            assertThat(redisTemplate.opsForValue().get(seatKey(seatId))).isNull();
        }
    }

    private Long insertHistoricalOrder(Long userId, String status, int daysOffset) {
        TicketOrder order = new TicketOrder();
        order.setUserId(userId);
        order.setShowtimeId(showtimeId);
        order.setLockToken("query-" + UUID.randomUUID());
        order.setTotalAmount(new BigDecimal("40.00"));
        order.setStatus(status);
        order.setExpireTime(LocalDateTime.now().plusDays(1));
        order.setCreateTime(LocalDateTime.now().plusDays(daysOffset));
        assertThat(ticketOrderMapper.insert(order)).isEqualTo(1);

        TicketOrderItem item = new TicketOrderItem();
        item.setOrderId(order.getId());
        item.setSeatId(selectedSeatIds.get(0));
        item.setPrice(new BigDecimal("40.00"));
        item.setTicketStatus(0);
        assertThat(ticketOrderItemMapper.insert(item)).isEqualTo(1);
        return order.getId();
    }

    private LoginSession login(String phone, String code) throws Exception {
        loginPhones.add(phone);
        redisTemplate.opsForValue().set(
                RedisConstants.LOGIN_CODE_KEY + phone, code, 5, TimeUnit.MINUTES);
        ResponseEntity<String> response = post("/api/user/login",
                "{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}", null);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        JsonNode body = json(response);
        String token = body.path("data").path("token").asText();
        long userId = body.path("data").path("user").path("id").asLong();
        assertThat(token).isNotBlank();
        assertThat(userId).isPositive();
        loginTokens.add(token);
        return new LoginSession(token, userId);
    }

    private ResponseEntity<String> get(String path, String token) {
        return restTemplate.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(headers(token)), String.class);
    }

    private ResponseEntity<String> post(String path, String body, String token) {
        return restTemplate.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, headers(token)), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
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
