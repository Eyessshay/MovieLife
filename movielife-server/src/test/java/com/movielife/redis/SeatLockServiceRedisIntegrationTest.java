package com.movielife.redis;

import com.movielife.dto.SeatLockRequest;
import com.movielife.dto.SeatReleaseRequest;
import com.movielife.dto.UserDTO;
import com.movielife.entity.Seat;
import com.movielife.entity.ShowtimeSeat;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.service.SeatLockService;
import com.movielife.service.ShowtimeService;
import com.movielife.utils.UserHolder;
import com.movielife.vo.SeatLockVO;
import com.movielife.vo.ShowtimeDetailVO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "movielife.startup-check.enabled=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeatLockServiceRedisIntegrationTest {

    @Autowired
    private SeatLockService seatLockService;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ShowtimeService showtimeService;
    @MockBean
    private SeatMapper seatMapper;
    @MockBean
    private ShowtimeSeatMapper showtimeSeatMapper;

    private final Set<String> cleanupKeys = new LinkedHashSet<>();

    @BeforeEach
    void setUpUser() {
        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void cleanRedisKeys() {
        UserHolder.removeUser();
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
    void repeatedLockShouldReturnSameTokenAndExpiryWithoutRefreshingTtl() {
        long showtimeId = 9_100_000_000L
                + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000L);
        stubValidDatabase(showtimeId);
        String firstSeatKey = "seat:lock:" + showtimeId + ":101";
        String secondSeatKey = "seat:lock:" + showtimeId + ":102";
        cleanupKeys.add(firstSeatKey);
        cleanupKeys.add(secondSeatKey);

        SeatLockVO first = seatLockService.lock(showtimeId, request(101L, 102L));
        String tokenKey = "seat:lock:token:" + first.getLockToken();
        cleanupKeys.add(tokenKey);
        Long firstSeatTtl = redisTemplate.getExpire(firstSeatKey, TimeUnit.MILLISECONDS);
        Long firstTokenTtl = redisTemplate.getExpire(tokenKey, TimeUnit.MILLISECONDS);

        SeatLockVO second = seatLockService.lock(showtimeId, request(102L, 101L));
        Long secondSeatTtl = redisTemplate.getExpire(firstSeatKey, TimeUnit.MILLISECONDS);
        Long secondTokenTtl = redisTemplate.getExpire(tokenKey, TimeUnit.MILLISECONDS);

        assertThat(second.getLockToken()).isEqualTo(first.getLockToken());
        assertThat(second.getExpireTime()).isEqualTo(first.getExpireTime());
        assertThat(secondSeatTtl).isPositive().isLessThanOrEqualTo(firstSeatTtl + 100L);
        assertThat(secondTokenTtl).isPositive().isLessThanOrEqualTo(firstTokenTtl + 100L);
        assertThat(redisTemplate.opsForValue().get(firstSeatKey)).isEqualTo(first.getLockToken());
        assertThat(redisTemplate.opsForValue().get(secondSeatKey)).isEqualTo(first.getLockToken());
        assertThat(redisTemplate.opsForValue().get(tokenKey)).isNotBlank();
    }

    @Test
    void lockThenReleaseShouldDeleteAllKeysAndRemainIdempotent() {
        long showtimeId = 9_100_000_000L
                + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000L);
        stubValidDatabase(showtimeId);
        String firstSeatKey = "seat:lock:" + showtimeId + ":101";
        String secondSeatKey = "seat:lock:" + showtimeId + ":102";
        cleanupKeys.add(firstSeatKey);
        cleanupKeys.add(secondSeatKey);

        SeatLockVO lock = seatLockService.lock(showtimeId, request(101L, 102L));
        String tokenKey = "seat:lock:token:" + lock.getLockToken();
        cleanupKeys.add(tokenKey);
        assertThat(redisTemplate.opsForValue().get(firstSeatKey)).isEqualTo(lock.getLockToken());
        assertThat(redisTemplate.opsForValue().get(secondSeatKey)).isEqualTo(lock.getLockToken());
        assertThat(redisTemplate.opsForValue().get(tokenKey)).isNotNull();

        SeatReleaseRequest releaseRequest = new SeatReleaseRequest();
        releaseRequest.setLockToken(lock.getLockToken());
        seatLockService.release(showtimeId, releaseRequest);

        assertThat(redisTemplate.opsForValue().get(firstSeatKey)).isNull();
        assertThat(redisTemplate.opsForValue().get(secondSeatKey)).isNull();
        assertThat(redisTemplate.opsForValue().get(tokenKey)).isNull();
        seatLockService.release(showtimeId, releaseRequest);
    }

    private void stubValidDatabase(long showtimeId) {
        ShowtimeDetailVO showtime = new ShowtimeDetailVO();
        showtime.setId(showtimeId);
        showtime.setHallId(10L);
        when(showtimeService.queryShowtimeDetail(showtimeId)).thenReturn(showtime);

        Seat firstSeat = seat(101L);
        Seat secondSeat = seat(102L);
        when(seatMapper.selectList(any())).thenReturn(Arrays.asList(firstSeat, secondSeat));

        ShowtimeSeat firstState = showtimeSeat(showtimeId, 101L);
        ShowtimeSeat secondState = showtimeSeat(showtimeId, 102L);
        when(showtimeSeatMapper.selectList(any())).thenReturn(Arrays.asList(firstState, secondState));
    }

    private Seat seat(Long id) {
        Seat seat = new Seat();
        seat.setId(id);
        seat.setHallId(10L);
        seat.setBaseStatus(1);
        return seat;
    }

    private ShowtimeSeat showtimeSeat(long showtimeId, Long seatId) {
        ShowtimeSeat state = new ShowtimeSeat();
        state.setShowtimeId(showtimeId);
        state.setSeatId(seatId);
        state.setStatus(0);
        return state;
    }

    private SeatLockRequest request(Long... seatIds) {
        SeatLockRequest request = new SeatLockRequest();
        request.setSeatIds(Arrays.asList(seatIds));
        return request;
    }
}
