package com.movielife.service;

import com.movielife.entity.Hall;
import com.movielife.entity.Movie;
import com.movielife.entity.Showtime;
import com.movielife.entity.Venue;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.vo.ShowtimeDetailVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
@Transactional
class ShowtimeServiceIntegrationTest {

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ShowtimeService showtimeService;
    @Autowired
    private ShowtimeMapper showtimeMapper;
    @Autowired
    private VenueMapper venueMapper;
    @Autowired
    private HallMapper hallMapper;
    @Autowired
    private MovieMapper movieMapper;

    @Test
    void shouldReturnFutureMovieShowtimeWithRelatedDetails() {
        Movie movie = insertMovie("场次详情电影");
        Venue venue = insertVenue(1, "场次详情影院");
        Hall hall = insertHall(venue, "详情测试厅");
        Showtime showtime = insertShowtime(
                venue,
                hall,
                movie,
                "MOVIE",
                LocalDateTime.now().plusDays(1).withNano(0),
                1
        );

        ShowtimeDetailVO result = showtimeService.queryShowtimeDetail(showtime.getId());

        assertThat(result.getId()).isEqualTo(showtime.getId());
        assertThat(result.getVenueId()).isEqualTo(venue.getId());
        assertThat(result.getVenueName()).isEqualTo("场次详情影院");
        assertThat(result.getHallId()).isEqualTo(hall.getId());
        assertThat(result.getHallName()).isEqualTo("详情测试厅");
        assertThat(result.getMovieId()).isEqualTo(movie.getId());
        assertThat(result.getMovieName()).isEqualTo("场次详情电影");
        assertThat(result.getStartTime()).isEqualTo(showtime.getStartTime());
        assertThat(result.getEndTime()).isEqualTo(showtime.getEndTime());
        assertThat(result.getBasePrice()).isEqualByComparingTo("45.00");
    }

    @Test
    void shouldHideMissingStoppedExpiredAndNonMovieShowtimes() {
        Movie movie = insertMovie("不可用场次电影");
        Venue venue = insertVenue(1, "不可用场次影院");
        Hall hall = insertHall(venue, "不可用测试厅");
        Showtime stopped = insertShowtime(
                venue, hall, movie, "MOVIE", LocalDateTime.now().plusDays(1), 0
        );
        Showtime expired = insertShowtime(
                venue, hall, movie, "MOVIE", LocalDateTime.now().minusHours(1), 1
        );
        Showtime activity = insertShowtime(
                venue, hall, movie, "ACTIVITY", LocalDateTime.now().plusDays(1), 1
        );

        assertError(() -> showtimeService.queryShowtimeDetail(999999999L), ErrorCode.SHOWTIME_NOT_FOUND);
        assertError(() -> showtimeService.queryShowtimeDetail(stopped.getId()), ErrorCode.SHOWTIME_NOT_FOUND);
        assertError(() -> showtimeService.queryShowtimeDetail(expired.getId()), ErrorCode.SHOWTIME_NOT_FOUND);
        assertError(() -> showtimeService.queryShowtimeDetail(activity.getId()), ErrorCode.SHOWTIME_NOT_FOUND);
    }

    @Test
    void shouldRejectInvalidIdAndDisabledVenue() {
        Movie movie = insertMovie("停用场馆电影");
        Venue venue = insertVenue(0, "停用场次影院");
        Hall hall = insertHall(venue, "停用场馆测试厅");
        Showtime showtime = insertShowtime(
                venue, hall, movie, "MOVIE", LocalDateTime.now().plusDays(1), 1
        );

        assertError(() -> showtimeService.queryShowtimeDetail(0L), ErrorCode.INVALID_ID);
        assertError(() -> showtimeService.queryShowtimeDetail(showtime.getId()), ErrorCode.SHOWTIME_NOT_FOUND);
    }

    private Movie insertMovie(String title) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setAvgScore(BigDecimal.ZERO);
        movie.setScoreCount(0);
        movie.setStatus(1);
        movieMapper.insert(movie);
        return movie;
    }

    private Venue insertVenue(int status, String name) {
        Venue venue = new Venue();
        venue.setName(name);
        venue.setType("cinema");
        venue.setCity("上海");
        venue.setAddress("场次详情测试地址");
        venue.setAvgScore(BigDecimal.ZERO);
        venue.setStatus(status);
        venueMapper.insert(venue);
        return venue;
    }

    private Hall insertHall(Venue venue, String name) {
        Hall hall = new Hall();
        hall.setVenueId(venue.getId());
        hall.setName(name);
        hallMapper.insert(hall);
        return hall;
    }

    private Showtime insertShowtime(Venue venue,
                                    Hall hall,
                                    Movie movie,
                                    String bizType,
                                    LocalDateTime startTime,
                                    int status) {
        Showtime showtime = new Showtime();
        showtime.setBizType(bizType);
        showtime.setBizId(movie.getId());
        showtime.setVenueId(venue.getId());
        showtime.setHallId(hall.getId());
        showtime.setStartTime(startTime);
        showtime.setEndTime(startTime.plusHours(2));
        showtime.setBasePrice(new BigDecimal("45.00"));
        showtime.setTotalSeats(100);
        showtime.setAvailableSeats(100);
        showtime.setStatus(status);
        showtimeMapper.insert(showtime);
        return showtime;
    }

    private void assertError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(errorCode);
    }
}
