package com.movielife.service;

import com.movielife.entity.Hall;
import com.movielife.entity.Movie;
import com.movielife.entity.Seat;
import com.movielife.entity.Showtime;
import com.movielife.entity.ShowtimeSeat;
import com.movielife.entity.Venue;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.service.impl.ShowtimeServiceImpl;
import com.movielife.vo.ShowtimeSeatMapVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShowtimeServiceImplTest {

    @Mock
    private ShowtimeMapper showtimeMapper;
    @Mock
    private VenueMapper venueMapper;
    @Mock
    private HallMapper hallMapper;
    @Mock
    private MovieMapper movieMapper;
    @Mock
    private SeatMapper seatMapper;
    @Mock
    private ShowtimeSeatMapper showtimeSeatMapper;

    private ShowtimeService showtimeService;

    @BeforeEach
    void setUp() {
        showtimeService = new ShowtimeServiceImpl(
                showtimeMapper,
                venueMapper,
                hallMapper,
                movieMapper,
                seatMapper,
                showtimeSeatMapper
        );
    }

    @Test
    void missingMovieRelationShouldReturnInternalError() {
        Showtime showtime = validShowtime();
        when(showtimeMapper.selectById(1L)).thenReturn(showtime);
        when(venueMapper.selectById(1L)).thenReturn(enabledVenue());
        when(hallMapper.selectById(1L)).thenReturn(hall(1L));
        when(movieMapper.selectById(1L)).thenReturn(null);

        assertInternalError(() -> showtimeService.queryShowtimeDetail(1L));
    }

    @Test
    void hallOwnedByAnotherVenueShouldReturnInternalError() {
        Showtime showtime = validShowtime();
        when(showtimeMapper.selectById(1L)).thenReturn(showtime);
        when(venueMapper.selectById(1L)).thenReturn(enabledVenue());
        when(hallMapper.selectById(1L)).thenReturn(hall(2L));
        when(movieMapper.selectById(1L)).thenReturn(new Movie());

        assertInternalError(() -> showtimeService.queryShowtimeDetail(1L));
    }

    @Test
    void missingShowtimeShouldNotBeAvailable() {
        when(showtimeMapper.selectById(1L)).thenReturn(null);

        assertShowtimeNotFound(() -> showtimeService.queryShowtimeDetail(1L));
    }

    @Test
    void expiredShowtimeShouldNotBeAvailable() {
        Showtime showtime = validShowtime();
        showtime.setStartTime(LocalDateTime.now().minusMinutes(1));
        when(showtimeMapper.selectById(1L)).thenReturn(showtime);

        assertShowtimeNotFound(() -> showtimeService.queryShowtimeDetail(1L));
    }

    @Test
    void disabledShowtimeShouldNotBeAvailable() {
        Showtime showtime = validShowtime();
        showtime.setStatus(0);
        when(showtimeMapper.selectById(1L)).thenReturn(showtime);

        assertShowtimeNotFound(() -> showtimeService.queryShowtimeDetail(1L));
    }

    @Test
    void seatMapShouldBatchLoadSeatTemplateAndShowtimeStatesOnce() {
        Showtime showtime = validShowtime();
        Seat first = seat(1L, 1, 1);
        Seat second = seat(2L, 1, 2);
        ShowtimeSeat firstState = state(1L, 0, "45.00");
        ShowtimeSeat secondState = state(2L, 2, "60.00");
        when(showtimeMapper.selectById(1L)).thenReturn(showtime);
        when(venueMapper.selectById(1L)).thenReturn(enabledVenue());
        when(hallMapper.selectById(1L)).thenReturn(hall(1L));
        when(movieMapper.selectById(1L)).thenReturn(new Movie());
        when(seatMapper.selectList(any())).thenReturn(Arrays.asList(first, second));
        when(showtimeSeatMapper.selectList(any())).thenReturn(Arrays.asList(firstState, secondState));

        ShowtimeSeatMapVO result = showtimeService.querySeatMap(1L);

        assertThat(result.getSeats()).extracting("seatId").containsExactly(1L, 2L);
        assertThat(result.getSeats()).extracting("status").containsExactly(0, 2);
        verify(seatMapper, times(1)).selectList(any());
        verify(showtimeSeatMapper, times(1)).selectList(any());
    }

    private Showtime validShowtime() {
        Showtime showtime = new Showtime();
        showtime.setId(1L);
        showtime.setBizType("MOVIE");
        showtime.setBizId(1L);
        showtime.setVenueId(1L);
        showtime.setHallId(1L);
        showtime.setStartTime(LocalDateTime.now().plusDays(1));
        showtime.setEndTime(LocalDateTime.now().plusDays(1).plusHours(2));
        showtime.setBasePrice(new BigDecimal("45.00"));
        showtime.setStatus(1);
        return showtime;
    }

    private Venue enabledVenue() {
        Venue venue = new Venue();
        venue.setId(1L);
        venue.setStatus(1);
        return venue;
    }

    private Hall hall(Long venueId) {
        Hall hall = new Hall();
        hall.setId(1L);
        hall.setVenueId(venueId);
        hall.setName("1号厅");
        return hall;
    }

    private Seat seat(Long id, int rowNo, int colNo) {
        Seat seat = new Seat();
        seat.setId(id);
        seat.setRowNo(rowNo);
        seat.setColNo(colNo);
        seat.setSeatNo(rowNo + "排" + colNo + "座");
        seat.setSeatType("NORMAL");
        return seat;
    }

    private ShowtimeSeat state(Long seatId, int status, String price) {
        ShowtimeSeat state = new ShowtimeSeat();
        state.setSeatId(seatId);
        state.setStatus(status);
        state.setPrice(new BigDecimal(price));
        return state;
    }

    private void assertInternalError(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    private void assertShowtimeNotFound(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.SHOWTIME_NOT_FOUND);
    }
}
