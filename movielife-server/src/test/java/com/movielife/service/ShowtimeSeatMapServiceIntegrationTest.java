package com.movielife.service;

import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Movie;
import com.movielife.entity.Seat;
import com.movielife.entity.Showtime;
import com.movielife.entity.ShowtimeSeat;
import com.movielife.entity.Venue;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.vo.SeatVO;
import com.movielife.vo.ShowtimeSeatMapVO;
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
class ShowtimeSeatMapServiceIntegrationTest {

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ShowtimeService showtimeService;
    @Autowired
    private MovieMapper movieMapper;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private VenueMapper venueMapper;
    @Autowired
    private HallMapper hallMapper;
    @Autowired
    private SeatMapper seatMapper;
    @Autowired
    private ShowtimeMapper showtimeMapper;
    @Autowired
    private ShowtimeSeatMapper showtimeSeatMapper;

    @Test
    void shouldReturnEnabledSeatsInPhysicalOrderWithShowtimeStateAndPrice() {
        Context context = insertContext(1, 1, LocalDateTime.now().plusDays(1));
        Seat rowTwo = insertSeat(context.hall, 2, 1, "2排1座", "NORMAL", 1);
        Seat rowOneSecond = insertSeat(context.hall, 1, 2, "1排2座", "VIP", 1);
        Seat rowOneFirst = insertSeat(context.hall, 1, 1, "1排1座", "NORMAL", 1);
        Seat disabled = insertSeat(context.hall, 1, 3, "1排3座", "NORMAL", 0);
        insertShowtimeSeat(context.showtime, rowTwo, 1, "45.00");
        insertShowtimeSeat(context.showtime, rowOneSecond, 2, "60.00");
        insertShowtimeSeat(context.showtime, rowOneFirst, 0, "45.00");
        insertShowtimeSeat(context.showtime, disabled, 0, "45.00");

        ShowtimeSeatMapVO result = showtimeService.querySeatMap(context.showtime.getId());

        assertThat(result.getShowtimeId()).isEqualTo(context.showtime.getId());
        assertThat(result.getHallId()).isEqualTo(context.hall.getId());
        assertThat(result.getHallName()).isEqualTo(context.hall.getName());
        assertThat(result.getBasePrice()).isEqualByComparingTo("45.00");
        assertThat(result.getSeats()).extracting(SeatVO::getSeatNo)
                .containsExactly("1排1座", "1排2座", "2排1座");
        assertThat(result.getSeats()).extracting(SeatVO::getStatus)
                .containsExactly(0, 2, 1);
        assertThat(result.getSeats()).extracting(SeatVO::getPrice)
                .containsExactly(
                        new BigDecimal("45.00"),
                        new BigDecimal("60.00"),
                        new BigDecimal("45.00")
                );
    }

    @Test
    void hallWithoutEnabledSeatsShouldReturnEmptyList() {
        Context context = insertContext(1, 1, LocalDateTime.now().plusDays(1));
        insertSeat(context.hall, 1, 1, "停用座位", "NORMAL", 0);

        ShowtimeSeatMapVO result = showtimeService.querySeatMap(context.showtime.getId());

        assertThat(result.getSeats()).isEmpty();
    }

