package com.movielife.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.constants.RedisConstants;
import com.movielife.entity.Activity;
import com.movielife.entity.Collection;
import com.movielife.entity.Showtime;
import com.movielife.entity.ShowtimeSeat;
import com.movielife.entity.TicketOrder;
import com.movielife.entity.User;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.CollectionMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.TicketOrderItemMapper;
import com.movielife.mapper.TicketOrderMapper;
import com.movielife.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
class ActivityCollectionHttpIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private UserMapper userMapper;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private CollectionMapper collectionMapper;
    @Autowired private ShowtimeMapper showtimeMapper;
    @Autowired private ShowtimeSeatMapper showtimeSeatMapper;
    @Autowired private TicketOrderMapper ticketOrderMapper;
    @Autowired private TicketOrderItemMapper ticketOrderItemMapper;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> activityIds = new ArrayList<>();
    private final List<String> loginTokens = new ArrayList<>();

    private Long ownerId;
    private Long otherUserId;
    private Long activeActivityId;
    private Long draftActivityId;
    private Long offlineActivityId;
    private String ownerToken;
    private String otherUserToken;

    @BeforeEach
    void setUp() {
        ownerId = insertUser("Step121Owner");
        otherUserId = insertUser("Step121Other");
        ownerToken = createLoginToken(ownerId);
        otherUserToken = createLoginToken(otherUserId);
        activeActivityId = insertActivity("Step121在售活动", 1);
        draftActivityId = insertActivity("Step121草稿活动", 0);
        offlineActivityId = insertActivity("Step121下线活动", 2);
    }

    @AfterEach
    void tearDown() {
        if (!userIds.isEmpty()) {
            collectionMapper.delete(new LambdaQueryWrapper<Collection>()
                    .in(Collection::getUserId, userIds));
        }
        activityIds.forEach(activityMapper::deleteById);
        userIds.forEach(userMapper::deleteById);
        loginTokens.forEach(token -> redisTemplate.delete(RedisConstants.LOGIN_TOKEN_KEY + token));
    }

    @Test
    void activityCollectionHttpShouldBeIdempotentIsolatedAndLeaveOtherFactsUntouched()
            throws Exception {
        Activity activityBefore = activityMapper.selectById(activeActivityId);
        List<Showtime> showtimesBefore = showtimeMapper.selectList(null);
        Long showtimeSeatCountBefore = showtimeSeatMapper.selectCount(null);
        Long orderCountBefore = ticketOrderMapper.selectCount(null);
        Long orderItemCountBefore = ticketOrderItemMapper.selectCount(null);
        Set<String> seatLocksBefore = redisTemplate.keys(RedisConstants.SEAT_LOCK_KEY_PREFIX + "*");
        Set<String> tokenLocksBefore = redisTemplate.keys(
                RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + "*");
        Map<String, Set<ZSetOperations.TypedTuple<String>>> geoBefore = loadGeoMembers();

        assertOk(exchange(HttpMethod.PUT, activityPath(activeActivityId), ownerToken));
        assertOk(exchange(HttpMethod.PUT, activityPath(activeActivityId), ownerToken));
        assertThat(collectionCount(ownerId, activeActivityId)).isEqualTo(1L);
        assertCollected(ownerToken, activeActivityId, true);
        assertCollected(otherUserToken, activeActivityId, false);

        assertOk(exchange(HttpMethod.DELETE, activityPath(activeActivityId), ownerToken));
        assertOk(exchange(HttpMethod.DELETE, activityPath(activeActivityId), ownerToken));
        assertThat(collectionCount(ownerId, activeActivityId)).isZero();
        assertCollected(ownerToken, activeActivityId, false);

        assertThat(activityMapper.selectById(activeActivityId)).isEqualTo(activityBefore);
        assertThat(showtimeMapper.selectList(null)).isEqualTo(showtimesBefore);
        assertThat(showtimeSeatMapper.selectCount(null)).isEqualTo(showtimeSeatCountBefore);
        assertThat(ticketOrderMapper.selectCount(null)).isEqualTo(orderCountBefore);
        assertThat(ticketOrderItemMapper.selectCount(null)).isEqualTo(orderItemCountBefore);
        assertThat(redisTemplate.keys(RedisConstants.SEAT_LOCK_KEY_PREFIX + "*"))
                .isEqualTo(seatLocksBefore);
        assertThat(redisTemplate.keys(RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + "*"))
                .isEqualTo(tokenLocksBefore);
        assertThat(loadGeoMembers()).isEqualTo(geoBefore);
    }

    @Test
    void concurrentActivityCollectionShouldPersistOneRelation() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ResponseEntity<String>> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return exchange(HttpMethod.PUT, activityPath(activeActivityId), ownerToken);
            });
            Future<ResponseEntity<String>> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return exchange(HttpMethod.PUT, activityPath(activeActivityId), ownerToken);
            });
            ready.await();
            start.countDown();

            assertOk(first.get());
            assertOk(second.get());
            assertThat(collectionCount(ownerId, activeActivityId)).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void missingDraftOfflineAndInvalidActivityCollectionShouldBeRejected() throws Exception {
        assertError(exchange(HttpMethod.PUT, activityPath(Long.MAX_VALUE), ownerToken),
                HttpStatus.NOT_FOUND, "COLLECTION_TARGET_NOT_FOUND");
        assertError(exchange(HttpMethod.PUT, activityPath(draftActivityId), ownerToken),
                HttpStatus.NOT_FOUND, "COLLECTION_TARGET_NOT_FOUND");
        assertError(exchange(HttpMethod.PUT, activityPath(offlineActivityId), ownerToken),
                HttpStatus.NOT_FOUND, "COLLECTION_TARGET_NOT_FOUND");
        assertError(exchange(HttpMethod.PUT, "/api/collections/ACTIVITY/0", ownerToken),
                HttpStatus.BAD_REQUEST, "INVALID_ID");
        assertError(exchange(HttpMethod.PUT, "/api/collections/VENUE/" + activeActivityId, ownerToken),
                HttpStatus.BAD_REQUEST, "INVALID_COLLECTION_TARGET_TYPE");
        assertError(exchange(HttpMethod.GET,
                        "/api/collections/check?targetType=ACTIVITY&targetId=" + activeActivityId,
                        null),
                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        assertThat(collectionMapper.selectCount(new LambdaQueryWrapper<Collection>()
                .eq(Collection::getUserId, ownerId))).isZero();
    }

    @Test
    void offlineActivityShouldStillAllowRelationCheckAndRemoval() throws Exception {
        assertOk(exchange(HttpMethod.PUT, activityPath(activeActivityId), ownerToken));
        Activity offline = new Activity();
        offline.setId(activeActivityId);
        offline.setStatus(2);
        assertThat(activityMapper.updateById(offline)).isEqualTo(1);

        assertCollected(ownerToken, activeActivityId, true);
        assertOk(exchange(HttpMethod.DELETE, activityPath(activeActivityId), ownerToken));
        assertCollected(ownerToken, activeActivityId, false);
        assertThat(collectionCount(ownerId, activeActivityId)).isZero();
    }

    @Test
    void activityCollectionListShouldFilterBeforeStablePagingAndNotDeleteRelations()
            throws Exception {
        Long secondVisibleId = insertActivity("Step122有效活动B", 1);
        Long thirdVisibleId = insertActivity("Step122有效活动C", 1);
        LocalDateTime collectedAt = LocalDateTime.of(2026, 9, 14, 12, 22, 0);

        insertCollection(ownerId, activeActivityId, collectedAt);
        insertCollection(ownerId, draftActivityId, collectedAt);
        insertCollection(ownerId, secondVisibleId, collectedAt);
        insertCollection(ownerId, Long.MAX_VALUE, collectedAt);
        insertCollection(ownerId, offlineActivityId, collectedAt);
        insertCollection(ownerId, thirdVisibleId, collectedAt);
        insertCollection(otherUserId, thirdVisibleId, collectedAt.plusSeconds(1));

        Long relationCountBefore = collectionMapper.selectCount(new LambdaQueryWrapper<Collection>()
                .in(Collection::getUserId, userIds)
                .eq(Collection::getTargetType, "ACTIVITY"));
        List<Activity> activitiesBefore = activityMapper.selectBatchIds(activityIds);
        Set<String> redisKeysBefore = redisTemplate.keys("*");

        JsonNode firstPage = assertActivityList(ownerToken, 1, 2, 3L);
        assertThat(firstPage.path("list")).hasSize(2);
        assertThat(firstPage.path("list").get(0).path("targetId").asLong())
                .isEqualTo(thirdVisibleId);
        assertThat(firstPage.path("list").get(1).path("targetId").asLong())
                .isEqualTo(secondVisibleId);
        assertThat(firstPage.path("list").get(0).path("target").path("title").asText())
                .isEqualTo("Step122有效活动C");
        assertThat(firstPage.path("list").get(0).path("target").path("priceMin").decimalValue())
                .isEqualByComparingTo("99.00");
        assertThat(firstPage.path("list").get(0).path("collectTime").asText()).isNotBlank();

        JsonNode secondPage = assertActivityList(ownerToken, 2, 2, 3L);
        assertThat(secondPage.path("list")).hasSize(1);
        assertThat(secondPage.path("list").get(0).path("targetId").asLong())
                .isEqualTo(activeActivityId);

        JsonNode otherUserPage = assertActivityList(otherUserToken, 1, 20, 1L);
        assertThat(otherUserPage.path("list")).hasSize(1);
        assertThat(otherUserPage.path("list").get(0).path("targetId").asLong())
                .isEqualTo(thirdVisibleId);

        assertThat(collectionMapper.selectCount(new LambdaQueryWrapper<Collection>()
                .in(Collection::getUserId, userIds)
                .eq(Collection::getTargetType, "ACTIVITY"))).isEqualTo(relationCountBefore);
        assertThat(activityMapper.selectBatchIds(activityIds)).containsExactlyInAnyOrderElementsOf(
                activitiesBefore);
        assertThat(redisTemplate.keys("*")).isEqualTo(redisKeysBefore);
    }

    @Test
    void activityCollectionListShouldRequireLogin() throws Exception {
        assertError(exchange(HttpMethod.GET,
                        "/api/collections?targetType=ACTIVITY&page=1&pageSize=20", null),
                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    }

    @Test
    void ordinaryActivityListAndDetailShouldExposeIsolatedCollectionState()
            throws Exception {
        String listPath = "/api/activities?city=Step121City&page=1&pageSize=20";
        String detailPath = "/api/activities/" + activeActivityId;

        assertActivityCollected(listPath, null, true, false);
        assertActivityCollected(detailPath, null, false, false);
        assertActivityCollected(listPath, ownerToken, true, false);
        assertActivityCollected(detailPath, ownerToken, false, false);

        assertOk(exchange(HttpMethod.PUT, activityPath(activeActivityId), ownerToken));

        assertActivityCollected(listPath, ownerToken, true, true);
        assertActivityCollected(detailPath, ownerToken, false, true);
        assertActivityCollected(listPath, otherUserToken, true, false);
        assertActivityCollected(detailPath, otherUserToken, false, false);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(headers), String.class);
    }

    private String activityPath(Long activityId) {
        return "/api/collections/ACTIVITY/" + activityId;
    }

    private void assertCollected(String token, Long activityId, boolean expected) throws Exception {
        ResponseEntity<String> response = exchange(HttpMethod.GET,
                "/api/collections/check?targetType=ACTIVITY&targetId=" + activityId, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo("OK");
        assertThat(body.path("data").path("collected").asBoolean()).isEqualTo(expected);
    }

    private void assertOk(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody()).path("code").asText()).isEqualTo("OK");
    }

    private void assertError(ResponseEntity<String> response,
                             HttpStatus status,
                             String code) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(objectMapper.readTree(response.getBody()).path("code").asText()).isEqualTo(code);
    }

    private Long collectionCount(Long userId, Long activityId) {
        return collectionMapper.selectCount(new LambdaQueryWrapper<Collection>()
                .eq(Collection::getUserId, userId)
                .eq(Collection::getTargetType, "ACTIVITY")
                .eq(Collection::getTargetId, activityId));
    }

    private JsonNode assertActivityList(String token, int page, int pageSize, long total)
            throws Exception {
        ResponseEntity<String> response = exchange(HttpMethod.GET,
                "/api/collections?targetType=ACTIVITY&page=" + page + "&pageSize=" + pageSize,
                token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo("OK");
        JsonNode data = body.path("data");
        assertThat(data.path("total").asLong()).isEqualTo(total);
        assertThat(data.path("page").asInt()).isEqualTo(page);
        assertThat(data.path("pageSize").asInt()).isEqualTo(pageSize);
        return data;
    }

    private void assertActivityCollected(String path,
                                         String token,
                                         boolean listResponse,
                                         boolean expected) throws Exception {
        ResponseEntity<String> response = exchange(HttpMethod.GET, path, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo("OK");
        JsonNode activity = listResponse
                ? body.path("data").path("list").get(0)
                : body.path("data");
        assertThat(activity.path("id").asLong()).isEqualTo(activeActivityId);
        assertThat(activity.path("collected").asBoolean()).isEqualTo(expected);
    }

    private Long insertCollection(Long userId, Long activityId, LocalDateTime createTime) {
        Collection collection = new Collection();
        collection.setUserId(userId);
        collection.setTargetType("ACTIVITY");
        collection.setTargetId(activityId);
        collection.setCreateTime(createTime);
        assertThat(collectionMapper.insert(collection)).isEqualTo(1);
        return collection.getId();
    }

    private Long insertUser(String nickName) {
        User user = new User();
        user.setPhone(uniquePhone());
        user.setNickName(nickName);
        user.setGender(0);
        user.setCity("Step121City");
        assertThat(userMapper.insert(user)).isEqualTo(1);
        userIds.add(user.getId());
        return user.getId();
    }

    private Long insertActivity(String title, int status) {
        Activity activity = new Activity();
        activity.setTitle(title);
        activity.setCoverUrl("https://example.com/step121.jpg");
        activity.setType("drama");
        activity.setCity("Step121City");
        activity.setDescription("Step 12.1 Activity 收藏真实联调");
        activity.setPriceMin(new BigDecimal("99.00"));
        activity.setPriceMax(new BigDecimal("299.00"));
        activity.setStatus(status);
        assertThat(activityMapper.insert(activity)).isEqualTo(1);
        activityIds.add(activity.getId());
        return activity.getId();
    }

    private String createLoginToken(Long userId) {
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

    private Map<String, Set<ZSetOperations.TypedTuple<String>>> loadGeoMembers() {
        Set<String> keys = redisTemplate.keys(RedisConstants.VENUE_GEO_KEY + "*");
        Map<String, Set<ZSetOperations.TypedTuple<String>>> result = new HashMap<>();
        if (keys == null) {
            return result;
        }
        for (String key : keys) {
            Set<ZSetOperations.TypedTuple<String>> members =
                    redisTemplate.opsForZSet().rangeWithScores(key, 0, -1);
            result.put(key, members == null ? new LinkedHashSet<>() : new LinkedHashSet<>(members));
        }
        return result;
    }
}
