package com.movielife.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "movielife.startup-check.enabled=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeatReleaseLuaIntegrationTest {

    private static final DefaultRedisScript<Long> SCRIPT;

    static {
        SCRIPT = new DefaultRedisScript<>();
        SCRIPT.setLocation(new ClassPathResource("lua/seat_release.lua"));
        SCRIPT.setResultType(Long.class);
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final Set<String> cleanupKeys = new LinkedHashSet<>();

    @AfterEach
    void cleanRedisKeys() {
        if (!cleanupKeys.isEmpty()) {
            redisTemplate.delete(cleanupKeys);
        }
        assertThat(redisTemplate.opsForValue().multiGet(cleanupKeys))
                .allMatch(value -> value == null);
    }

    @AfterAll
    void noSeatLockKeysShouldRemain() {
        assertThat(redisTemplate.keys("seat:lock:*")).isEmpty();
    }

    @Test
    void shouldReleaseTwoSeatsAndTokenMetadata() {
        TestLock lock = createLock(2);

        Long result = release(lock);

        assertThat(result).isZero();
        assertAllMissing(lock.allKeys);
    }

    @Test
    void missingTokenMetadataShouldBeIdempotentSuccess() {
        TestLock lock = testLock(2);

        assertThat(release(lock)).isZero();
        assertAllMissing(lock.allKeys);
    }

    @Test
    void missingSeatKeyShouldNotPreventRemainingSeatsFromBeingReleased() {
        TestLock lock = createLock(2);
        redisTemplate.delete(lock.seatKeys.get(0));

        assertThat(release(lock)).isZero();
        assertAllMissing(lock.allKeys);
    }

    @Test
    void seatReownedByAnotherTokenMustNotBeDeleted() {
        TestLock lock = createLock(2);
        redisTemplate.opsForValue().set(lock.seatKeys.get(1), "tokenB", 30, TimeUnit.SECONDS);

        assertThat(release(lock)).isZero();

        assertThat(redisTemplate.opsForValue().get(lock.seatKeys.get(0))).isNull();
        assertThat(redisTemplate.opsForValue().get(lock.seatKeys.get(1))).isEqualTo("tokenB");
        assertThat(redisTemplate.opsForValue().get(lock.tokenKey)).isNull();
    }

    @Test
    void malformedMetadataShouldReturnErrorAndDeleteNothing() {
        TestLock lock = testLock(2);
        redisTemplate.opsForValue().set(lock.tokenKey, "not-json", 30, TimeUnit.SECONDS);
        for (String seatKey : lock.seatKeys) {
            redisTemplate.opsForValue().set(seatKey, lock.token, 30, TimeUnit.SECONDS);
        }

        assertThat(release(lock)).isEqualTo(1L);

        assertThat(redisTemplate.opsForValue().get(lock.tokenKey)).isEqualTo("not-json");
        assertThat(redisTemplate.opsForValue().multiGet(lock.seatKeys))
                .containsOnly(lock.token);
    }

    @Test
    void repeatedReleaseShouldRemainSuccessful() {
        TestLock lock = createLock(2);

        assertThat(release(lock)).isZero();
        assertThat(release(lock)).isZero();
        assertAllMissing(lock.allKeys);
    }

    @Test
    void shouldReleaseMoreThanFourSeatsInOneExecution() {
        TestLock lock = createLock(5);

        assertThat(release(lock)).isZero();
        assertAllMissing(lock.allKeys);
    }

    private TestLock createLock(int seatCount) {
        TestLock lock = testLock(seatCount);
        redisTemplate.opsForValue().set(lock.tokenKey, metadata(lock), 30, TimeUnit.SECONDS);
        for (String seatKey : lock.seatKeys) {
            redisTemplate.opsForValue().set(seatKey, lock.token, 30, TimeUnit.SECONDS);
        }
        return lock;
    }

    private Long release(TestLock lock) {
        return redisTemplate.execute(
                SCRIPT,
                java.util.Collections.singletonList(lock.tokenKey),
                lock.token,
                "seat:lock:"
        );
    }

    private TestLock testLock(int seatCount) {
        long showtimeId = 9_200_000_000L
                + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000L);
        String token = UUID.randomUUID().toString().replace("-", "");
        List<Long> seatIds = new ArrayList<>();
        List<String> seatKeys = new ArrayList<>();
        for (int index = 0; index < seatCount; index++) {
            long seatId = 101L + index;
            seatIds.add(seatId);
            seatKeys.add("seat:lock:" + showtimeId + ":" + seatId);
        }
        String tokenKey = "seat:lock:token:" + token;
        List<String> allKeys = new ArrayList<>(seatKeys);
        allKeys.add(tokenKey);
        cleanupKeys.addAll(allKeys);
        return new TestLock(showtimeId, token, tokenKey, seatIds, seatKeys, allKeys);
    }

    private String metadata(TestLock lock) {
        return "{\"userId\":1,\"showtimeId\":" + lock.showtimeId
                + ",\"seatIds\":" + lock.seatIds
                + ",\"expireTime\":\"2026-09-03T18:00:00\"}";
    }

    private void assertAllMissing(List<String> keys) {
        assertThat(redisTemplate.opsForValue().multiGet(keys))
                .allMatch(value -> value == null);
    }

    private static final class TestLock {

        private final long showtimeId;
        private final String token;
        private final String tokenKey;
        private final List<Long> seatIds;
        private final List<String> seatKeys;
        private final List<String> allKeys;

        private TestLock(long showtimeId, String token, String tokenKey,
                         List<Long> seatIds, List<String> seatKeys, List<String> allKeys) {
            this.showtimeId = showtimeId;
            this.token = token;
            this.tokenKey = tokenKey;
            this.seatIds = seatIds;
            this.seatKeys = seatKeys;
            this.allKeys = allKeys;
        }
    }
}
