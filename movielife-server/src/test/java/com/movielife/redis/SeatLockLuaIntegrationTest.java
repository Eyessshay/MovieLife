package com.movielife.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "movielife.startup-check.enabled=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeatLockLuaIntegrationTest {

    private static final DefaultRedisScript<Long> SCRIPT;

    static {
        SCRIPT = new DefaultRedisScript<>();
        SCRIPT.setLocation(new ClassPathResource("lua/seat_lock.lua"));
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
    void shouldCreateTwoSeatKeysAndMetadataWithOneAbsoluteDeadline() {
        TestKeys keys = keys(2);
        String metadata = metadata(Arrays.asList(101L, 102L));
        long expireAtMillis = Instant.now().plusSeconds(30).toEpochMilli();

        Long result = execute(keys, metadata, expireAtMillis);

        assertThat(result).isZero();
        assertThat(redisTemplate.opsForValue().get(keys.seatKeys.get(0))).isEqualTo(keys.token);
        assertThat(redisTemplate.opsForValue().get(keys.seatKeys.get(1))).isEqualTo(keys.token);
        assertThat(redisTemplate.opsForValue().get(keys.tokenKey)).isEqualTo(metadata);
        assertDeadline(keys.allKeys, expireAtMillis);
    }

    @Test
    void middleSeatConflictShouldLeaveNoPartialLockAndPreserveExistingValue() {
        TestKeys keys = keys(3);
        redisTemplate.opsForValue().set(keys.seatKeys.get(1), "otherToken", 30, TimeUnit.SECONDS);

        Long result = execute(keys, metadata(Arrays.asList(101L, 102L, 103L)),
                Instant.now().plusSeconds(30).toEpochMilli());

        assertThat(result).isEqualTo(1L);
        assertThat(redisTemplate.opsForValue().get(keys.seatKeys.get(0))).isNull();
        assertThat(redisTemplate.opsForValue().get(keys.seatKeys.get(1))).isEqualTo("otherToken");
        assertThat(redisTemplate.opsForValue().get(keys.seatKeys.get(2))).isNull();
        assertThat(redisTemplate.opsForValue().get(keys.tokenKey)).isNull();
    }

    @Test
    void firstSeatConflictShouldPreventAllNewWrites() {
        TestKeys keys = keys(2);
        redisTemplate.opsForValue().set(keys.seatKeys.get(0), "existingToken", 30, TimeUnit.SECONDS);

        Long result = execute(keys, metadata(Arrays.asList(101L, 102L)),
                Instant.now().plusSeconds(30).toEpochMilli());

        assertThat(result).isEqualTo(1L);
        assertThat(redisTemplate.opsForValue().get(keys.seatKeys.get(0))).isEqualTo("existingToken");
        assertThat(redisTemplate.opsForValue().get(keys.seatKeys.get(1))).isNull();
        assertThat(redisTemplate.opsForValue().get(keys.tokenKey)).isNull();
    }

    @Test
    void tokenConflictShouldPreserveMetadataAndCreateNoSeatKeys() {
        TestKeys keys = keys(2);
        redisTemplate.opsForValue().set(keys.tokenKey, "old-metadata", 30, TimeUnit.SECONDS);

        Long result = execute(keys, "new-metadata",
                Instant.now().plusSeconds(30).toEpochMilli());

        assertThat(result).isEqualTo(2L);
        assertThat(redisTemplate.opsForValue().get(keys.tokenKey)).isEqualTo("old-metadata");
        assertThat(redisTemplate.opsForValue().get(keys.seatKeys.get(0))).isNull();
        assertThat(redisTemplate.opsForValue().get(keys.seatKeys.get(1))).isNull();
    }

    @Test
    void shouldAtomicallyCreateMoreThanThreeSeatKeys() {
        TestKeys keys = keys(4);
        String metadata = metadata(Arrays.asList(101L, 102L, 103L, 104L));
        long expireAtMillis = Instant.now().plusSeconds(30).toEpochMilli();

        Long result = execute(keys, metadata, expireAtMillis);

        assertThat(result).isZero();
        assertThat(redisTemplate.opsForValue().multiGet(keys.seatKeys))
                .containsExactly(keys.token, keys.token, keys.token, keys.token);
        assertThat(redisTemplate.opsForValue().get(keys.tokenKey)).isEqualTo(metadata);
        assertDeadline(keys.allKeys, expireAtMillis);
    }

    private Long execute(TestKeys keys, String metadata, long expireAtMillis) {
        return redisTemplate.execute(
                SCRIPT,
                keys.allKeys,
                keys.token,
                metadata,
                String.valueOf(expireAtMillis),
                String.valueOf(keys.seatKeys.size())
        );
    }

    private void assertDeadline(List<String> keys, long expectedExpireAtMillis) {
        for (String key : keys) {
            Long ttl = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
            assertThat(ttl).isNotNull().isPositive();
            Long redisNowMillis = redisTemplate.execute(
                    (RedisCallback<Long>) connection ->
                            connection.serverCommands().time(TimeUnit.MILLISECONDS));
            assertThat(redisNowMillis).isNotNull().isPositive();
            long actualExpireAtMillis = redisNowMillis + ttl;
            assertThat(Math.abs(actualExpireAtMillis - expectedExpireAtMillis)).isLessThan(1500L);
        }
    }

    private TestKeys keys(int seatCount) {
        long showtimeId = 9_000_000_000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000L);
        String token = UUID.randomUUID().toString().replace("-", "");
        List<String> seatKeys = new ArrayList<>();
        for (int index = 0; index < seatCount; index++) {
            seatKeys.add("seat:lock:" + showtimeId + ":" + (101 + index));
        }
        String tokenKey = "seat:lock:token:" + token;
        List<String> allKeys = new ArrayList<>(seatKeys);
        allKeys.add(tokenKey);
        cleanupKeys.addAll(allKeys);
        return new TestKeys(token, tokenKey, seatKeys, allKeys);
    }

    private String metadata(List<Long> seatIds) {
        return "{\"userId\":1,\"showtimeId\":1001,\"seatIds\":" + seatIds
                + ",\"expireTime\":\"2026-09-03T17:00:00\"}";
    }

    private static final class TestKeys {

        private final String token;
        private final String tokenKey;
        private final List<String> seatKeys;
        private final List<String> allKeys;

        private TestKeys(String token, String tokenKey, List<String> seatKeys, List<String> allKeys) {
            this.token = token;
            this.tokenKey = tokenKey;
            this.seatKeys = seatKeys;
            this.allKeys = allKeys;
        }
    }
}
