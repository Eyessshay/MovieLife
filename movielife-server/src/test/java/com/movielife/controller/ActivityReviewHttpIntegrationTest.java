package com.movielife.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.constants.RedisConstants;
import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Review;
import com.movielife.entity.Showtime;
import com.movielife.entity.User;
import com.movielife.entity.Venue;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.ReviewMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.UserMapper;
import com.movielife.mapper.VenueMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "movielife.startup-check.enabled=false"
)
class ActivityReviewHttpIntegrationTest {

    private static final String FREEZE_CITY = "STEP136HTTP";
    private static final String FREEZE_GEO_KEY =
            RedisConstants.VENUE_GEO_KEY + FREEZE_CITY + ":THEATER";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserMapper userMapper;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private ReviewMapper reviewMapper;
    @Autowired private VenueMapper venueMapper;
    @Autowired private HallMapper hallMapper;
    @Autowired private ShowtimeMapper showtimeMapper;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> activityIds = new ArrayList<>();
    private final List<Long> venueIds = new ArrayList<>();
    private final List<Long> hallIds = new ArrayList<>();
    private final List<Long> showtimeIds = new ArrayList<>();
    private final List<Long> reviewIds = new ArrayList<>();
    private final List<String> loginTokens = new ArrayList<>();

    @Test
    void stageThirteenHttpMainChainShouldFreezeReviewAndTicketingBoundaries() throws Exception {
        BoundarySnapshot before = boundarySnapshot();

        try {
            long userAId = insertUser("Step136UserA");
            long userBId = insertUser("Step136UserB");
            String userAToken = createLoginToken(userAId);
            String userBToken = createLoginToken(userBId);
            long activityId = insertActivity();
            insertNearbyFixture(activityId);

            assertAllActivityProjections(activityId, "0.00", 0);

            long reviewAId = createReview(activityId, "4.5", "用户 A 的 4.5 分评价",
                    userAToken);
            assertAggregate(activityId, "4.50", 1);
            long reviewBId = createReview(activityId, "3.5", "用户 B 的 3.5 分评价",
                    userBToken);
            assertAllActivityProjections(activityId, "4.00", 2);

            JsonNode list = assertOk(get(
                    "/api/reviews?targetType=ACTIVITY&targetId=" + activityId
                            + "&sortBy=latest&page=1&pageSize=20", null)).path("data");
            assertThat(list.path("total").asLong()).isEqualTo(2L);
            assertThat(list.path("list")).hasSize(2)
                    .allSatisfy(review -> {
                        assertThat(review.path("targetType").asText()).isEqualTo("ACTIVITY");
                        assertThat(review.path("targetId").asLong()).isEqualTo(activityId);
                        assertThat(review.path("liked").asBoolean()).isFalse();
                    });
            JsonNode detail = assertOk(get("/api/reviews/" + reviewAId, null)).path("data");
            assertThat(detail.path("id").asLong()).isEqualTo(reviewAId);
            assertThat(detail.path("score").decimalValue()).isEqualByComparingTo("4.5");

            JsonNode updated = assertOk(exchange(HttpMethod.PUT, "/api/reviews/" + reviewAId,
                    "{\"score\":5.0,\"content\":\"用户 A 修改为 5.0\",\"images\":[]}",
                    userAToken)).path("data");
            assertThat(updated.path("score").decimalValue()).isEqualByComparingTo("5.0");
            assertAllActivityProjections(activityId, "4.25", 2);

            String likeKey = RedisConstants.REVIEW_LIKE_KEY + reviewAId;
            JsonNode liked = assertOk(exchange(HttpMethod.PUT,
                    "/api/reviews/" + reviewAId + "/like", null, userBToken)).path("data");
            assertThat(liked.path("liked").asBoolean()).isTrue();
            assertThat(liked.path("likeCount").asInt()).isEqualTo(1);
            assertThat(redisTemplate.opsForZSet().score(likeKey, String.valueOf(userBId)))
                    .isNotNull();
            JsonNode unliked = assertOk(exchange(HttpMethod.DELETE,
                    "/api/reviews/" + reviewAId + "/like", null, userBToken)).path("data");
            assertThat(unliked.path("liked").asBoolean()).isFalse();
            assertThat(unliked.path("likeCount").asInt()).isZero();
            assertThat(redisTemplate.opsForZSet().score(likeKey, String.valueOf(userBId)))
                    .isNull();

            assertOk(exchange(HttpMethod.DELETE, "/api/reviews/" + reviewBId,
                    null, userBToken));
            assertAllActivityProjections(activityId, "5.00", 1);

            long restoredReviewBId = createReview(activityId, "3.5",
                    "用户 B 恢复 3.5 分评价", userBToken);
            assertThat(restoredReviewBId).isEqualTo(reviewBId);
            assertAggregate(activityId, "4.25", 2);

            Activity offline = new Activity();
            offline.setId(activityId);
            offline.setStatus(2);
            assertThat(activityMapper.updateById(offline)).isEqualTo(1);

            assertActivityUnavailableAfterOffline(activityId);
            JsonNode offlineDetail = assertOk(get("/api/reviews/" + reviewBId, null))
                    .path("data");
            assertThat(offlineDetail.path("id").asLong()).isEqualTo(reviewBId);

            assertOk(exchange(HttpMethod.PUT, "/api/reviews/" + reviewAId,
                    "{\"score\":5.0,\"content\":\"下线后仍可修改\",\"images\":[]}",
                    userAToken));
            assertOk(exchange(HttpMethod.DELETE, "/api/reviews/" + reviewBId,
                    null, userBToken));
            assertAggregate(activityId, "5.00", 1);
            assertError(exchange(HttpMethod.POST, "/api/reviews",
                            reviewBody(activityId, "3.5", "下线后不得恢复"), userBToken),
                    HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND");

            Review reviewA = reviewMapper.selectById(reviewAId);
            Review reviewB = reviewMapper.selectById(reviewBId);
            assertThat(reviewA.getStatus()).isEqualTo(1);
            assertThat(reviewA.getScore()).isEqualByComparingTo("5.0");
            assertThat(reviewA.getLikeCount()).isZero();
            assertThat(reviewB.getStatus()).isZero();
            assertThat(redisTemplate.hasKey(likeKey)).isFalse();
            assertThat(redisTemplate.hasKey(RedisConstants.REVIEW_LIKE_KEY + reviewBId)).isFalse();
        } finally {
            cleanupFixtures();
        }

        assertBoundarySnapshot(before);
    }

    private void assertAllActivityProjections(long activityId, String avgScore, int scoreCount)
            throws Exception {
        JsonNode listData = assertOk(get(
                "/api/activities?city=" + FREEZE_CITY + "&page=1&pageSize=20", null))
                .path("data").path("list");
        assertScore(findById(listData, activityId), avgScore, scoreCount);

        JsonNode nearbyData = assertOk(get(
                "/api/activities/nearby?city=" + FREEZE_CITY
                        + "&venueType=THEATER&x=121.47&y=31.23"
                        + "&distance=5000&page=1&pageSize=20", null)).path("data");
        assertScore(findById(nearbyData, activityId), avgScore, scoreCount);

        JsonNode detail = assertOk(get("/api/activities/" + activityId, null)).path("data");
        assertThat(detail.path("id").asLong()).isEqualTo(activityId);
        assertScore(detail, avgScore, scoreCount);
    }

    private void assertActivityUnavailableAfterOffline(long activityId) throws Exception {
        JsonNode list = assertOk(get(
                "/api/activities?city=" + FREEZE_CITY + "&page=1&pageSize=20", null))
                .path("data").path("list");
        assertThat(list).noneSatisfy(item ->
                assertThat(item.path("id").asLong()).isEqualTo(activityId));

        JsonNode nearby = assertOk(get(
                "/api/activities/nearby?city=" + FREEZE_CITY
                        + "&venueType=THEATER&x=121.47&y=31.23"
                        + "&distance=5000&page=1&pageSize=20", null)).path("data");
        assertThat(nearby).noneSatisfy(item ->
                assertThat(item.path("id").asLong()).isEqualTo(activityId));

        assertError(get("/api/activities/" + activityId, null),
                HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND");
        assertError(get("/api/reviews?targetType=ACTIVITY&targetId=" + activityId, null),
                HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND");
    }

    private long createReview(long activityId, String score, String content, String token)
            throws Exception {
        JsonNode data = assertOk(exchange(HttpMethod.POST, "/api/reviews",
                reviewBody(activityId, score, content), token)).path("data");
        long reviewId = data.path("id").asLong();
        assertThat(reviewId).isPositive();
        if (!reviewIds.contains(reviewId)) {
            reviewIds.add(reviewId);
        }
        return reviewId;
    }

    private String reviewBody(long activityId, String score, String content) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetType", "ACTIVITY");
        body.put("targetId", activityId);
        body.put("score", new BigDecimal(score));
        body.put("content", content);
        body.put("images", Collections.emptyList());
        return objectMapper.writeValueAsString(body);
    }

    private void assertAggregate(long activityId, String avgScore, int scoreCount) {
        Activity activity = activityMapper.selectById(activityId);
        assertThat(activity.getAvgScore()).isEqualByComparingTo(avgScore);
        assertThat(activity.getScoreCount()).isEqualTo(scoreCount);
    }

    private void assertScore(JsonNode activity, String avgScore, int scoreCount) {
        assertThat(activity).isNotNull();
        assertThat(activity.path("avgScore").decimalValue()).isEqualByComparingTo(avgScore);
        assertThat(activity.path("scoreCount").asInt()).isEqualTo(scoreCount);
    }

    private JsonNode findById(JsonNode items, long id) {
        for (JsonNode item : items) {
            if (item.path("id").asLong() == id) {
                return item;
            }
        }
        return null;
    }

    private ResponseEntity<String> get(String path, String token) {
        return exchange(HttpMethod.GET, path, null, token);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path,
                                            String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode assertOk(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo("OK");
        return body;
    }

    private void assertError(ResponseEntity<String> response,
                             HttpStatus status,
                             String code) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(objectMapper.readTree(response.getBody()).path("code").asText()).isEqualTo(code);
    }

    private long insertUser(String nickName) {
        User user = new User();
        user.setPhone(uniquePhone());
        user.setNickName(nickName);
        user.setGender(0);
        user.setCity(FREEZE_CITY);
        assertThat(userMapper.insert(user)).isEqualTo(1);
        userIds.add(user.getId());
        return user.getId();
    }

    private long insertActivity() {
        Activity activity = new Activity();
        activity.setTitle("Step136ActivityReview冻结活动");
        activity.setCoverUrl("https://example.com/step136.jpg");
        activity.setType("drama");
        activity.setCity(FREEZE_CITY);
        activity.setDescription("Step 13.6 Activity Review 真实 HTTP 冻结证据");
        activity.setPriceMin(new BigDecimal("99.00"));
        activity.setPriceMax(new BigDecimal("299.00"));
        activity.setAvgScore(new BigDecimal("0.00"));
        activity.setScoreCount(0);
        activity.setStatus(1);
        assertThat(activityMapper.insert(activity)).isEqualTo(1);
        activityIds.add(activity.getId());
        return activity.getId();
    }

    private void insertNearbyFixture(long activityId) {
        Venue venue = new Venue();
        venue.setName("Step136ActivityReview剧场");
        venue.setType("theater");
        venue.setCity(FREEZE_CITY);
        venue.setAddress("Step 13.6 HTTP 测试地址");
        venue.setLongitude(new BigDecimal("121.471000"));
        venue.setLatitude(new BigDecimal("31.230000"));
        venue.setAvgScore(BigDecimal.ZERO);
        venue.setStatus(1);
        assertThat(venueMapper.insert(venue)).isEqualTo(1);
        venueIds.add(venue.getId());

        Hall hall = new Hall();
        hall.setVenueId(venue.getId());
        hall.setName("Step136ActivityReview厅");
        assertThat(hallMapper.insert(hall)).isEqualTo(1);
        hallIds.add(hall.getId());

        Showtime showtime = new Showtime();
        showtime.setBizType("ACTIVITY");
        showtime.setBizId(activityId);
        showtime.setVenueId(venue.getId());
        showtime.setHallId(hall.getId());
        showtime.setStartTime(LocalDateTime.now().plusDays(2).withSecond(0).withNano(0));
        showtime.setEndTime(showtime.getStartTime().plusHours(2));
        showtime.setBasePrice(new BigDecimal("99.00"));
        showtime.setTotalSeats(100);
        showtime.setAvailableSeats(40);
        showtime.setStatus(1);
        assertThat(showtimeMapper.insert(showtime)).isEqualTo(1);
        showtimeIds.add(showtime.getId());

        redisTemplate.opsForGeo().add(FREEZE_GEO_KEY,
                new Point(121.471000D, 31.230000D), venue.getId().toString());
    }

    private String createLoginToken(long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(RedisConstants.LOGIN_TOKEN_KEY + token,
                String.valueOf(userId), RedisConstants.LOGIN_TOKEN_TTL);
        loginTokens.add(token);
        return token;
    }

    private String uniquePhone() {
        long suffix = Math.floorMod(System.nanoTime(), 100_000_000L);
        return String.format("139%08d", suffix);
    }

    private BoundarySnapshot boundarySnapshot() {
        return new BoundarySnapshot(
                table("tb_showtime"),
                table("tb_showtime_seat"),
                table("tb_ticket_order"),
                table("tb_ticket_order_item"),
                geoSnapshot(),
                stringKeySnapshot(RedisConstants.SEAT_LOCK_KEY_PREFIX + "*"),
                stringKeySnapshot(RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + "*")
        );
    }

    private List<Map<String, Object>> table(String tableName) {
        return jdbcTemplate.queryForList("SELECT * FROM " + tableName + " ORDER BY id");
    }

    private Map<String, Set<ZSetOperations.TypedTuple<String>>> geoSnapshot() {
        Map<String, Set<ZSetOperations.TypedTuple<String>>> result = new LinkedHashMap<>();
        Set<String> keys = redisTemplate.keys(RedisConstants.VENUE_GEO_KEY + "*");
        if (keys == null) {
            return result;
        }
        keys.stream().sorted().forEach(key -> {
            Set<ZSetOperations.TypedTuple<String>> members =
                    redisTemplate.opsForZSet().rangeWithScores(key, 0, -1);
            result.put(key, members == null ? new LinkedHashSet<>() : new LinkedHashSet<>(members));
        });
        return result;
    }

    private Map<String, String> stringKeySnapshot(String pattern) {
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys == null) {
            return result;
        }
        keys.stream().sorted().forEach(key -> result.put(key, redisTemplate.opsForValue().get(key)));
        return result;
    }

    private void assertBoundarySnapshot(BoundarySnapshot expected) {
        BoundarySnapshot actual = boundarySnapshot();
        assertThat(actual.showtimes).isEqualTo(expected.showtimes);
        assertThat(actual.showtimeSeats).isEqualTo(expected.showtimeSeats);
        assertThat(actual.ticketOrders).isEqualTo(expected.ticketOrders);
        assertThat(actual.ticketOrderItems).isEqualTo(expected.ticketOrderItems);
        assertThat(actual.venueGeo).isEqualTo(expected.venueGeo);
        assertThat(actual.seatLocks).isEqualTo(expected.seatLocks);
        assertThat(actual.seatLockTokens).isEqualTo(expected.seatLockTokens);
    }

    private void cleanupFixtures() {
        reviewIds.forEach(id -> redisTemplate.delete(RedisConstants.REVIEW_LIKE_KEY + id));
        if (!activityIds.isEmpty()) {
            reviewMapper.delete(new LambdaQueryWrapper<Review>()
                    .eq(Review::getTargetType, "ACTIVITY")
                    .in(Review::getTargetId, activityIds));
        }
        showtimeIds.forEach(showtimeMapper::deleteById);
        hallIds.forEach(hallMapper::deleteById);
        if (!venueIds.isEmpty()) {
            venueIds.forEach(id -> redisTemplate.opsForZSet().remove(FREEZE_GEO_KEY, id.toString()));
        }
        venueIds.forEach(venueMapper::deleteById);
        activityIds.forEach(activityMapper::deleteById);
        userIds.forEach(userMapper::deleteById);
        loginTokens.forEach(token -> redisTemplate.delete(RedisConstants.LOGIN_TOKEN_KEY + token));
        if (Boolean.FALSE.equals(redisTemplate.hasKey(FREEZE_GEO_KEY))) {
            redisTemplate.delete(FREEZE_GEO_KEY);
        }
    }

    private static final class BoundarySnapshot {
        private final List<Map<String, Object>> showtimes;
        private final List<Map<String, Object>> showtimeSeats;
        private final List<Map<String, Object>> ticketOrders;
        private final List<Map<String, Object>> ticketOrderItems;
        private final Map<String, Set<ZSetOperations.TypedTuple<String>>> venueGeo;
        private final Map<String, String> seatLocks;
        private final Map<String, String> seatLockTokens;

        private BoundarySnapshot(
                List<Map<String, Object>> showtimes,
                List<Map<String, Object>> showtimeSeats,
                List<Map<String, Object>> ticketOrders,
                List<Map<String, Object>> ticketOrderItems,
                Map<String, Set<ZSetOperations.TypedTuple<String>>> venueGeo,
                Map<String, String> seatLocks,
                Map<String, String> seatLockTokens) {
            this.showtimes = showtimes;
            this.showtimeSeats = showtimeSeats;
            this.ticketOrders = ticketOrders;
            this.ticketOrderItems = ticketOrderItems;
            this.venueGeo = venueGeo;
            this.seatLocks = seatLocks;
            this.seatLockTokens = seatLockTokens;
        }
    }
}