    @Test
    void missingShowtimeSeatStateShouldReturnInternalError() {
        Context context = insertContext(1, 1, LocalDateTime.now().plusDays(1));
        Seat complete = insertSeat(context.hall, 1, 1, "1排1座", "NORMAL", 1);
        insertSeat(context.hall, 1, 2, "1排2座", "NORMAL", 1);
        insertShowtimeSeat(context.showtime, complete, 0, "45.00");

        assertError(() -> showtimeService.querySeatMap(context.showtime.getId()), ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void shouldReuseShowtimeVisibilityRules() {
        Context stopped = insertContext(1, 0, LocalDateTime.now().plusDays(1));
        Context expired = insertContext(1, 1, LocalDateTime.now().minusHours(1));
        Context disabledVenue = insertContext(0, 1, LocalDateTime.now().plusDays(1));

        assertError(() -> showtimeService.querySeatMap(0L), ErrorCode.INVALID_ID);
        assertError(() -> showtimeService.querySeatMap(999999999L), ErrorCode.SHOWTIME_NOT_FOUND);
        assertError(() -> showtimeService.querySeatMap(stopped.showtime.getId()), ErrorCode.SHOWTIME_NOT_FOUND);
        assertError(() -> showtimeService.querySeatMap(expired.showtime.getId()), ErrorCode.SHOWTIME_NOT_FOUND);
        assertError(() -> showtimeService.querySeatMap(disabledVenue.showtime.getId()), ErrorCode.SHOWTIME_NOT_FOUND);
    }

    @Test
    void activitySeatMapShouldRequireConsistentPhysicalSeatFacts() {
        Context context = insertActivityContext(2, 1);
        Seat available = insertSeat(context.hall, 1, 1, "A01", "NORMAL", 1);
        Seat sold = insertSeat(context.hall, 1, 2, "A02", "VIP", 1);
        insertShowtimeSeat(context.showtime, available, 0, "199.00");
        insertShowtimeSeat(context.showtime, sold, 2, "299.00");

        ShowtimeSeatMapVO result = showtimeService.querySeatMap(context.showtime.getId());

        assertThat(result.getSeats()).extracting(SeatVO::getSeatNo)
                .containsExactly("A01", "A02");
        assertThat(result.getSeats()).extracting(SeatVO::getStatus)
                .containsExactly(0, 2);
    }

    @Test
    void activityHallWithoutEnabledSeatsShouldReturnInternalError() {
        Context context = insertActivityContext(1, 1);

        assertError(() -> showtimeService.querySeatMap(context.showtime.getId()),
                ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void activitySeatOrAvailableCountMismatchShouldReturnInternalError() {
        Context seatCountMismatch = insertActivityContext(2, 1);
        Seat onlySeat = insertSeat(seatCountMismatch.hall, 1, 1, "M01", "NORMAL", 1);
        insertShowtimeSeat(seatCountMismatch.showtime, onlySeat, 0, "199.00");

        Context availableCountMismatch = insertActivityContext(1, 0);
        Seat available = insertSeat(availableCountMismatch.hall, 1, 1, "M02", "NORMAL", 1);
        insertShowtimeSeat(availableCountMismatch.showtime, available, 0, "199.00");

        assertError(() -> showtimeService.querySeatMap(seatCountMismatch.showtime.getId()),
                ErrorCode.INTERNAL_ERROR);
        assertError(() -> showtimeService.querySeatMap(availableCountMismatch.showtime.getId()),
                ErrorCode.INTERNAL_ERROR);
    }

    private Context insertContext(int venueStatus, int showtimeStatus, LocalDateTime startTime) {
        Movie movie = new Movie();
        movie.setTitle("座位图测试电影");
        movie.setAvgScore(BigDecimal.ZERO);
        movie.setScoreCount(0);
        movie.setStatus(1);
        movieMapper.insert(movie);

        Venue venue = new Venue();
        venue.setName("座位图测试影院");
        venue.setType("cinema");
        venue.setCity("上海");
        venue.setAddress("座位图测试地址");
        venue.setAvgScore(BigDecimal.ZERO);
        venue.setStatus(venueStatus);
        venueMapper.insert(venue);

        Hall hall = new Hall();
        hall.setVenueId(venue.getId());
        hall.setName("座位图测试厅");
        hallMapper.insert(hall);

        Showtime showtime = new Showtime();
        showtime.setBizType("MOVIE");
        showtime.setBizId(movie.getId());
        showtime.setVenueId(venue.getId());
        showtime.setHallId(hall.getId());
        showtime.setStartTime(startTime.withNano(0));
        showtime.setEndTime(startTime.plusHours(2).withNano(0));
        showtime.setBasePrice(new BigDecimal("45.00"));
        showtime.setTotalSeats(10);
        showtime.setAvailableSeats(10);
        showtime.setStatus(showtimeStatus);
        showtimeMapper.insert(showtime);
        return new Context(hall, showtime);
    }

    private Context insertActivityContext(int totalSeats, int availableSeats) {
        Activity activity = new Activity();
        activity.setTitle("座位图测试活动");
        activity.setType("drama");
        activity.setCity("上海");
        activity.setPriceMin(new BigDecimal("199.00"));
        activity.setPriceMax(new BigDecimal("299.00"));
        activity.setStatus(1);
        activityMapper.insert(activity);

        Venue venue = new Venue();
        venue.setName("座位图测试剧场");
        venue.setType("theater");
        venue.setCity("上海");
        venue.setAddress("活动座位图测试地址");
        venue.setAvgScore(BigDecimal.ZERO);
        venue.setStatus(1);
        venueMapper.insert(venue);

        Hall hall = new Hall();
        hall.setVenueId(venue.getId());
        hall.setName("活动座位图测试厅");
        hallMapper.insert(hall);

        LocalDateTime startTime = LocalDateTime.now().plusDays(1).withNano(0);
        Showtime showtime = new Showtime();
        showtime.setBizType("ACTIVITY");
        showtime.setBizId(activity.getId());
        showtime.setVenueId(venue.getId());
        showtime.setHallId(hall.getId());
        showtime.setStartTime(startTime);
        showtime.setEndTime(startTime.plusHours(2));
        showtime.setBasePrice(new BigDecimal("199.00"));
        showtime.setTotalSeats(totalSeats);
        showtime.setAvailableSeats(availableSeats);
        showtime.setStatus(1);
        showtimeMapper.insert(showtime);
        return new Context(hall, showtime);
    }

    private Seat insertSeat(Hall hall,
                            int rowNo,
                            int colNo,
                            String seatNo,
                            String seatType,
                            int baseStatus) {
        Seat seat = new Seat();
        seat.setHallId(hall.getId());
        seat.setRowNo(rowNo);
        seat.setColNo(colNo);
        seat.setSeatNo(seatNo);
        seat.setSeatType(seatType);
        seat.setBaseStatus(baseStatus);
        seatMapper.insert(seat);
        return seat;
    }

    private void insertShowtimeSeat(Showtime showtime, Seat seat, int status, String price) {
        ShowtimeSeat showtimeSeat = new ShowtimeSeat();
        showtimeSeat.setShowtimeId(showtime.getId());
        showtimeSeat.setSeatId(seat.getId());
        showtimeSeat.setStatus(status);
        showtimeSeat.setPrice(new BigDecimal(price));
        showtimeSeat.setVersion(0);
        showtimeSeatMapper.insert(showtimeSeat);
    }

    private void assertError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(errorCode);
    }

    private static final class Context {

        private final Hall hall;
        private final Showtime showtime;

        private Context(Hall hall, Showtime showtime) {
            this.hall = hall;
            this.showtime = showtime;
        }
    }
}
