package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.movielife.dto.ShowtimeSeatInventoryStat;
import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Showtime;
import com.movielife.entity.Venue;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.mapper.VenueStaffMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityShowtimeSaleStatusTransactionServiceTest {

    @Mock private VenueStaffMapper venueStaffMapper;
    @Mock private VenueMapper venueMapper;
    @Mock private ActivityMapper activityMapper;
    @Mock private HallMapper hallMapper;
    @Mock private ShowtimeMapper showtimeMapper;
    @Mock private ShowtimeSeatMapper showtimeSeatMapper;

    private ActivityShowtimeSaleStatusTransactionService service;

    @BeforeEach
    void setUp() {
        service = new ActivityShowtimeSaleStatusTransactionService(venueStaffMapper, venueMapper,
                activityMapper, hallMapper, showtimeMapper, showtimeSeatMapper);
    }

    @Test
    void shouldStopOnSaleShowtimeWithoutTouchingInventoryFacts() {
        Showtime showtime = authorizedShowtime(1);
        when(showtimeMapper.updateActivitySaleStatus(eq(1001L), eq(501L),
                eq(1), eq(0), any(LocalDateTime.class))).thenReturn(1);

        assertThat(service.stopSale(7L, 501L, 1001L)).isEqualTo(1001L);
        verify(showtimeSeatMapper, never()).selectInventoryStat(any());
        verify(venueMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    void repeatedStopShouldBeIdempotentWithoutUpdate() {
        authorizedShowtime(0);

        assertThat(service.stopSale(7L, 501L, 1001L)).isEqualTo(1001L);
        verify(showtimeMapper, never()).updateActivitySaleStatus(
                any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void shouldResumeOnlyAfterCurrentFactsAreConsistent() {
        Showtime showtime = authorizedShowtime(0);
        stubValidResumeFacts(showtime);
        when(showtimeMapper.updateActivitySaleStatus(eq(1001L), eq(501L),
                eq(0), eq(1), any(LocalDateTime.class))).thenReturn(1);

        assertThat(service.resumeSale(7L, 501L, 1001L)).isEqualTo(1001L);
    }

    @Test
    void repeatedResumeShouldStillValidateFactsButSkipUpdate() {
        Showtime showtime = authorizedShowtime(1);
        stubValidResumeFacts(showtime);

        assertThat(service.resumeSale(7L, 501L, 1001L)).isEqualTo(1001L);
        verify(showtimeSeatMapper).selectInventoryStat(1001L);
        verify(showtimeMapper, never()).updateActivitySaleStatus(
                any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void shouldRejectStartedOrFinishedStatusShowtime() {
        Showtime started = authorizedShowtime(1);
        started.setStartTime(LocalDateTime.now().minusMinutes(1));
        assertError(() -> service.stopSale(7L, 501L, 1001L),
                ErrorCode.SHOWTIME_SALE_STATUS_CONFLICT);

        Showtime finished = showtime(2);
        when(showtimeMapper.selectActivityByIdAndVenueForUpdate(1002L, 501L))
                .thenReturn(finished);
        assertError(() -> service.stopSale(7L, 501L, 1002L),
                ErrorCode.SHOWTIME_SALE_STATUS_CONFLICT);
    }

    @Test
    void shouldRejectUnauthorizedOrHiddenTarget() {
        when(venueStaffMapper.selectActiveId(7L, 501L)).thenReturn(null);
        assertError(() -> service.stopSale(7L, 501L, 1001L),
                ErrorCode.VENUE_MANAGE_FORBIDDEN);

        when(venueStaffMapper.selectActiveId(7L, 501L)).thenReturn(71L);
        when(showtimeMapper.selectActivityByIdAndVenueForUpdate(1001L, 501L))
                .thenReturn(null);
        assertError(() -> service.stopSale(7L, 501L, 1001L),
                ErrorCode.SHOWTIME_NOT_FOUND);
    }

    @Test
    void shouldRejectResumeWhenInventoryDoesNotMatch() {
        Showtime showtime = authorizedShowtime(0);
        when(venueMapper.selectOne(any(Wrapper.class))).thenReturn(venue());
        when(activityMapper.selectOne(any(Wrapper.class))).thenReturn(activity());
        when(hallMapper.selectOne(any(Wrapper.class))).thenReturn(hall());
        ShowtimeSeatInventoryStat stat = new ShowtimeSeatInventoryStat();
        stat.setTotalCount(2L);
        stat.setAvailableCount(1L);
        when(showtimeSeatMapper.selectInventoryStat(1001L)).thenReturn(stat);

        assertError(() -> service.resumeSale(7L, 501L, 1001L),
                ErrorCode.SHOWTIME_INVENTORY_INCONSISTENT);
        verify(showtimeMapper, never()).updateActivitySaleStatus(
                any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void shouldRejectConditionalUpdateMiss() {
        authorizedShowtime(1);
        when(showtimeMapper.updateActivitySaleStatus(eq(1001L), eq(501L),
                eq(1), eq(0), any(LocalDateTime.class))).thenReturn(0);

        assertError(() -> service.stopSale(7L, 501L, 1001L),
                ErrorCode.SHOWTIME_SALE_STATUS_CONFLICT);
    }

    private Showtime authorizedShowtime(int status) {
        when(venueStaffMapper.selectActiveId(7L, 501L)).thenReturn(71L);
        Showtime showtime = showtime(status);
        when(showtimeMapper.selectActivityByIdAndVenueForUpdate(1001L, 501L))
                .thenReturn(showtime);
        return showtime;
    }

    private Showtime showtime(int status) {
        Showtime showtime = new Showtime();
        showtime.setId(1001L);
        showtime.setBizType("ACTIVITY");
        showtime.setBizId(301L);
        showtime.setVenueId(501L);
        showtime.setHallId(601L);
        showtime.setStartTime(LocalDateTime.now().plusDays(2));
        showtime.setEndTime(LocalDateTime.now().plusDays(2).plusHours(2));
        showtime.setStatus(status);
        showtime.setTotalSeats(2);
        showtime.setAvailableSeats(2);
        return showtime;
    }

    private void stubValidResumeFacts(Showtime showtime) {
        when(venueMapper.selectOne(any(Wrapper.class))).thenReturn(venue());
        when(activityMapper.selectOne(any(Wrapper.class))).thenReturn(activity());
        when(hallMapper.selectOne(any(Wrapper.class))).thenReturn(hall());
        ShowtimeSeatInventoryStat stat = new ShowtimeSeatInventoryStat();
        stat.setTotalCount(Long.valueOf(showtime.getTotalSeats()));
        stat.setAvailableCount(Long.valueOf(showtime.getAvailableSeats()));
        when(showtimeSeatMapper.selectInventoryStat(showtime.getId())).thenReturn(stat);
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

    private Hall hall() {
        Hall hall = new Hall();
        hall.setId(601L);
        hall.setVenueId(501L);
        return hall;
    }

    private void assertError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
