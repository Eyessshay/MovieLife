package com.movielife.service;

import com.movielife.entity.AiSummaryTask;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.mapper.AiSummaryTaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
class AiSummaryConcurrencyIntegrationTest {

    private static final long TARGET_ID = 991499L;

    @MockBean
    private StringRedisTemplate redisTemplate;
    @Autowired
    private AiSummaryPersistenceService service;
    @Autowired
    private AiSummaryTaskMapper taskMapper;

    @AfterEach
    void cleanUp() {
        AiSummaryTask task = taskMapper.selectByTarget("MOVIE", TARGET_ID);
        if (task != null) {
            taskMapper.deleteById(task.getId());
        }
    }

    @Test
    void concurrentDirtySignalsShouldBeAccumulatedWithoutLostUpdate() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        LocalDateTime now = LocalDateTime.of(2026, 9, 18, 11, 0);
        try {
            Future<?> first = executor.submit(() -> markDirtyTogether(ready, start, now));
            Future<?> second = executor.submit(() -> markDirtyTogether(ready, start, now));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        AiSummaryTask task = taskMapper.selectByTarget("MOVIE", TARGET_ID);
        assertThat(task.getDirtyVersion()).isEqualTo(2L);
        assertThat(task.getProcessedVersion()).isZero();
        assertThat(task.getStatus()).isEqualTo("PENDING");
    }

    private void markDirtyTogether(CountDownLatch ready,
                                   CountDownLatch start,
                                   LocalDateTime now) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrency start timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        service.markDirty(AiSummaryTargetType.MOVIE, TARGET_ID, now);
    }
}
