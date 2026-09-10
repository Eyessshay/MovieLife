package com.movielife.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.movielife.entity.Activity;
import com.movielife.entity.Seat;
import com.movielife.entity.Showtime;
import com.movielife.entity.ShowtimeSeat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

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
class ActivityPersistenceIntegrationTest {

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired private ActivityMapper activityMapper;
    @Autowired private ShowtimeMapper showtimeMapper;
    @Autowired private SeatMapper seatMapper;
    @Autowired private ShowtimeSeatMapper showtimeSeatMapper;

    @Test
    void shouldReadInitializedFixedSeatActivity() {
        Activity activity = activityMapper.selectById(1L);
        Showtime showtime = showtimeMapper.selectById(2L);
        List<Seat> seats = seatMapper.selectList(new LambdaQueryWrapper<Seat>()
                .eq(Seat::getHallId, 3L)
                .eq(Seat::getBaseStatus, 1));
        List<ShowtimeSeat> states = showtimeSeatMapper.selectList(
                new LambdaQueryWrapper<ShowtimeSeat>()
                        .eq(ShowtimeSeat::getShowtimeId, 2L));

        assertThat(activity).isNotNull();
        assertThat(activity.getStatus()).isEqualTo(1);
        assertThat(showtime).isNotNull();
        assertThat(showtime.getBizType()).isEqualTo("ACTIVITY");
        assertThat(showtime.getBizId()).isEqualTo(activity.getId());
        assertThat(showtime.getHallId()).isEqualTo(3L);
        assertThat(showtime.getStartTime()).isAfter(java.time.LocalDateTime.now());
        assertThat(seats).hasSize(6);
        assertThat(states).hasSize(6);
        assertThat(states).extracting(ShowtimeSeat::getSeatId)
                .containsExactlyInAnyOrderElementsOf(
                        seats.stream().map(Seat::getId).collect(java.util.stream.Collectors.toList()));
        assertThat(showtime.getTotalSeats()).isEqualTo(seats.size());
        assertThat(showtime.getAvailableSeats()).isEqualTo(6);
    }
}
