package com.movielife.mapper;

import com.movielife.entity.Hall;
import com.movielife.entity.Showtime;
import com.movielife.entity.Venue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

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
class VenueDataLayerIntegrationTest {

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private VenueMapper venueMapper;
    @Autowired
    private HallMapper hallMapper;
    @Autowired
    private ShowtimeMapper showtimeMapper;

    @Test
    void shouldReadInitializedVenueHallAndShowtime() {
        Venue venue = venueMapper.selectById(1L);
        Hall hall = hallMapper.selectById(1L);
        Showtime showtime = showtimeMapper.selectById(1L);

        assertThat(venue).isNotNull();
        assertThat(venue.getId()).isEqualTo(1L);
        assertThat(venue.getLongitude()).isNotNull();
        assertThat(venue.getLatitude()).isNotNull();

        assertThat(hall).isNotNull();
        assertThat(hall.getId()).isEqualTo(1L);
        assertThat(hall.getVenueId()).isEqualTo(venue.getId());

        assertThat(showtime).isNotNull();
        assertThat(showtime.getId()).isEqualTo(1L);
        assertThat(showtime.getVenueId()).isEqualTo(venue.getId());
        assertThat(showtime.getHallId()).isEqualTo(hall.getId());
        assertThat(showtime.getStartTime()).isNotNull();
        assertThat(showtime.getEndTime()).isNotNull();
        assertThat(showtime.getBasePrice()).isNotNull();
    }
}
