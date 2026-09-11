package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Movie;
import com.movielife.entity.Seat;
import com.movielife.entity.Showtime;
import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.entity.Venue;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.TicketOrderItemMapper;
import com.movielife.mapper.TicketOrderMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.vo.PageVO;
import com.movielife.vo.TicketOrderDetailVO;
import com.movielife.vo.TicketOrderListItemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketOrderQueryServiceTest {

    @Mock private TicketOrderMapper ticketOrderMapper;
    @Mock private TicketOrderItemMapper ticketOrderItemMapper;
    @Mock private ShowtimeMapper showtimeMapper;
    @Mock private MovieMapper movieMapper;
    @Mock private ActivityMapper activityMapper;
    @Mock private VenueMapper venueMapper;
    @Mock private HallMapper hallMapper;
    @Mock private SeatMapper seatMapper;

    private TicketOrderQueryService service;

    @BeforeEach
    void setUp() {
        service = new TicketOrderQueryService(ticketOrderMapper, ticketOrderItemMapper,
                showtimeMapper, movieMapper, activityMapper, venueMapper, hallMapper, seatMapper);
    }

    @Test
    void listShouldBatchLoadAssociationsAndKeepPageOrder() {
        TicketOrder newer = order(102L, 2L, 12L, "PAID");
        TicketOrder older = order(101L, 1L, 11L, "UNPAID");
        Page<TicketOrder> databasePage = new Page<>(1, 20);
        databasePage.setRecords(Arrays.asList(newer, older));
        databasePage.setTotal(2);
        when(ticketOrderMapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenReturn(databasePage);
        when(ticketOrderItemMapper.selectList(any())).thenReturn(Arrays.asList(
                item(102L, 22L, "55.00", null, 0),
                item(101L, 11L, "40.00", null, 0),
                item(101L, 12L, "45.00", "TICKET-12", 1)));
        when(showtimeMapper.selectBatchIds(any())).thenReturn(Arrays.asList(
                showtime(12L, "ACTIVITY", 202L, 302L, 402L),
                showtime(11L, 201L, 301L, 401L)));
        when(movieMapper.selectBatchIds(any())).thenReturn(
                Collections.singletonList(movie(201L, "Movie A")));
        Activity offlineActivity = activity(202L, "Activity B");
        offlineActivity.setStatus(0);
        when(activityMapper.selectBatchIds(any())).thenReturn(
                Collections.singletonList(offlineActivity));
        when(venueMapper.selectBatchIds(any())).thenReturn(Arrays.asList(
                venue(301L, "Venue A"), venue(302L, "Venue B")));
        when(hallMapper.selectBatchIds(any())).thenReturn(Arrays.asList(
                hall(401L, "Hall A"), hall(402L, "Hall B")));

        PageVO<TicketOrderListItemVO> result = service.queryMyOrders(7L, 1, 20);

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getPageSize()).isEqualTo(20);
        assertThat(result.getList()).extracting(TicketOrderListItemVO::getOrderId)
                .containsExactly(102L, 101L);
        assertThat(result.getList()).extracting(TicketOrderListItemVO::getSeatCount)
                .containsExactly(1, 2);
        assertThat(result.getList().get(0).getBizType()).isEqualTo("ACTIVITY");
        assertThat(result.getList().get(0).getActivityId()).isEqualTo(202L);
        assertThat(result.getList().get(0).getActivityName()).isEqualTo("Activity B");
        assertThat(result.getList().get(0).getMovieId()).isNull();
        assertThat(result.getList().get(1).getBizType()).isEqualTo("MOVIE");
        assertThat(result.getList().get(1).getMovieName()).isEqualTo("Movie A");
        assertThat(result.getList().get(1).getActivityId()).isNull();
        assertThat(result.getList().get(1).getVenueName()).isEqualTo("Venue A");
        assertThat(result.getList().get(1).getHallName()).isEqualTo("Hall A");
        verify(ticketOrderItemMapper, times(1)).selectList(any());
        verify(showtimeMapper, times(1)).selectBatchIds(any());
        verify(movieMapper, times(1)).selectBatchIds(any());
        verify(activityMapper, times(1)).selectBatchIds(any());
        verify(venueMapper, times(1)).selectBatchIds(any());
        verify(hallMapper, times(1)).selectBatchIds(any());
    }

    @Test
    void emptyListShouldReturnEmptyPageWithoutAssociationQueries() {
        Page<TicketOrder> databasePage = new Page<>(3, 20);
        databasePage.setRecords(Collections.emptyList());
        databasePage.setTotal(0);
        when(ticketOrderMapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenReturn(databasePage);

        PageVO<TicketOrderListItemVO> result = service.queryMyOrders(7L, 3, 20);

        assertThat(result.getList()).isEmpty();
        assertThat(result.getTotal()).isZero();
        verify(ticketOrderItemMapper, never()).selectList(any());
        verify(showtimeMapper, never()).selectBatchIds(any());
    }

    @Test
    void listShouldRejectInvalidPagination() {
        assertError(() -> service.queryMyOrders(7L, 0, 20), ErrorCode.INVALID_PAGE);
        assertError(() -> service.queryMyOrders(7L, 1, 0), ErrorCode.PAGE_SIZE_TOO_LARGE);
        assertError(() -> service.queryMyOrders(7L, 1, 101), ErrorCode.PAGE_SIZE_TOO_LARGE);
        verify(ticketOrderMapper, never()).selectPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    void listShouldFailWhenAnAssociationOrItemsAreMissing() {
        Page<TicketOrder> databasePage = new Page<>(1, 20);
        databasePage.setRecords(Collections.singletonList(order(101L, 1L, 11L, "UNPAID")));
        databasePage.setTotal(1);
        when(ticketOrderMapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenReturn(databasePage);
        when(ticketOrderItemMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertError(() -> service.queryMyOrders(7L, 1, 20), ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void listShouldFailWhenActivityRecordIsMissing() {
        Page<TicketOrder> databasePage = new Page<>(1, 20);
        databasePage.setRecords(Collections.singletonList(order(101L, 7L, 11L, "UNPAID")));
        databasePage.setTotal(1);
        when(ticketOrderMapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenReturn(databasePage);
        when(ticketOrderItemMapper.selectList(any())).thenReturn(
                Collections.singletonList(item(101L, 11L, "120.00", null, 0)));
        when(showtimeMapper.selectBatchIds(any())).thenReturn(
                Collections.singletonList(showtime(11L, "ACTIVITY", 201L, 301L, 401L)));
        when(activityMapper.selectBatchIds(any())).thenReturn(Collections.emptyList());

        assertError(() -> service.queryMyOrders(7L, 1, 20), ErrorCode.INTERNAL_ERROR);

        verify(movieMapper, never()).selectBatchIds(any());
    }

    @Test
    void detailShouldReturnSnapshotItemsSortedByPhysicalSeat() {
        TicketOrder order = order(101L, 7L, 11L, "PAID");
        order.setPayTime(LocalDateTime.of(2026, 9, 4, 12, 0));
        when(ticketOrderMapper.selectById(101L)).thenReturn(order);
        when(showtimeMapper.selectById(11L)).thenReturn(showtime(11L, 201L, 301L, 401L));
        when(movieMapper.selectById(201L)).thenReturn(movie(201L, "Movie A"));
        when(venueMapper.selectById(301L)).thenReturn(venue(301L, "Venue A"));
        when(hallMapper.selectById(401L)).thenReturn(hall(401L, "Hall A"));
        when(ticketOrderItemMapper.selectList(any())).thenReturn(Arrays.asList(
                item(101L, 22L, "55.00", "T-22", 1),
                item(101L, 11L, "40.00", null, 0),
                item(101L, 12L, "45.00", "T-12", 2)));
        when(seatMapper.selectBatchIds(any())).thenReturn(Arrays.asList(
                seat(22L, 2, 1, "B1"),
                seat(11L, 1, 2, "A2"),
                seat(12L, 1, 1, "A1")));

        TicketOrderDetailVO result = service.queryOrderDetail(7L, 101L);

        assertThat(result.getOrderId()).isEqualTo(101L);
        assertThat(result.getMovieName()).isEqualTo("Movie A");
        assertThat(result.getBizType()).isEqualTo("MOVIE");
        assertThat(result.getActivityId()).isNull();
        assertThat(result.getVenueName()).isEqualTo("Venue A");
        assertThat(result.getHallName()).isEqualTo("Hall A");
        assertThat(result.getStatus()).isEqualTo("PAID");
        assertThat(result.getPayTime()).isEqualTo(order.getPayTime());
        assertThat(result.getItems()).extracting(item -> item.getSeatId())
                .containsExactly(12L, 11L, 22L);
        assertThat(result.getItems()).extracting(item -> item.getPrice())
                .containsExactly(new BigDecimal("45.00"), new BigDecimal("40.00"),
                        new BigDecimal("55.00"));
        assertThat(result.getItems().get(0).getTicketCode()).isEqualTo("T-12");
        assertThat(result.getItems().get(0).getTicketStatus()).isEqualTo(2);
        verify(seatMapper, times(1)).selectBatchIds(any());
    }

    @Test
    void activityDetailShouldReturnOfflineActivityAndKeepMovieFieldsEmpty() {
        TicketOrder order = order(101L, 7L, 11L, "UNPAID");
        when(ticketOrderMapper.selectById(101L)).thenReturn(order);
        when(showtimeMapper.selectById(11L))
                .thenReturn(showtime(11L, "ACTIVITY", 201L, 301L, 401L));
        Activity offlineActivity = activity(201L, "Offline Activity");
        offlineActivity.setStatus(0);
        when(activityMapper.selectById(201L)).thenReturn(offlineActivity);
        when(venueMapper.selectById(301L)).thenReturn(venue(301L, "Venue A"));
        when(hallMapper.selectById(401L)).thenReturn(hall(401L, "Hall A"));
        when(ticketOrderItemMapper.selectList(any())).thenReturn(
                Collections.singletonList(item(101L, 11L, "120.00", null, 0)));
        when(seatMapper.selectBatchIds(any())).thenReturn(
                Collections.singletonList(seat(11L, 1, 1, "A1")));

        TicketOrderDetailVO result = service.queryOrderDetail(7L, 101L);

        assertThat(result.getBizType()).isEqualTo("ACTIVITY");
        assertThat(result.getActivityId()).isEqualTo(201L);
        assertThat(result.getActivityName()).isEqualTo("Offline Activity");
        assertThat(result.getMovieId()).isNull();
        assertThat(result.getMovieName()).isNull();
        verify(movieMapper, never()).selectById(any());
    }

    @Test
    void unknownBusinessTypeShouldFailWithoutBusinessResourceQuery() {
        TicketOrder order = order(101L, 7L, 11L, "UNPAID");
        when(ticketOrderMapper.selectById(101L)).thenReturn(order);
        when(showtimeMapper.selectById(11L))
                .thenReturn(showtime(11L, "UNKNOWN", 201L, 301L, 401L));

        assertError(() -> service.queryOrderDetail(7L, 101L), ErrorCode.INTERNAL_ERROR);

        verify(movieMapper, never()).selectById(any());
        verify(activityMapper, never()).selectById(any());
        verify(ticketOrderItemMapper, never()).selectList(any());
    }

    @Test
    void missingAndForeignOrdersShouldBothReturnNotFound() {
        when(ticketOrderMapper.selectById(999L)).thenReturn(null);
        TicketOrder foreign = order(101L, 8L, 11L, "UNPAID");
        when(ticketOrderMapper.selectById(101L)).thenReturn(foreign);

        assertError(() -> service.queryOrderDetail(7L, 999L), ErrorCode.ORDER_NOT_FOUND);
        assertError(() -> service.queryOrderDetail(7L, 101L), ErrorCode.ORDER_NOT_FOUND);
        verify(showtimeMapper, never()).selectById(any());
    }

    @Test
    void detailShouldRejectInvalidId() {
        assertError(() -> service.queryOrderDetail(7L, 0L), ErrorCode.INVALID_ID);
        verify(ticketOrderMapper, never()).selectById(any());
    }

    @Test
    void detailShouldFailWhenSeatOrContextDataIsMissing() {
        TicketOrder order = order(101L, 7L, 11L, "UNPAID");
        when(ticketOrderMapper.selectById(101L)).thenReturn(order);
        when(showtimeMapper.selectById(11L)).thenReturn(showtime(11L, 201L, 301L, 401L));
        when(movieMapper.selectById(201L)).thenReturn(movie(201L, "Movie A"));
        when(venueMapper.selectById(301L)).thenReturn(venue(301L, "Venue A"));
        when(hallMapper.selectById(401L)).thenReturn(hall(401L, "Hall A"));
        when(ticketOrderItemMapper.selectList(any())).thenReturn(
                Collections.singletonList(item(101L, 11L, "40.00", null, 0)));
        when(seatMapper.selectBatchIds(any())).thenReturn(Collections.emptyList());

        assertError(() -> service.queryOrderDetail(7L, 101L), ErrorCode.INTERNAL_ERROR);
    }

    private void assertError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    private TicketOrder order(Long id, Long userId, Long showtimeId, String status) {
        TicketOrder order = new TicketOrder();
        order.setId(id);
        order.setUserId(userId);
        order.setShowtimeId(showtimeId);
        order.setStatus(status);
        order.setTotalAmount(new BigDecimal("95.00"));
        order.setExpireTime(LocalDateTime.of(2026, 9, 4, 13, 0));
        order.setCreateTime(LocalDateTime.of(2026, 9, 4, 11, 0).plusMinutes(id));
        return order;
    }

    private TicketOrderItem item(Long orderId, Long seatId, String price,
                                 String ticketCode, Integer ticketStatus) {
        TicketOrderItem item = new TicketOrderItem();
        item.setOrderId(orderId);
        item.setSeatId(seatId);
        item.setPrice(new BigDecimal(price));
        item.setTicketCode(ticketCode);
        item.setTicketStatus(ticketStatus);
        return item;
    }

    private Showtime showtime(Long id, Long movieId, Long venueId, Long hallId) {
        return showtime(id, "MOVIE", movieId, venueId, hallId);
    }

    private Showtime showtime(Long id, String bizType, Long bizId,
                              Long venueId, Long hallId) {
        Showtime showtime = new Showtime();
        showtime.setId(id);
        showtime.setBizType(bizType);
        showtime.setBizId(bizId);
        showtime.setVenueId(venueId);
        showtime.setHallId(hallId);
        showtime.setStartTime(LocalDateTime.of(2026, 9, 5, 16, 0));
        showtime.setEndTime(LocalDateTime.of(2026, 9, 5, 18, 0));
        return showtime;
    }

    private Activity activity(Long id, String title) {
        Activity activity = new Activity();
        activity.setId(id);
        activity.setTitle(title);
        activity.setStatus(1);
        return activity;
    }

    private Movie movie(Long id, String title) {
        Movie movie = new Movie();
        movie.setId(id);
        movie.setTitle(title);
        return movie;
    }

    private Venue venue(Long id, String name) {
        Venue venue = new Venue();
        venue.setId(id);
        venue.setName(name);
        return venue;
    }

    private Hall hall(Long id, String name) {
        Hall hall = new Hall();
        hall.setId(id);
        hall.setName(name);
        return hall;
    }

    private Seat seat(Long id, int rowNo, int colNo, String seatNo) {
        Seat seat = new Seat();
        seat.setId(id);
        seat.setRowNo(rowNo);
        seat.setColNo(colNo);
        seat.setSeatNo(seatNo);
        seat.setSeatType("NORMAL");
        return seat;
    }
}
