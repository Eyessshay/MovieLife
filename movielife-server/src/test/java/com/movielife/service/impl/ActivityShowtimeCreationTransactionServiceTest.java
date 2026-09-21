package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.movielife.dto.CreateActivityShowtimeDTO;
import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Seat;
import com.movielife.entity.Showtime;
import com.movielife.entity.ShowtimeSeat;
import com.movielife.entity.Venue;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.mapper.VenueStaffMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityShowtimeCreationTransactionServiceTest {

    @Mock private VenueStaffMapper venueStaffMapper;
    @Mock private VenueMapper venueMapper;
    @Mock private ActivityMapper activityMapper;
    @Mock private HallMapper hallMapper;
    @Mock private SeatMapper seatMapper;
    @Mock private ShowtimeMapper showtimeMapper;
    @Mock private ShowtimeSeatMapper showtimeSeatMapper;

    private ActivityShowtimeCreationTransactionService service;

    @BeforeEach
    void setUp() {
        service = new ActivityShowtimeCreationTransactionService(venueStaffMapper, venueMapper,
                activityMapper, hallMapper, seatMapper, showtimeMapper, showtimeSeatMapper);
    }

    @Test
    void shouldCreateShowtimeAndAllSeatStatesWithOneBatch() {
        ActivityShowtimeCreationCommand command = command("req-1");
        when(venueStaffMapper.selectActiveId(7L, 501L)).thenReturn(71L);
        when(venueMapper.selectOne(any(Wrapper.class))).thenReturn(venue());
        when(activityMapper.selectOne(any(Wrapper.class))).thenReturn(activity());
        when(hallMapper.selectByIdForUpdate(601L)).thenReturn(hall(501L));
        when(showtimeMapper.selectOverlappingShowtimeForUpdate(601L,
                command.getStartTime(), command.getEndTime())).thenReturn(null);
        when(seatMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(seat(801L), seat(802L)));
        when(showtimeMapper.insert(any(Showtime.class))).thenAnswer(invocation -> {
            invocation.<Showtime>getArgument(0).setId(1001L);
            return 1;
        });
        when(showtimeSeatMapper.insertBatch(any())).thenReturn(2);

        Long result = service.create(7L, 501L, command);

        assertThat(result).isEqualTo(1001L);
        ArgumentCaptor<Showtime> showtimeCaptor = ArgumentCaptor.forClass(Showtime.class);
        verify(showtimeMapper).insert(showtimeCaptor.capture());
        Showtime showtime = showtimeCaptor.getValue();
        assertThat(showtime.getBizType()).isEqualTo("ACTIVITY");
        assertThat(showtime.getCreateRequestId()).isEqualTo("req-1");
        assertThat(showtime.getStatus()).isEqualTo(1);
        assertThat(showtime.getTotalSeats()).isEqualTo(2);
        assertThat(showtime.getAvailableSeats()).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ShowtimeSeat>> statesCaptor = ArgumentCaptor.forClass(List.class);
        verify(showtimeSeatMapper).insertBatch(statesCaptor.capture());
        assertThat(statesCaptor.getValue()).hasSize(2)
                .extracting(ShowtimeSeat::getPrice)
                .containsOnly(new BigDecimal("180.00"));
        assertThat(statesCaptor.getValue()).extracting(ShowtimeSeat::getStatus).containsOnly(0);
    }

    @Test
    void shouldReturnExistingOnlyWhenNormalizedRequestMatches() {
        ActivityShowtimeCreationCommand command = command("req-existing");
        Showtime existing = matchingShowtime(command);
        existing.setId(1001L);
        when(venueStaffMapper.selectActiveId(7L, 501L)).thenReturn(71L);
        when(showtimeMapper.selectByVenueAndCreateRequestId(501L, "req-existing"))
                .thenReturn(existing);

        assertThat(service.create(7L, 501L, command)).isEqualTo(1001L);
        verify(hallMapper, never()).selectByIdForUpdate(any());

        existing.setHallId(999L);
        assertError(() -> service.create(7L, 501L, command),
                ErrorCode.SHOWTIME_CREATE_REQUEST_MISMATCH);
    }

    @Test
    void shouldRejectScheduleConflictBeforeReadingSeats() {
        ActivityShowtimeCreationCommand command = readyThroughHall();
        when(showtimeMapper.selectOverlappingShowtimeForUpdate(601L,
                command.getStartTime(), command.getEndTime())).thenReturn(new Showtime());

        assertError(() -> service.create(7L, 501L, command),
                ErrorCode.SHOWTIME_SCHEDULE_CONFLICT);
        verify(seatMapper, never()).selectList(any(Wrapper.class));
        verify(showtimeMapper, never()).insert(any(Showtime.class));
    }

    @Test
    void shouldRejectHallWithoutEnabledPhysicalSeats() {
        ActivityShowtimeCreationCommand command = readyThroughHall();
        when(seatMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());

        assertError(() -> service.create(7L, 501L, command),
                ErrorCode.SHOWTIME_SEAT_REQUIRED);
        verify(showtimeMapper, never()).insert(any(Showtime.class));
    }

    @Test
    void shouldFailWhenBatchInsertCountDoesNotMatchSeatCount() {
        ActivityShowtimeCreationCommand command = readyThroughHall();
        when(seatMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(seat(801L), seat(802L)));
        when(showtimeMapper.insert(any(Showtime.class))).thenAnswer(invocation -> {
            invocation.<Showtime>getArgument(0).setId(1001L);
            return 1;
        });
        when(showtimeSeatMapper.insertBatch(any())).thenReturn(1);

        assertError(() -> service.create(7L, 501L, command), ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void shouldRequireVenueStaffAndOwnVenueHall() {
        ActivityShowtimeCreationCommand command = command("req-1");
        when(venueStaffMapper.selectActiveId(7L, 501L)).thenReturn(null);
        assertError(() -> service.create(7L, 501L, command),
                ErrorCode.VENUE_MANAGE_FORBIDDEN);

        when(venueStaffMapper.selectActiveId(7L, 501L)).thenReturn(71L);
        when(venueMapper.selectOne(any(Wrapper.class))).thenReturn(venue());
        when(activityMapper.selectOne(any(Wrapper.class))).thenReturn(activity());
        when(hallMapper.selectByIdForUpdate(601L)).thenReturn(hall(999L));
        assertError(() -> service.create(7L, 501L, command), ErrorCode.HALL_NOT_FOUND);
    }

    private ActivityShowtimeCreationCommand readyThroughHall() {
        ActivityShowtimeCreationCommand command = command("req-1");
        when(venueStaffMapper.selectActiveId(7L, 501L)).thenReturn(71L);
        when(venueMapper.selectOne(any(Wrapper.class))).thenReturn(venue());
        when(activityMapper.selectOne(any(Wrapper.class))).thenReturn(activity());
        when(hallMapper.selectByIdForUpdate(601L)).thenReturn(hall(501L));
        when(showtimeMapper.selectOverlappingShowtimeForUpdate(601L,
                command.getStartTime(), command.getEndTime())).thenReturn(null);
        return command;
    }

    private ActivityShowtimeCreationCommand command(String requestId) {
        CreateActivityShowtimeDTO request = new CreateActivityShowtimeDTO();
        request.setRequestId(requestId);
        request.setActivityId(301L);
        request.setHallId(601L);
        request.setStartTime(LocalDateTime.of(2035, 9, 20, 19, 30));
        request.setEndTime(LocalDateTime.of(2035, 9, 20, 21, 30));
        request.setBasePrice(new BigDecimal("180.00"));
        return ActivityShowtimeCreationCommand.from(request);
    }

    private Showtime matchingShowtime(ActivityShowtimeCreationCommand command) {
        Showtime showtime = new Showtime();
        showtime.setBizType("ACTIVITY");
        showtime.setBizId(command.getActivityId());
        showtime.setVenueId(501L);
        showtime.setHallId(command.getHallId());
        showtime.setCreateRequestId(command.getRequestId());
        showtime.setStartTime(command.getStartTime());
        showtime.setEndTime(command.getEndTime());
        showtime.setBasePrice(command.getBasePrice());
        return showtime;
    }

    private Venue venue() {
        Venue venue = new Venue();
        venue.setId(501L);
        venue.setStatus(1);
        return venue;
    }

    private Activity activity() {
        Activity activity = new Activity();
        activity.setId(301L);
        activity.setStatus(1);
        return activity;
    }

    private Hall hall(Long venueId) {
        Hall hall = new Hall();
        hall.setId(601L);
        hall.setVenueId(venueId);
        return hall;
    }

    private Seat seat(Long id) {
        Seat seat = new Seat();
        seat.setId(id);
        seat.setHallId(601L);
        seat.setBaseStatus(1);
        return seat;
    }

    private void assertError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
