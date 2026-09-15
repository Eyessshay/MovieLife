package com.movielife.mapper;

import com.movielife.entity.Activity;
import com.movielife.entity.Movie;
import com.movielife.entity.Showtime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

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
class ActivityReviewAggregatePersistenceIntegrationTest {

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private MovieMapper movieMapper;
    @Autowired
    private ShowtimeMapper showtimeMapper;
    @Autowired
    private ShowtimeSeatMapper showtimeSeatMapper;
    @Autowired
    private TicketOrderMapper ticketOrderMapper;
    @Autowired
    private TicketOrderItemMapper ticketOrderItemMapper;

    private final Set<Long> activityIds = ConcurrentHashMap.newKeySet();

    @AfterEach
    void cleanUp() {
        if (!activityIds.isEmpty()) {
            activityMapper.deleteBatchIds(activityIds);
            activityIds.clear();
        }
    }

    @Test
    void shouldApplyCompleteAtomicAggregateLifecycleWithoutChangingFrozenFacts() {
        Movie movieBefore = movieMapper.selectById(1L);
        long showtimeCountBefore = showtimeMapper.selectCount(null);
        long showtimeSeatCountBefore = showtimeSeatMapper.selectCount(null);
        long orderCountBefore = ticketOrderMapper.selectCount(null);
        long orderItemCountBefore = ticketOrderItemMapper.selectCount(null);
        Map<Long, Integer> availableSeatsBefore = availableSeatsByShowtime();
        Activity activity = insertActivity(1);

        assertThat(activityMapper.addReviewScore(activity.getId(), score("4.5"))).isEqualTo(1);
        assertAggregate(activity.getId(), "4.50", 1);

        assertThat(activityMapper.addReviewScore(activity.getId(), score("3.5"))).isEqualTo(1);
        assertAggregate(activity.getId(), "4.00", 2);

        assertThat(activityMapper.replaceReviewScore(
                activity.getId(), score("3.5"), score("5.0"))).isEqualTo(1);
        assertAggregate(activity.getId(), "4.75", 2);

        assertThat(activityMapper.removeReviewScore(activity.getId(), score("4.5"))).isEqualTo(1);
        assertAggregate(activity.getId(), "5.00", 1);

        assertThat(activityMapper.removeReviewScore(activity.getId(), score("5.0"))).isEqualTo(1);
        assertAggregate(activity.getId(), "0.00", 0);

        Movie movieAfter = movieMapper.selectById(1L);
        assertThat(movieAfter.getAvgScore()).isEqualByComparingTo(movieBefore.getAvgScore());
        assertThat(movieAfter.getScoreCount()).isEqualTo(movieBefore.getScoreCount());
        assertThat(showtimeMapper.selectCount(null)).isEqualTo(showtimeCountBefore);
        assertThat(showtimeSeatMapper.selectCount(null)).isEqualTo(showtimeSeatCountBefore);
        assertThat(ticketOrderMapper.selectCount(null)).isEqualTo(orderCountBefore);
        assertThat(ticketOrderItemMapper.selectCount(null)).isEqualTo(orderItemCountBefore);
        assertThat(availableSeatsByShowtime()).isEqualTo(availableSeatsBefore);
    }

    @Test
    void shouldReturnZeroWhenAtomicUpdatePreconditionsAreNotMet() {
        Activity activity = insertActivity(1);

        assertAggregate(activity.getId(), "0.00", 0);
        assertThat(activityMapper.replaceReviewScore(
                activity.getId(), score("4.0"), score("5.0"))).isZero();
        assertThat(activityMapper.removeReviewScore(activity.getId(), score("4.0"))).isZero();
        assertThat(activityMapper.addReviewScore(Long.MAX_VALUE, score("4.0"))).isZero();

        activity.setStatus(2);
        assertThat(activityMapper.updateById(activity)).isEqualTo(1);
        assertThat(activityMapper.addReviewScore(activity.getId(), score("4.0"))).isZero();
        assertAggregate(activity.getId(), "0.00", 0);
    }

    @Test
    void shouldSerializeConcurrentAggregateUpdatesInMysql() throws Exception {
        Activity activity = insertActivity(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return activityMapper.addReviewScore(activity.getId(), score("4.5"));
            });
            Future<Integer> second = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return activityMapper.addReviewScore(activity.getId(), score("3.5"));
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(Arrays.asList(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(1, 1);
            assertAggregate(activity.getId(), "4.00", 2);
        } finally {
            executor.shutdownNow();
        }
    }

    private Activity insertActivity(int status) {
        Activity activity = new Activity();
        activity.setTitle("Step 13.2 aggregate " + System.nanoTime());
        activity.setType("talkshow");
        activity.setCity("上海");
        activity.setDescription("Activity review aggregate persistence test");
        activity.setPriceMin(score("99.00"));
        activity.setPriceMax(score("199.00"));
        activity.setStatus(status);
        assertThat(activityMapper.insert(activity)).isEqualTo(1);
        assertThat(activity.getId()).isPositive();
        activityIds.add(activity.getId());
        return activity;
    }

    private void assertAggregate(Long activityId, String avgScore, int scoreCount) {
        Activity persisted = activityMapper.selectById(activityId);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getAvgScore()).isEqualByComparingTo(avgScore);
        assertThat(persisted.getScoreCount()).isEqualTo(scoreCount);
    }

    private Map<Long, Integer> availableSeatsByShowtime() {
        List<Showtime> showtimes = showtimeMapper.selectList(null);
        return showtimes.stream().collect(Collectors.toMap(
                Showtime::getId,
                Showtime::getAvailableSeats
        ));
    }

    private BigDecimal score(String value) {
        return new BigDecimal(value);
    }
}
