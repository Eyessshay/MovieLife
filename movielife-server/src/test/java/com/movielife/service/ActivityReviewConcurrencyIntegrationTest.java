package com.movielife.service;

import com.movielife.dto.CreateReviewDTO;
import com.movielife.dto.ActivityNearbyQueryDTO;
import com.movielife.dto.ActivityQueryDTO;
import com.movielife.dto.CollectionQueryDTO;
import com.movielife.dto.UpdateReviewDTO;
import com.movielife.dto.UserDTO;
import com.movielife.entity.Activity;
import com.movielife.entity.Review;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.ReviewMapper;
import com.movielife.utils.UserHolder;
import com.movielife.enums.VenueType;
import com.movielife.vo.ActivityDetailVO;
import com.movielife.vo.ActivityListItemVO;
import com.movielife.vo.ActivityNearbyVO;
import com.movielife.vo.CollectionVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "movielife.startup-check.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.redisson.spring.starter.RedissonAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        }
)
class ActivityReviewConcurrencyIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(13_500_000_000L);

    @Autowired private ReviewService reviewService;
    @Autowired private CollectionService collectionService;
    @Autowired private ActivityService activityService;
    @Autowired private ReviewMapper reviewMapper;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockBean private StringRedisTemplate redisTemplate;

    private ZSetOperations<String, String> zSetOperations;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> activityIds = new ArrayList<>();
    private final List<Long> movieIds = new ArrayList<>();
    private final List<Long> venueIds = new ArrayList<>();
    private final List<Long> hallIds = new ArrayList<>();
    private final List<Long> showtimeIds = new ArrayList<>();
    private DatabaseBoundarySnapshot boundaryBefore;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        zSetOperations = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        boundaryBefore = takeDatabaseBoundarySnapshot();
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
        try {
            assertThat(takeDatabaseBoundarySnapshot()).isEqualTo(boundaryBefore);
        } finally {
            if (!showtimeIds.isEmpty()) {
                jdbcTemplate.update("DELETE FROM tb_showtime WHERE id IN ("
                        + placeholders(showtimeIds.size()) + ")", showtimeIds.toArray());
            }
            if (!activityIds.isEmpty()) {
                jdbcTemplate.update("DELETE FROM tb_collection WHERE target_type = 'ACTIVITY' "
                        + "AND target_id IN (" + placeholders(activityIds.size()) + ")", activityIds.toArray());
                jdbcTemplate.update("DELETE FROM tb_review WHERE target_type = 'ACTIVITY' "
                        + "AND target_id IN (" + placeholders(activityIds.size()) + ")", activityIds.toArray());
                jdbcTemplate.update("DELETE FROM tb_activity WHERE id IN ("
                        + placeholders(activityIds.size()) + ")", activityIds.toArray());
            }
            if (!hallIds.isEmpty()) {
                jdbcTemplate.update("DELETE FROM tb_hall WHERE id IN ("
                        + placeholders(hallIds.size()) + ")", hallIds.toArray());
            }
            if (!venueIds.isEmpty()) {
                jdbcTemplate.update("DELETE FROM tb_venue WHERE id IN ("
                        + placeholders(venueIds.size()) + ")", venueIds.toArray());
            }
            if (!movieIds.isEmpty()) {
                jdbcTemplate.update("DELETE FROM tb_review WHERE target_type = 'MOVIE' "
                        + "AND target_id IN (" + placeholders(movieIds.size()) + ")", movieIds.toArray());
                jdbcTemplate.update("DELETE FROM tb_movie WHERE id IN ("
                        + placeholders(movieIds.size()) + ")", movieIds.toArray());
            }
            if (!userIds.isEmpty()) {
                jdbcTemplate.update("DELETE FROM tb_user WHERE id IN ("
                        + placeholders(userIds.size()) + ")", userIds.toArray());
            }
        }
    }

    @Test
    void sameUserConcurrentFirstReviewShouldCreateOneRelationAndAggregateOnce() throws Exception {
        long userId = insertUser("same-user");
        long activityId = insertActivity("same-user-first-review");

        List<Outcome> outcomes = runTogether(
                () -> callAs(userId, () -> reviewService.createReview(create(activityId, "4.5"))),
                () -> callAs(userId, () -> reviewService.createReview(create(activityId, "4.5")))
        );

        assertThat(outcomes).filteredOn(Outcome::isSuccess).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.isSuccess()).hasSize(1);
        Outcome rejected = outcomes.stream().filter(outcome -> !outcome.isSuccess())
                .findFirst().orElseThrow(AssertionError::new);
        assertThat(rejected.error).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) rejected.error).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_REVIEW);
        assertThat(activeReviewCount("ACTIVITY", activityId)).isEqualTo(1);
        assertAggregateMatchesReviews(activityId);
        assertActivityAggregate(activityId, "4.50", 1);
    }

    @Test
    void twoUsersConcurrentReviewsShouldBothCommitWithAccurateAggregate() throws Exception {
        long firstUser = insertUser("two-users-a");
        long secondUser = insertUser("two-users-b");
        long activityId = insertActivity("two-users");

        List<Outcome> outcomes = runTogether(
                () -> callAs(firstUser, () -> reviewService.createReview(create(activityId, "4.5"))),
                () -> callAs(secondUser, () -> reviewService.createReview(create(activityId, "3.5")))
        );

        assertThat(outcomes).allMatch(Outcome::isSuccess);
        assertThat(activeReviewCount("ACTIVITY", activityId)).isEqualTo(2);
        assertAggregateMatchesReviews(activityId);
        assertActivityAggregate(activityId, "4.00", 2);
    }

    @Test
    void updateAndDeleteCompetitionShouldNeverLeavePartialAggregate() throws Exception {
        long userId = insertUser("update-delete");
        long activityId = insertActivity("update-delete");
        Review created = createReview(userId, activityId, "4.0");

        UpdateReviewDTO update = update("5.0");
        List<Outcome> outcomes = runTogether(
                () -> callAs(userId, () -> reviewService.updateReview(created.getId(), update)),
                () -> callAs(userId, () -> {
                    reviewService.deleteReview(created.getId());
                    return null;
                })
        );

        assertThat(outcomes.get(1).isSuccess()).isTrue();
        assertThat(reviewMapper.selectById(created.getId()).getStatus()).isZero();
        assertAggregateMatchesReviews(activityId);
        assertActivityAggregate(activityId, "0.00", 0);
    }

    @Test
    void deleteAndRestoreCompetitionShouldRemainLinearizable() throws Exception {
        long userId = insertUser("delete-restore");
        long activityId = insertActivity("delete-restore");
        Review created = createReview(userId, activityId, "4.0");
        callAs(userId, () -> {
            reviewService.deleteReview(created.getId());
            return null;
        });

        List<Outcome> outcomes = runTogether(
                () -> callAs(userId, () -> reviewService.createReview(create(activityId, "5.0"))),
                () -> callAs(userId, () -> {
                    reviewService.deleteReview(created.getId());
                    return null;
                })
        );

        assertThat(outcomes).allMatch(Outcome::isSuccess);
        assertThat(reviewCountForRelation(userId, "ACTIVITY", activityId)).isEqualTo(1);
        assertAggregateMatchesReviews(activityId);
        Review finalReview = reviewMapper.selectById(created.getId());
        if (finalReview.getStatus() == 1) {
            assertActivityAggregate(activityId, "5.00", 1);
        } else {
            assertThat(finalReview.getStatus()).isZero();
            assertActivityAggregate(activityId, "0.00", 0);
        }
    }

    @Test
    void deletingLastReviewShouldResetAggregateExactly() {
        long userId = insertUser("last-review");
        long activityId = insertActivity("last-review");
        Review created = createReview(userId, activityId, "4.5");

        callAs(userId, () -> {
            reviewService.deleteReview(created.getId());
            return null;
        });

        assertAggregateMatchesReviews(activityId);
        assertActivityAggregate(activityId, "0.00", 0);
    }

    @Test
    void movieAndActivityWithSameNumericIdShouldRemainIndependent() {
        long userId = insertUser("polymorphic-id");
        long sharedId = IDS.incrementAndGet();
        insertActivity(sharedId, "polymorphic-activity");
        insertMovie(sharedId, "polymorphic-movie");

        callAs(userId, () -> reviewService.createReview(create("ACTIVITY", sharedId, "4.5")));
        callAs(userId, () -> reviewService.createReview(create("MOVIE", sharedId, "3.5")));

        assertThat(reviewCountForRelation(userId, "ACTIVITY", sharedId)).isEqualTo(1);
        assertThat(reviewCountForRelation(userId, "MOVIE", sharedId)).isEqualTo(1);
        assertActivityAggregate(sharedId, "4.50", 1);
        Map<String, Object> movie = jdbcTemplate.queryForMap(
                "SELECT avg_score, score_count FROM tb_movie WHERE id = ?", sharedId);
        assertThat((BigDecimal) movie.get("avg_score")).isEqualByComparingTo("3.50");
        assertThat(((Number) movie.get("score_count")).intValue()).isEqualTo(1);
    }

    @Test
    void activityOfflineAndCreateCompetitionShouldFollowCommitOrder() throws Exception {
        long losingUser = insertUser("offline-wins");
        long offlineFirstActivity = insertActivity("offline-first");
        CountDownLatch offlineLocked = new CountDownLatch(1);
        CountDownLatch allowOfflineCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> offline = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbcTemplate.update("UPDATE tb_activity SET status = 2 WHERE id = ?",
                                offlineFirstActivity);
                        offlineLocked.countDown();
                        await(allowOfflineCommit);
                    }));
            assertThat(offlineLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Outcome> create = executor.submit(() -> capture(() -> callAs(losingUser,
                    () -> reviewService.createReview(create(offlineFirstActivity, "4.0")))));
            allowOfflineCommit.countDown();
            offline.get(10, TimeUnit.SECONDS);
            assertThat(create.get(10, TimeUnit.SECONDS).isSuccess()).isFalse();
        } finally {
            allowOfflineCommit.countDown();
            shutdown(executor);
        }
        assertThat(activeReviewCount("ACTIVITY", offlineFirstActivity)).isZero();
        assertActivityAggregate(offlineFirstActivity, "0.00", 0);
        assertThat(activityMapper.selectById(offlineFirstActivity).getStatus()).isEqualTo(2);

        long winningUser = insertUser("create-wins");
        long createFirstActivity = insertActivity("create-first");
        CountDownLatch createFinished = new CountDownLatch(1);
        CountDownLatch allowCreateCommit = new CountDownLatch(1);
        ExecutorService secondExecutor = Executors.newFixedThreadPool(2);
        try {
            Future<?> create = secondExecutor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> callAs(winningUser, () -> {
                        reviewService.createReview(create(createFirstActivity, "4.0"));
                        createFinished.countDown();
                        await(allowCreateCommit);
                        return null;
                    })));
            assertThat(createFinished.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Integer> offline = secondExecutor.submit(() -> jdbcTemplate.update(
                    "UPDATE tb_activity SET status = 2 WHERE id = ?", createFirstActivity));
            allowCreateCommit.countDown();
            create.get(10, TimeUnit.SECONDS);
            assertThat(offline.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            allowCreateCommit.countDown();
            shutdown(secondExecutor);
        }
        assertThat(activeReviewCount("ACTIVITY", createFirstActivity)).isEqualTo(1);
        assertActivityAggregate(createFirstActivity, "4.00", 1);
        assertThat(activityMapper.selectById(createFirstActivity).getStatus()).isEqualTo(2);
    }

    @Test
    void offlineActivityShouldStillAllowOwnerUpdateAndDelete() {
        long userId = insertUser("offline-existing");
        long activityId = insertActivity("offline-existing");
        Review created = createReview(userId, activityId, "4.0");
        jdbcTemplate.update("UPDATE tb_activity SET status = 2 WHERE id = ?", activityId);

        callAs(userId, () -> reviewService.updateReview(created.getId(), update("5.0")));
        assertActivityAggregate(activityId, "5.00", 1);
        callAs(userId, () -> {
            reviewService.deleteReview(created.getId());
            return null;
        });

        assertAggregateMatchesReviews(activityId);
        assertActivityAggregate(activityId, "0.00", 0);
        assertThat(activityMapper.selectById(activityId).getStatus()).isEqualTo(2);
    }

    @Test
    void activityCollectionAndAnotherUsersReviewShouldRemainIndependent() throws Exception {
        long collectorId = insertUser("collector-a");
        long reviewerId = insertUser("reviewer-b");
        long activityId = insertActivity("collection-review-independent");

        List<Outcome> outcomes = runTogether(
                () -> callAs(collectorId, () -> {
                    collectionService.collect("ACTIVITY", activityId);
                    return null;
                }),
                () -> callAs(reviewerId,
                        () -> reviewService.createReview(create(activityId, "4.5")))
        );

        assertThat(outcomes).allMatch(Outcome::isSuccess);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_collection "
                        + "WHERE user_id = ? AND target_type = 'ACTIVITY' AND target_id = ?",
                Integer.class, collectorId, activityId)).isEqualTo(1);
        assertActivityAggregate(activityId, "4.50", 1);

        callAs(collectorId, () -> {
            collectionService.uncollect("ACTIVITY", activityId);
            return null;
        });
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_collection "
                        + "WHERE user_id = ? AND target_type = 'ACTIVITY' AND target_id = ?",
                Integer.class, collectorId, activityId)).isZero();
        assertActivityAggregate(activityId, "4.50", 1);
        assertAggregateMatchesReviews(activityId);
    }

    @Test
    void repeatedLikeAndUnlikeShouldBeIdempotentAndLeaveActivityFactsUntouched() {
        long authorId = insertUser("review-author");
        long likerId = insertUser("review-liker");
        long activityId = insertActivity("review-like-independent");
        Review review = createReview(authorId, activityId, "4.5");
        String key = "review:like:" + review.getId();
        String member = String.valueOf(likerId);
        when(zSetOperations.addIfAbsent(eq(key), eq(member), anyDouble()))
                .thenReturn(true, false);
        when(redisTemplate.execute(any(RedisScript.class),
                eq(Collections.singletonList(key)), eq(member))).thenReturn(123L, -1L);

        callAs(likerId, () -> reviewService.likeReview(review.getId()));
        callAs(likerId, () -> reviewService.likeReview(review.getId()));
        assertThat(reviewMapper.selectById(review.getId()).getLikeCount()).isEqualTo(1);
        assertActivityAggregate(activityId, "4.50", 1);

        callAs(likerId, () -> reviewService.unlikeReview(review.getId()));
        callAs(likerId, () -> reviewService.unlikeReview(review.getId()));
        assertThat(reviewMapper.selectById(review.getId()).getLikeCount()).isZero();
        assertActivityAggregate(activityId, "4.50", 1);
        assertAggregateMatchesReviews(activityId);

        verify(redisTemplate, never()).opsForValue();
        verify(redisTemplate, never()).opsForGeo();
        verify(redisTemplate, times(2)).execute(any(RedisScript.class),
                eq(Collections.singletonList(key)), eq(member));
        verify(redisTemplate, never()).delete(org.mockito.ArgumentMatchers.<String>argThat(redisKey ->
                redisKey.startsWith("seat:lock:") || redisKey.startsWith("venue:geo:")));
    }

    @Test
    void allActivityProjectionsShouldExposeTheSameAggregateWithoutChangingCollectionState() {
        long userId = insertUser("projection-user");
        long activityId = insertActivity("projection-consistency");
        createReview(userId, activityId, "4.5");
        callAs(userId, () -> {
            collectionService.collect("ACTIVITY", activityId);
            return null;
        });
        long venueId = insertVenue();
        long hallId = insertHall(venueId);
        insertShowtime(activityId, venueId, hallId);
        boundaryBefore = takeDatabaseBoundarySnapshot();

        GeoOperations<String, String> geoOperations = mock(GeoOperations.class);
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(eq("venue:geo:STEP135:THEATER"),
                any(Circle.class), any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenReturn(new GeoResults<>(Collections.singletonList(new GeoResult<>(
                        new RedisGeoCommands.GeoLocation<>(String.valueOf(venueId),
                                new Point(121.47D, 31.23D)),
                        new Distance(0.1D, Metrics.KILOMETERS)))));

        ActivityQueryDTO listQuery = new ActivityQueryDTO();
        listQuery.setCity("STEP135");
        listQuery.setKeyword("projection-consistency");
        ActivityNearbyQueryDTO nearbyQuery = new ActivityNearbyQueryDTO();
        nearbyQuery.setCity("STEP135");
        nearbyQuery.setX(121.47D);
        nearbyQuery.setY(31.23D);
        nearbyQuery.setVenueType(VenueType.THEATER);
        CollectionQueryDTO collectionQuery = new CollectionQueryDTO();
        collectionQuery.setTargetType("ACTIVITY");

        callAs(userId, () -> {
            ActivityListItemVO listItem = activityService.queryActivities(listQuery).getList().get(0);
            ActivityDetailVO detail = activityService.queryActivityDetail(activityId);
            ActivityNearbyVO nearby = activityService.queryNearbyActivities(nearbyQuery).get(0);
            CollectionVO collection = collectionService.queryCollections(collectionQuery).getList().get(0);
            ActivityListItemVO collected = (ActivityListItemVO) collection.getTarget();

            assertThat(Arrays.asList(listItem.getAvgScore(), detail.getAvgScore(),
                    nearby.getAvgScore(), collected.getAvgScore()))
                    .allSatisfy(score -> assertThat(score).isEqualByComparingTo("4.50"));
            assertThat(Arrays.asList(listItem.getScoreCount(), detail.getScoreCount(),
                    nearby.getScoreCount(), collected.getScoreCount()))
                    .containsOnly(1);
            assertThat(Arrays.asList(listItem.getCollected(), detail.getCollected(),
                    nearby.getCollected(), collected.getCollected()))
                    .containsOnly(true);
            return null;
        });

        verify(geoOperations).radius(eq("venue:geo:STEP135:THEATER"),
                any(Circle.class), any(RedisGeoCommands.GeoRadiusCommandArgs.class));
        verifyNoMoreInteractions(geoOperations);
        verify(redisTemplate, never()).opsForValue();
        verify(redisTemplate, never()).delete(org.mockito.ArgumentMatchers.<String>argThat(redisKey ->
                redisKey.startsWith("seat:lock:") || redisKey.startsWith("venue:geo:")));
        assertAggregateMatchesReviews(activityId);
    }

    private List<Outcome> runTogether(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> firstFuture = executor.submit(gated(first, ready, start));
            Future<Outcome> secondFuture = executor.submit(gated(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return Arrays.asList(
                    firstFuture.get(10, TimeUnit.SECONDS),
                    secondFuture.get(10, TimeUnit.SECONDS)
            );
        } finally {
            start.countDown();
            shutdown(executor);
        }
    }

    private Callable<Outcome> gated(Callable<?> action, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                return Outcome.failure(new IllegalStateException("concurrent start timed out"));
            }
            return capture(action);
        };
    }

    private Outcome capture(Callable<?> action) {
        try {
            return Outcome.success(action.call());
        } catch (Throwable error) {
            return Outcome.failure(error);
        }
    }

    private <T> T callAs(long userId, Callable<T> action) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
        try {
            return action.call();
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        } finally {
            UserHolder.removeUser();
        }
    }

    private Review createReview(long userId, long activityId, String score) {
        return reviewMapper.selectById(callAs(userId,
                () -> reviewService.createReview(create(activityId, score))).getId());
    }

    private CreateReviewDTO create(long activityId, String score) {
        return create("ACTIVITY", activityId, score);
    }

    private CreateReviewDTO create(String targetType, long targetId, String score) {
        CreateReviewDTO request = new CreateReviewDTO();
        request.setTargetType(targetType);
        request.setTargetId(targetId);
        request.setScore(new BigDecimal(score));
        request.setContent("Step 13.5 boundary review");
        request.setImages(Collections.emptyList());
        return request;
    }

    private UpdateReviewDTO update(String score) {
        UpdateReviewDTO request = new UpdateReviewDTO();
        request.setScore(new BigDecimal(score));
        request.setContent("Step 13.5 boundary update");
        request.setImages(Collections.emptyList());
        return request;
    }

    private long insertUser(String label) {
        long id = IDS.incrementAndGet();
        userIds.add(id);
        jdbcTemplate.update("INSERT INTO tb_user (id, phone, nick_name) VALUES (?, ?, ?)",
                id, "15" + id, "Step 13.5 " + label);
        return id;
    }

    private long insertActivity(String label) {
        long id = IDS.incrementAndGet();
        insertActivity(id, label);
        return id;
    }

    private void insertActivity(long id, String label) {
        activityIds.add(id);
        jdbcTemplate.update("INSERT INTO tb_activity "
                        + "(id, title, type, city, description, price_min, price_max, status) "
                        + "VALUES (?, ?, 'talkshow', 'STEP135', ?, 99.00, 199.00, 1)",
                id, "Step 13.5 " + label, "Activity Review concurrency boundary");
    }

    private void insertMovie(long id, String label) {
        movieIds.add(id);
        jdbcTemplate.update("INSERT INTO tb_movie (id, title, avg_score, score_count, status) "
                + "VALUES (?, ?, 0.00, 0, 1)", id, "Step 13.5 " + label);
    }

    private long insertVenue() {
        long id = IDS.incrementAndGet();
        venueIds.add(id);
        jdbcTemplate.update("INSERT INTO tb_venue "
                        + "(id, name, type, city, address, longitude, latitude, status) "
                        + "VALUES (?, 'Step 13.5 theater', 'theater', 'STEP135', "
                        + "'Boundary address', 121.470000, 31.230000, 1)", id);
        return id;
    }

    private long insertHall(long venueId) {
        long id = IDS.incrementAndGet();
        hallIds.add(id);
        jdbcTemplate.update("INSERT INTO tb_hall (id, venue_id, name) VALUES (?, ?, ?)",
                id, venueId, "Step 13.5 hall " + id);
        return id;
    }

    private void insertShowtime(long activityId, long venueId, long hallId) {
        long id = IDS.incrementAndGet();
        showtimeIds.add(id);
        jdbcTemplate.update("INSERT INTO tb_showtime "
                        + "(id, biz_type, biz_id, venue_id, hall_id, start_time, end_time, "
                        + "base_price, total_seats, available_seats, status) "
                        + "VALUES (?, 'ACTIVITY', ?, ?, ?, DATE_ADD(NOW(), INTERVAL 1 DAY), "
                        + "DATE_ADD(NOW(), INTERVAL 1 DAY) + INTERVAL 2 HOUR, 99.00, 10, 7, 1)",
                id, activityId, venueId, hallId);
    }

    private int activeReviewCount(String targetType, long targetId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_review "
                        + "WHERE target_type = ? AND target_id = ? AND status = 1",
                Integer.class, targetType, targetId);
    }

    private int reviewCountForRelation(long userId, String targetType, long targetId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_review "
                        + "WHERE user_id = ? AND target_type = ? AND target_id = ?",
                Integer.class, userId, targetType, targetId);
    }

    private void assertAggregateMatchesReviews(long activityId) {
        Map<String, Object> expected = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) score_count, COALESCE(ROUND(AVG(score), 2), 0.00) avg_score "
                        + "FROM tb_review WHERE target_type = 'ACTIVITY' AND target_id = ? AND status = 1",
                activityId);
        Activity activity = activityMapper.selectById(activityId);
        assertThat(activity.getScoreCount())
                .isEqualTo(((Number) expected.get("score_count")).intValue());
        assertThat(activity.getAvgScore())
                .isEqualByComparingTo((BigDecimal) expected.get("avg_score"));
    }

    private void assertActivityAggregate(long activityId, String avgScore, int scoreCount) {
        Activity activity = activityMapper.selectById(activityId);
        assertThat(activity.getAvgScore()).isEqualByComparingTo(avgScore);
        assertThat(activity.getScoreCount()).isEqualTo(scoreCount);
    }

    private DatabaseBoundarySnapshot takeDatabaseBoundarySnapshot() {
        Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
        tables.put("showtime", jdbcTemplate.queryForList("SELECT * FROM tb_showtime ORDER BY id"));
        tables.put("showtimeSeat", jdbcTemplate.queryForList("SELECT * FROM tb_showtime_seat ORDER BY id"));
        tables.put("ticketOrder", jdbcTemplate.queryForList("SELECT * FROM tb_ticket_order ORDER BY id"));
        tables.put("ticketOrderItem", jdbcTemplate.queryForList("SELECT * FROM tb_ticket_order_item ORDER BY id"));
        tables.put("availableSeats", jdbcTemplate.queryForList(
                "SELECT id, available_seats FROM tb_showtime ORDER BY id"));
        return new DatabaseBoundarySnapshot(tables);
    }

    private String placeholders(int size) {
        return String.join(",", Collections.nCopies(size, "?"));
    }

    private void shutdown(ExecutorService executor) {
        executor.shutdownNow();
        try {
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("transaction gate timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private static final class Outcome {
        private final Object value;
        private final Throwable error;

        private Outcome(Object value, Throwable error) {
            this.value = value;
            this.error = error;
        }

        static Outcome success(Object value) {
            return new Outcome(value, null);
        }

        static Outcome failure(Throwable error) {
            return new Outcome(null, error);
        }

        boolean isSuccess() {
            return error == null;
        }

        @Override
        public String toString() {
            return isSuccess()
                    ? "Outcome{success,value=" + value + "}"
                    : "Outcome{failure,error=" + error + "}";
        }
    }

    private static final class DatabaseBoundarySnapshot {
        private final Map<String, List<Map<String, Object>>> tables;

        private DatabaseBoundarySnapshot(Map<String, List<Map<String, Object>>> tables) {
            this.tables = tables;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DatabaseBoundarySnapshot
                    && tables.equals(((DatabaseBoundarySnapshot) other).tables);
        }

        @Override
        public int hashCode() {
            return tables.hashCode();
        }

        @Override
        public String toString() {
            return tables.toString();
        }
    }
}
