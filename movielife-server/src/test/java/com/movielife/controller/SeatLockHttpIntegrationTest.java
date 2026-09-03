package com.movielife.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.constants.RedisConstants;
import com.movielife.entity.Seat;
import com.movielife.entity.Showtime;
import com.movielife.entity.ShowtimeSeat;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "movielife.startup-check.enabled=false"
)
class SeatLockHttpIntegrationTest {

    private static final String LOGIN_PHONE = "13800000001";
    private static final String LOGIN_CODE = "654321";

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private ShowtimeMapper showtimeMapper;
    @Autowired
    private SeatMapper seatMapper;
    @Autowired
    private ShowtimeSeatMapper showtimeSeatMapper;

    private Long showtimeId;
    private String loginToken;
    private String lockToken;
    private final List<Long> seatIds = new ArrayList<>();
    private final Set<String> loginTokens = new LinkedHashSet<>();
    private final Set<String> lockTokens = new LinkedHashSet<>();
    private final Set<String> loginPhones = new LinkedHashSet<>();

    @BeforeEach
    void createFutureShowtime() {
        List<Seat> seats = seatMapper.selectList(
                new LambdaQueryWrapper<Seat>()
                        .eq(Seat::getHallId, 1L)
                        .eq(Seat::getBaseStatus, 1)
                        .orderByAsc(Seat::getId)
        );
        assertThat(seats).hasSizeGreaterThanOrEqualTo(2);
        seats.stream().map(Seat::getId).limit(2).forEach(seatIds::add);

        Showtime showtime = new Showtime();
        showtime.setBizType("MOVIE");
        showtime.setBizId(1L);
        showtime.setVenueId(1L);
        showtime.setHallId(1L);
        showtime.setStartTime(LocalDateTime.now().plusDays(1));
        showtime.setEndTime(LocalDateTime.now().plusDays(1).plusHours(2));
        showtime.setLanguageVersion("国语 2D");
        showtime.setBasePrice(new BigDecimal("45.00"));
        showtime.setTotalSeats(seats.size());
        showtime.setAvailableSeats(seats.size());
        showtime.setStatus(1);
        assertThat(showtimeMapper.insert(showtime)).isEqualTo(1);
        showtimeId = showtime.getId();

        for (Seat seat : seats) {
            ShowtimeSeat state = new ShowtimeSeat();
            state.setShowtimeId(showtimeId);
            state.setSeatId(seat.getId());
            state.setStatus(0);
            state.setPrice("VIP".equals(seat.getSeatType())
                    ? new BigDecimal("60.00") : new BigDecimal("45.00"));
            assertThat(showtimeSeatMapper.insert(state)).isEqualTo(1);
        }
    }

    @AfterEach
    void cleanUp() {
        if (showtimeId != null) {
            for (Long seatId : seatIds) {
                redisTemplate.delete(seatKey(seatId));
            }
            showtimeSeatMapper.delete(
                    new LambdaQueryWrapper<ShowtimeSeat>()
                            .eq(ShowtimeSeat::getShowtimeId, showtimeId)
            );
            showtimeMapper.deleteById(showtimeId);
            assertThat(showtimeSeatMapper.selectCount(
                    new LambdaQueryWrapper<ShowtimeSeat>()
                            .eq(ShowtimeSeat::getShowtimeId, showtimeId)
            )).isZero();
            assertThat(showtimeMapper.selectById(showtimeId)).isNull();
        }
        for (String token : lockTokens) {
            redisTemplate.delete(RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + token);
        }
        for (String token : loginTokens) {
            redisTemplate.delete(RedisConstants.LOGIN_TOKEN_KEY + token);
        }
        for (String phone : loginPhones) {
            redisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + phone);
        }

