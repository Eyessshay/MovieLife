package com.movielife.service;

import com.movielife.entity.Hall;
import com.movielife.entity.Showtime;
import com.movielife.entity.Venue;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.vo.ShowtimeVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
class VenueShowtimeServiceIntegrationTest {

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private VenueService venueService;
    @Autowired
    private VenueMapper venueMapper;
    @Autowired
    private HallMapper hallMapper;
    @Autowired
    private ShowtimeMapper showtimeMapper;

    @Test
    void shouldReturnOnlyTargetDateFutureOnSaleMovieShowtimesInTimeOrder() {
        Venue venue = insertVenue(1, "场次查询影院");
        Venue otherVenue = insertVenue(1, "其他影院");
        Hall firstHall = insertHall(venue, "1号厅");
        Hall secondHall = insertHall(venue, "2号厅");
        Hall otherHall = insertHall(otherVenue, "其他厅");
        LocalDate date = LocalDate.now().plusDays(1);

        insertShowtime(venue, secondHall, 1L, date.atTime(15, 0), 1, "55.00");
        insertShowtime(venue, firstHall, 1L, date.atTime(10, 0), 1, "45.00");
        insertShowtime(venue, firstHall, 1L, date.atTime(12, 0), 0, "45.00");
        insertShowtime(venue, firstHall, 999L, date.atTime(13, 0), 1, "45.00");
        insertShowtime(venue, firstHall, 1L, date.plusDays(1).atTime(10, 0), 1, "45.00");
        insertShowtime(otherVenue, otherHall, 1L, date.atTime(11, 0), 1, "45.00");

        List<ShowtimeVO> result = venueService.queryVenueShowtimes(venue.getId(), 1L, date);

        assertThat(result).extracting(ShowtimeVO::getHallName)
                .containsExactly("1号厅", "2号厅");
        assertThat(result).extracting(ShowtimeVO::getStartTime)
                .containsExactly(date.atTime(10, 0), date.atTime(15, 0));
        assertThat(result).extracting(ShowtimeVO::getBasePrice)
                .containsExactly(new BigDecimal("45.00"), new BigDecimal("55.00"));
    }

    @Test
    void pastShowtimeAndEmptyDateShouldReturnEmptyList() {
        Venue venue = insertVenue(1, "无可售场次影院");
        Hall hall = insertHall(venue, "测试厅");
        LocalDate today = LocalDate.now();
        insertShowtime(venue, hall, 1L, LocalDateTime.now().minusHours(1), 1, "45.00");

        assertThat(venueService.queryVenueShowtimes(venue.getId(), 1L, today)).isEmpty();
        assertThat(venueService.queryVenueShowtimes(
                venue.getId(),
                1L,
                today.plusDays(2)
        )).isEmpty();
    }

    @Test
    void shouldValidateVenueMovieAndIds() {
        Venue disabled = insertVenue(0, "停用影院");
        Venue enabled = insertVenue(1, "有效影院");

        assertError(() -> venueService.queryVenueShowtimes(0L, 1L, LocalDate.now()), ErrorCode.INVALID_ID);
        assertError(() -> venueService.queryVenueShowtimes(enabled.getId(), 0L, LocalDate.now()), ErrorCode.INVALID_ID);
        assertError(() -> venueService.queryVenueShowtimes(999999999L, 1L, LocalDate.now()), ErrorCode.VENUE_NOT_FOUND);
        assertError(() -> venueService.queryVenueShowtimes(disabled.getId(), 1L, LocalDate.now()), ErrorCode.VENUE_NOT_FOUND);
        assertError(() -> venueService.queryVenueShowtimes(
                enabled.getId(),
                999999999L,
                LocalDate.now()
        ), ErrorCode.MOVIE_NOT_FOUND);
    }

    private Venue insertVenue(int status, String name) {
        Venue venue = new Venue();
        venue.setName(name);
        venue.setType("cinema");
        venue.setCity("上海");
        venue.setAddress("场次测试地址");
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

    private void insertShowtime(Venue venue,
                                Hall hall,
                                Long movieId,
                                LocalDateTime startTime,
                                int status,
                                String basePrice) {
        Showtime showtime = new Showtime();
        showtime.setBizType("MOVIE");
        showtime.setBizId(movieId);
        showtime.setVenueId(venue.getId());
        showtime.setHallId(hall.getId());
        showtime.setStartTime(startTime);
        showtime.setEndTime(startTime.plusHours(2));
        showtime.setBasePrice(new BigDecimal(basePrice));
        showtime.setTotalSeats(100);
        showtime.setAvailableSeats(100);
        showtime.setStatus(status);
        showtimeMapper.insert(showtime);
    }

    private void assertError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(errorCode);
    }
}
