package com.movielife.service.impl;

import com.movielife.entity.Showtime;
import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.TicketOrderItemMapper;
import com.movielife.mapper.TicketOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketOrderRefundServiceTest {

    @Mock private TicketOrderMapper ticketOrderMapper;
    @Mock private TicketOrderItemMapper ticketOrderItemMapper;
    @Mock private ShowtimeSeatMapper showtimeSeatMapper;
    @Mock private ShowtimeMapper showtimeMapper;

    private TicketOrderRefundService service;

    @BeforeEach
    void setUp() {
        service = new TicketOrderRefundService(ticketOrderMapper, ticketOrderItemMapper,
                showtimeSeatMapper, showtimeMapper);
    }

    @Test
    void paidOrderShouldRefundEveryOwnedSoldSeatAndTicket() {
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(order("PAID"));
        when(showtimeMapper.selectById(1001L)).thenReturn(showtime(LocalDateTime.now().plusHours(1)));
        when(ticketOrderItemMapper.selectList(any())).thenReturn(items());
        when(showtimeSeatMapper.releaseSoldSeatsByOrder(1001L, Arrays.asList(101L, 102L), 9001L))
                .thenReturn(2);
        when(showtimeMapper.increaseAvailableSeats(1001L, 2)).thenReturn(1);
        when(ticketOrderMapper.refundPaidOrder(9001L)).thenReturn(1);
        when(ticketOrderItemMapper.markRefundedByOrder(anyLong(), anyList(), anyList(), any(Integer.class)))
                .thenReturn(2);

        service.refundOrder(1L, 9001L);

        verify(showtimeSeatMapper).releaseSoldSeatsByOrder(1001L, Arrays.asList(101L, 102L), 9001L);
        verify(showtimeMapper).increaseAvailableSeats(1001L, 2);
        verify(ticketOrderMapper).refundPaidOrder(9001L);
        verify(ticketOrderItemMapper).markRefundedByOrder(anyLong(), anyList(), anyList(), any(Integer.class));
    }

    @Test
    void refundedOrderShouldBeIdempotentWithoutWrites() {
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(order("REFUNDED"));

        service.refundOrder(1L, 9001L);

        verify(showtimeMapper, never()).selectById(anyLong());
        verify(ticketOrderItemMapper, never()).selectList(any());
        verify(showtimeSeatMapper, never()).releaseSoldSeatsByOrder(any(), any(), any());
    }

    @Test
    void missingForeignAndNonPaidOrdersShouldBeRejected() {
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(null);
        when(ticketOrderMapper.selectByIdForUpdate(9002L)).thenReturn(orderForUser(2L, "PAID"));
        when(ticketOrderMapper.selectByIdForUpdate(9003L)).thenReturn(order("CANCELED"));

        assertError(() -> service.refundOrder(1L, 9001L), ErrorCode.ORDER_NOT_FOUND);
        assertError(() -> service.refundOrder(1L, 9002L), ErrorCode.ORDER_NOT_FOUND);
        assertError(() -> service.refundOrder(1L, 9003L), ErrorCode.ORDER_STATUS_CONFLICT);
    }

    @Test
    void startedShowtimeShouldRejectRefundBeforeAnySeatWrite() {
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(order("PAID"));
        when(showtimeMapper.selectById(1001L)).thenReturn(showtime(LocalDateTime.now().minusSeconds(1)));

        assertError(() -> service.refundOrder(1L, 9001L), ErrorCode.REFUND_NOT_ALLOWED);

        verify(ticketOrderItemMapper, never()).selectList(any());
        verify(showtimeSeatMapper, never()).releaseSoldSeatsByOrder(any(), any(), any());
    }

    @Test
    void seatOrCounterOrTicketUpdateMismatchShouldFail() {
        when(ticketOrderMapper.selectByIdForUpdate(anyLong())).thenReturn(order("PAID"));
        when(showtimeMapper.selectById(1001L)).thenReturn(showtime(LocalDateTime.now().plusHours(1)));
        when(ticketOrderItemMapper.selectList(any())).thenReturn(items());
        when(showtimeSeatMapper.releaseSoldSeatsByOrder(anyLong(), anyList(), anyLong())).thenReturn(1);
        assertError(() -> service.refundOrder(1L, 9001L), ErrorCode.INTERNAL_ERROR);

        when(showtimeSeatMapper.releaseSoldSeatsByOrder(anyLong(), anyList(), anyLong())).thenReturn(2);
        when(showtimeMapper.increaseAvailableSeats(1001L, 2)).thenReturn(0);
        assertError(() -> service.refundOrder(1L, 9002L), ErrorCode.INTERNAL_ERROR);

        when(showtimeMapper.increaseAvailableSeats(1001L, 2)).thenReturn(1);
        when(ticketOrderMapper.refundPaidOrder(anyLong())).thenReturn(1);
        when(ticketOrderItemMapper.markRefundedByOrder(anyLong(), anyList(), anyList(), any(Integer.class)))
                .thenReturn(1);
        assertError(() -> service.refundOrder(1L, 9003L), ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void invalidIdentifiersAndMissingItemsShouldBeRejected() {
        assertError(() -> service.refundOrder(null, 9001L), ErrorCode.UNAUTHORIZED);
        assertError(() -> service.refundOrder(1L, 0L), ErrorCode.INVALID_ID);
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(order("PAID"));
        when(showtimeMapper.selectById(1001L)).thenReturn(showtime(LocalDateTime.now().plusHours(1)));
        when(ticketOrderItemMapper.selectList(any())).thenReturn(Collections.emptyList());
        assertError(() -> service.refundOrder(1L, 9001L), ErrorCode.INTERNAL_ERROR);
    }

    private TicketOrder order(String status) { return orderForUser(1L, status); }

    private TicketOrder orderForUser(Long userId, String status) {
        TicketOrder order = new TicketOrder();
        order.setId(9001L);
        order.setUserId(userId);
        order.setShowtimeId(1001L);
        order.setStatus(status);
        return order;
    }

    private Showtime showtime(LocalDateTime startTime) {
        Showtime showtime = new Showtime();
        showtime.setId(1001L);
        showtime.setStartTime(startTime);
        return showtime;
    }

    private java.util.List<TicketOrderItem> items() {
        TicketOrderItem first = new TicketOrderItem(); first.setId(1L); first.setSeatId(101L);
        TicketOrderItem second = new TicketOrderItem(); second.setId(2L); second.setSeatId(102L);
        return Arrays.asList(first, second);
    }

    private void assertError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> org.assertj.core.api.Assertions.assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