        Set<String> remainingSeatLocks = redisTemplate.keys(RedisConstants.SEAT_LOCK_KEY_PREFIX + "*");
        assertThat(remainingSeatLocks).isEmpty();
    }

    @Test
    void loginLockIdempotentLockReleaseAndSeatMapShouldWorkOverRealHttp() throws Exception {
        LoginSession login = login(LOGIN_PHONE, LOGIN_CODE);
        loginToken = login.token;

        JsonNode detail = getJson("/api/showtimes/" + showtimeId, loginToken);
        assertThat(detail.path("code").asText()).isEqualTo("OK");
        assertThat(detail.path("data").path("id").asLong()).isEqualTo(showtimeId);

        JsonNode beforeLockSeatMap = getJson("/api/showtimes/" + showtimeId + "/seats", loginToken);
        assertThat(beforeLockSeatMap.path("code").asText()).isEqualTo("OK");
        assertThat(beforeLockSeatMap.path("data").path("seats").isArray()).isTrue();
        assertSeatStatuses(beforeLockSeatMap, 0);
        assertDatabaseSeatStatuses(0);

        String lockBody = "{\"seatIds\":[" + seatIds.get(1) + "," + seatIds.get(0) + "]}";
        JsonNode firstLock = postJson(
                "/api/showtimes/" + showtimeId + "/seats/lock", lockBody, loginToken);
        assertThat(firstLock.path("code").asText()).isEqualTo("OK");
        lockToken = firstLock.path("data").path("lockToken").asText();
        lockTokens.add(lockToken);
        String expireTime = firstLock.path("data").path("expireTime").asText();
        assertThat(lockToken).isNotBlank();
        assertThat(expireTime).isNotBlank();
        LocalDateTime parsedExpireTime = LocalDateTime.parse(expireTime);
        assertThat(parsedExpireTime).isAfter(LocalDateTime.now().plusMinutes(4));
        assertThat(parsedExpireTime).isBefore(LocalDateTime.now().plusMinutes(6));

        for (Long seatId : seatIds) {
            assertThat(redisTemplate.opsForValue().get(seatKey(seatId))).isEqualTo(lockToken);
        }
        String metadataJson = redisTemplate.opsForValue().get(
                RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + lockToken);
        assertThat(metadataJson).isNotBlank();
        JsonNode metadata = objectMapper.readTree(metadataJson);
        assertThat(metadata.path("userId").asLong()).isEqualTo(login.userId);
        assertThat(metadata.path("showtimeId").asLong()).isEqualTo(showtimeId);
        assertThat(metadata.path("seatIds").get(0).asLong()).isEqualTo(seatIds.get(0));
        assertThat(metadata.path("seatIds").get(1).asLong()).isEqualTo(seatIds.get(1));
        assertThat(metadata.path("expireTime").asText()).isEqualTo(expireTime);
        assertRedisExpirationsAreAligned(lockToken);
        assertDatabaseSeatStatuses(0);

        JsonNode duringLockSeatMap = getJson("/api/showtimes/" + showtimeId + "/seats", loginToken);
        assertSeatStatuses(duringLockSeatMap, 0);

        JsonNode secondLock = postJson(
                "/api/showtimes/" + showtimeId + "/seats/lock", lockBody, loginToken);
        assertThat(secondLock.path("code").asText()).isEqualTo("OK");
        assertThat(secondLock.path("data").path("lockToken").asText()).isEqualTo(lockToken);
        assertThat(secondLock.path("data").path("expireTime").asText()).isEqualTo(expireTime);

        JsonNode release = postJson(
                "/api/showtimes/" + showtimeId + "/seats/release",
                "{\"lockToken\":\"" + lockToken + "\"}",
                loginToken);
        assertThat(release.path("code").asText()).isEqualTo("OK");
        JsonNode repeatedRelease = postJson(
                "/api/showtimes/" + showtimeId + "/seats/release",
                "{\"lockToken\":\"" + lockToken + "\"}",
                loginToken);
        assertThat(repeatedRelease.path("code").asText()).isEqualTo("OK");

        for (Long seatId : seatIds) {
            assertThat(redisTemplate.opsForValue().get(seatKey(seatId))).isNull();
        }
        assertThat(redisTemplate.opsForValue().get(
                RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + lockToken)).isNull();
        assertDatabaseSeatStatuses(0);

        JsonNode afterReleaseSeatMap = getJson("/api/showtimes/" + showtimeId + "/seats", loginToken);
        assertSeatStatuses(afterReleaseSeatMap, 0);
    }

    @Test
    void twoUsersCompetingForSameSeatsShouldHaveExactlyOneWinner() throws Exception {
        LoginSession userA = login("13800000002", "123456");
        LoginSession userB = login("13800000003", "234567");
        String body = "{\"seatIds\":[" + seatIds.get(0) + "," + seatIds.get(1) + "]}";
        String path = "/api/showtimes/" + showtimeId + "/seats/lock";
        assertDatabaseSeatStatuses(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ResponseEntity<String>> first = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return exchangePost(path, body, userA.token);
            });
            Future<ResponseEntity<String>> second = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return exchangePost(path, body, userB.token);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            ResponseEntity<String> responseA = first.get(10, TimeUnit.SECONDS);
            ResponseEntity<String> responseB = second.get(10, TimeUnit.SECONDS);
            List<Integer> statuses = new ArrayList<>();
            statuses.add(responseA.getStatusCodeValue());
            statuses.add(responseB.getStatusCodeValue());
            Collections.sort(statuses);
            assertThat(statuses).containsExactly(200, 409);

            ResponseEntity<String> winnerResponse = responseA.getStatusCodeValue() == 200
                    ? responseA : responseB;
            ResponseEntity<String> loserResponse = responseA.getStatusCodeValue() == 409
                    ? responseA : responseB;
            LoginSession winner = responseA.getStatusCodeValue() == 200 ? userA : userB;
            JsonNode winnerJson = objectMapper.readTree(winnerResponse.getBody());
            JsonNode loserJson = objectMapper.readTree(loserResponse.getBody());
            lockToken = winnerJson.path("data").path("lockToken").asText();
            lockTokens.add(lockToken);
            assertThat(loserJson.path("code").asText()).isEqualTo("SEAT_ALREADY_LOCKED");

            for (Long seatId : seatIds) {
                assertThat(redisTemplate.opsForValue().get(seatKey(seatId))).isEqualTo(lockToken);
            }
            Set<String> tokenKeys = redisTemplate.keys(RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + "*");
            assertThat(tokenKeys).containsExactly(RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + lockToken);
            JsonNode metadata = objectMapper.readTree(redisTemplate.opsForValue().get(
                    RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + lockToken));
            assertThat(metadata.path("userId").asLong()).isEqualTo(winner.userId);
            assertDatabaseSeatStatuses(0);

            JsonNode seatMap = getJson("/api/showtimes/" + showtimeId + "/seats", winner.token);
            assertSeatStatuses(seatMap, 0);
            postJson("/api/showtimes/" + showtimeId + "/seats/release",
                    "{\"lockToken\":\"" + lockToken + "\"}", winner.token);
            assertDatabaseSeatStatuses(0);
            for (Long seatId : seatIds) {
                assertThat(redisTemplate.opsForValue().get(seatKey(seatId))).isNull();
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private JsonNode getJson(String path, String token) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                url(path), HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        return objectMapper.readTree(response.getBody());
    }

    private JsonNode postJson(String path, String body, String token) throws Exception {
        ResponseEntity<String> response = exchangePost(path, body, token);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        return objectMapper.readTree(response.getBody());
    }

    private ResponseEntity<String> exchangePost(String path, String body, String token) {
        return restTemplate.exchange(
                url(path), HttpMethod.POST, new HttpEntity<>(body, headers(token)), String.class);
    }

    private LoginSession login(String phone, String code) throws Exception {
        loginPhones.add(phone);
        redisTemplate.opsForValue().set(
                RedisConstants.LOGIN_CODE_KEY + phone,
                code,
                5,
                TimeUnit.MINUTES
        );
        JsonNode response = postJson("/api/user/login",
                "{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}", null);
        assertThat(response.path("code").asText()).isEqualTo("OK");
        String token = response.path("data").path("token").asText();
        long userId = response.path("data").path("user").path("id").asLong();
        assertThat(token).isNotBlank();
        assertThat(userId).isPositive();
        loginTokens.add(token);
        return new LoginSession(token, userId);
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

    private void assertRedisExpirationsAreAligned(String token) {
        List<String> keys = new ArrayList<>();
        for (Long seatId : seatIds) {
            keys.add(seatKey(seatId));
        }
        keys.add(RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + token);

        List<Long> expireAtMillis = new ArrayList<>();
        for (String key : keys) {
            Long ttlMillis = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
            assertThat(ttlMillis).isNotNull().isPositive();
            expireAtMillis.add(System.currentTimeMillis() + ttlMillis);
        }
        long earliest = Collections.min(expireAtMillis);
        long latest = Collections.max(expireAtMillis);
        assertThat(latest - earliest).isLessThan(1_000L);
    }

    private void assertDatabaseSeatStatuses(int expectedStatus) {
        List<ShowtimeSeat> states = showtimeSeatMapper.selectList(
                new LambdaQueryWrapper<ShowtimeSeat>()
                        .eq(ShowtimeSeat::getShowtimeId, showtimeId)
                        .in(ShowtimeSeat::getSeatId, seatIds)
        );
        assertThat(states).hasSize(seatIds.size());
        assertThat(states).allMatch(state -> state.getStatus() == expectedStatus);
    }

    private void assertSeatStatuses(JsonNode seatMap, int expectedStatus) {
        JsonNode seats = seatMap.path("data").path("seats");
        for (Long seatId : seatIds) {
            JsonNode matchingSeat = null;
            for (JsonNode seat : seats) {
                if (seat.path("seatId").asLong() == seatId) {
                    matchingSeat = seat;
                    break;
                }
            }
            assertThat(matchingSeat).isNotNull();
            assertThat(matchingSeat.path("status").asInt()).isEqualTo(expectedStatus);
        }
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
