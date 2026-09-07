package com.movielife.service.impl;

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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketOrderCancellationServiceTest {

    @Mock private TicketOrderMapper ticketOrderMapper;
    @Mock private TicketOrderItemMapper ticketOrderItemMapper;
    @Mock private ShowtimeSeatMapper showtimeSeatMapper;
    @Mock private ShowtimeMapper showtimeMapper;

    private TicketOrderCancellationService service;

    @BeforeEach
    void setUp() {
        service = new TicketOrderCancellationService(ticketOrderMapper,
                ticketOrderItemMapper, showtimeSeatMapper, showtimeMapper);
    }

    @Test
    void unpaidOrderShouldReleaseAllSeatsRestoreCounterThenCancel() {
        TicketOrder order = order(1L, "UNPAID");
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(order);
        when(ticketOrderItemMapper.selectList(any())).thenReturn(Arrays.asList(
                item(101L), item(102L)));
        when(showtimeSeatMapper.releaseSeatsByOrder(
                1001L, Arrays.asList(101L, 102L), 9001L)).thenReturn(2);
        when(showtimeMapper.increaseAvailableSeats(1001L, 2)).thenReturn(1);
        when(ticketOrderMapper.cancelUnpaidOrder(9001L, 1L)).thenReturn(1);

        service.cancelOrder(1L, 9001L);

        InOrder writes = inOrder(showtimeSeatMapper, showtimeMapper, ticketOrderMapper);
        writes.verify(showtimeSeatMapper)
                .releaseSeatsByOrder(1001L, Arrays.asList(101L, 102L), 9001L);
        writes.verify(showtimeMapper).increaseAvailableSeats(1001L, 2);
        writes.verify(ticketOrderMapper).cancelUnpaidOrder(9001L, 1L);
    }

    @Test
    void canceledOrderShouldReturnIdempotentlyWithoutReleasingAgain() {
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(order(1L, "CANCELED"));

        service.cancelOrder(1L, 9001L);

        verify(ticketOrderItemMapper, never()).selectList(any());
        verify(showtimeSeatMapper, never()).releaseSeatsByOrder(any(), any(), any());
        verify(showtimeMapper, never()).increaseAvailableSeats(any(), any());
        verify(ticketOrderMapper, never()).cancelUnpaidOrder(any(), any());
    }

    @Test
    void missingAndForeignOrdersShouldBothBeHiddenAsNotFound() {
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(null);
        when(ticketOrderMapper.selectByIdForUpdate(9002L)).thenReturn(order(2L, "UNPAID"));

        assertError(() -> service.cancelOrder(1L, 9001L), ErrorCode.ORDER_NOT_FOUND);
        assertError(() -> service.cancelOrder(1L, 9002L), ErrorCode.ORDER_NOT_FOUND);
        verify(ticketOrderItemMapper, never()).selectList(any());
    }

    @Test
    void paidAndRefundedOrdersShouldConflict() {
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(order(1L, "PAID"));
        when(ticketOrderMapper.selectByIdForUpdate(9002L)).thenReturn(order(1L, "REFUNDED"));

        assertError(() -> service.cancelOrder(1L, 9001L), ErrorCode.ORDER_STATUS_CONFLICT);
        assertError(() -> service.cancelOrder(1L, 9002L), ErrorCode.ORDER_STATUS_CONFLICT);
        verify(showtimeSeatMapper, never()).releaseSeatsByOrder(any(), any(), any());
    }

    @Test
    void orderWithoutItemsShouldBeInternalError() {
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(order(1L, "UNPAID"));
        when(ticketOrderItemMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertError(() -> service.cancelOrder(1L, 9001L), ErrorCode.INTERNAL_ERROR);
        verify(showtimeSeatMapper, never()).releaseSeatsByOrder(any(), any(), any());
    }

    @Test
    void partialOrWrongOwnerSeatReleaseShouldFailBeforeCounterChange() {
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(order(1L, "UNPAID"));
        when(ticketOrderItemMapper.selectList(any())).thenReturn(Arrays.asList(
                item(101L), item(102L)));
        when(showtimeSeatMapper.releaseSeatsByOrder(
                1001L, Arrays.asList(101L, 102L), 9001L)).thenReturn(1);

        assertError(() -> service.cancelOrder(1L, 9001L), ErrorCode.INTERNAL_ERROR);
        verify(showtimeMapper, never()).increaseAvailableSeats(any(), any());
        verify(ticketOrderMapper, never()).cancelUnpaidOrder(any(), any());
    }

    @Test
    void counterFailureShouldPreventOrderStatusUpdate() {
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(order(1L, "UNPAID"));
        when(ticketOrderItemMapper.selectList(any())).thenReturn(Arrays.asList(
                item(101L), item(102L)));
        when(showtimeSeatMapper.releaseSeatsByOrder(
                1001L, Arrays.asList(101L, 102L), 9001L)).thenReturn(2);
        when(showtimeMapper.increaseAvailableSeats(1001L, 2)).thenReturn(0);

        assertError(() -> service.cancelOrder(1L, 9001L), ErrorCode.INTERNAL_ERROR);
        verify(ticketOrderMapper, never()).cancelUnpaidOrder(any(), any());
    }

    @Test
    void failedConditionalOrderTransitionShouldConflict() {
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(order(1L, "UNPAID"));
        when(ticketOrderItemMapper.selectList(any())).thenReturn(Arrays.asList(
                item(101L), item(102L)));
        when(showtimeSeatMapper.releaseSeatsByOrder(
                1001L, Arrays.asList(101L, 102L), 9001L)).thenReturn(2);
        when(showtimeMapper.increaseAvailableSeats(1001L, 2)).thenReturn(1);
        when(ticketOrderMapper.cancelUnpaidOrder(9001L, 1L)).thenReturn(0);

        assertError(() -> service.cancelOrder(1L, 9001L), ErrorCode.ORDER_STATUS_CONFLICT);
    }

    @Test
    void invalidOrderIdShouldFailBeforeDatabaseAccess() {
        assertError(() -> service.cancelOrder(1L, 0L), ErrorCode.INVALID_ID);
        verify(ticketOrderMapper, never()).selectByIdForUpdate(any());
    }

    @Test
    void expiredUnpaidOrderShouldReuseResourceReleaseAndCancel() {
        TicketOrder order = order(1L, "UNPAID");
        order.setExpireTime(LocalDateTime.now().minusMinutes(1));
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(order);
        when(ticketOrderItemMapper.selectList(any())).thenReturn(Arrays.asList(
                item(101L), item(102L)));
        when(showtimeSeatMapper.releaseSeatsByOrder(
                1001L, Arrays.asList(101L, 102L), 9001L)).thenReturn(2);
        when(showtimeMapper.increaseAvailableSeats(1001L, 2)).thenReturn(1);
        when(ticketOrderMapper.cancelExpiredUnpaidOrder(any(), any())).thenReturn(1);

        service.cancelExpiredOrder(9001L);

        verify(ticketOrderMapper).cancelExpiredUnpaidOrder(any(), any());
    }

    @Test
    void timeoutShouldSkipFutureCanceledPaidAndMissingOrders() {
        TicketOrder future = order(1L, "UNPAID");
        future.setExpireTime(LocalDateTime.now().plusMinutes(1));
        TicketOrder canceled = order(1L, "CANCELED");
        canceled.setExpireTime(LocalDateTime.now().minusMinutes(1));
        TicketOrder paid = order(1L, "PAID");
        paid.setExpireTime(LocalDateTime.now().minusMinutes(1));
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(future);
        when(ticketOrderMapper.selectByIdForUpdate(9002L)).thenReturn(canceled);
        when(ticketOrderMapper.selectByIdForUpdate(9003L)).thenReturn(paid);
        when(ticketOrderMapper.selectByIdForUpdate(9004L)).thenReturn(null);

        service.cancelExpiredOrder(9001L);
        service.cancelExpiredOrder(9002L);
        service.cancelExpiredOrder(9003L);
        service.cancelExpiredOrder(9004L);
        service.cancelExpiredOrder(0L);

        verify(ticketOrderItemMapper, never()).selectList(any());
        verify(showtimeSeatMapper, never()).releaseSeatsByOrder(any(), any(), any());
        verify(ticketOrderMapper, never()).cancelExpiredUnpaidOrder(any(), any());
    }

    private TicketOrder order(Long userId, String status) {
        TicketOrder order = new TicketOrder();
        order.setId(9001L);
        order.setUserId(userId);
        order.setShowtimeId(1001L);
        order.setStatus(status);
        return order;
    }

    private TicketOrderItem item(Long seatId) {
        TicketOrderItem item = new TicketOrderItem();
        item.setOrderId(9001L);
        item.setSeatId(seatId);
        return item;
    }

    private void assertError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
