package com.movielife.service.impl;

import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.TicketOrderItemMapper;
import com.movielife.mapper.TicketOrderMapper;
import com.movielife.vo.PayTicketOrderVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketOrderPaymentServiceTest {

    @Mock private TicketOrderMapper ticketOrderMapper;
    @Mock private TicketOrderItemMapper ticketOrderItemMapper;
    @Mock private ShowtimeSeatMapper showtimeSeatMapper;

    private TicketOrderPaymentService service;

    @BeforeEach
    void setUp() {
        service = new TicketOrderPaymentService(
                ticketOrderMapper, ticketOrderItemMapper, showtimeSeatMapper);
    }

    @Test
    void unpaidOrderShouldBecomePaidAndSellAllOwnedSeats() {
        TicketOrder order = order(1L, "UNPAID", LocalDateTime.now().plusMinutes(10));
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(order);
        when(ticketOrderItemMapper.selectList(any())).thenReturn(items());
        when(showtimeSeatMapper.markSeatsSoldByOrder(
                1001L, Arrays.asList(101L, 102L), 9001L)).thenReturn(2);
        when(ticketOrderMapper.payUnpaidOrder(anyLong(), any())).thenReturn(1);

        PayTicketOrderVO result = service.payOrder(1L, 9001L);

        assertThat(result.getOrderId()).isEqualTo(9001L);
        assertThat(result.getStatus()).isEqualTo("PAID");
        assertThat(result.getPayTime()).isNotNull();
        verify(showtimeSeatMapper).markSeatsSoldByOrder(
                1001L, Arrays.asList(101L, 102L), 9001L);
        verify(ticketOrderMapper).payUnpaidOrder(anyLong(), any(LocalDateTime.class));
    }

    @Test
    void paidOrderShouldReturnOriginalPayTimeWithoutWritingAgain() {
        TicketOrder order = order(1L, "PAID", LocalDateTime.now().minusMinutes(1));
        LocalDateTime originalPayTime = LocalDateTime.now().minusMinutes(5);
        order.setPayTime(originalPayTime);
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(order);

        PayTicketOrderVO result = service.payOrder(1L, 9001L);

        assertThat(result.getPayTime()).isEqualTo(originalPayTime);
        verify(ticketOrderItemMapper, never()).selectList(any());
        verify(showtimeSeatMapper, never()).markSeatsSoldByOrder(any(), any(), any());
        verify(ticketOrderMapper, never()).payUnpaidOrder(any(), any());
    }

    @Test
    void missingOrForeignOrderShouldBeHiddenAsNotFound() {
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(null);
        when(ticketOrderMapper.selectByIdForUpdate(9002L))
                .thenReturn(order(2L, "UNPAID", LocalDateTime.now().plusMinutes(5)));

        assertError(() -> service.payOrder(1L, 9001L), ErrorCode.ORDER_NOT_FOUND);
        assertError(() -> service.payOrder(1L, 9002L), ErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    void canceledAndRefundedOrdersShouldConflict() {
        when(ticketOrderMapper.selectByIdForUpdate(9001L))
                .thenReturn(order(1L, "CANCELED", LocalDateTime.now().plusMinutes(5)));
        when(ticketOrderMapper.selectByIdForUpdate(9002L))
                .thenReturn(order(1L, "REFUNDED", LocalDateTime.now().plusMinutes(5)));

        assertError(() -> service.payOrder(1L, 9001L), ErrorCode.ORDER_STATUS_CONFLICT);
        assertError(() -> service.payOrder(1L, 9002L), ErrorCode.ORDER_STATUS_CONFLICT);
    }

    @Test
    void expiredUnpaidOrderShouldBeRejectedWithoutSellingSeats() {
        when(ticketOrderMapper.selectByIdForUpdate(9001L))
                .thenReturn(order(1L, "UNPAID", LocalDateTime.now().minusSeconds(1)));

        assertError(() -> service.payOrder(1L, 9001L), ErrorCode.ORDER_EXPIRED);

        verify(showtimeSeatMapper, never()).markSeatsSoldByOrder(any(), any(), any());
        verify(ticketOrderMapper, never()).payUnpaidOrder(any(), any());
    }

    @Test
    void missingExpiryOrItemsShouldBeInternalError() {
        when(ticketOrderMapper.selectByIdForUpdate(9001L))
                .thenReturn(order(1L, "UNPAID", null));
        when(ticketOrderMapper.selectByIdForUpdate(9002L))
                .thenReturn(order(1L, "UNPAID", LocalDateTime.now().plusMinutes(5)));
        when(ticketOrderItemMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertError(() -> service.payOrder(1L, 9001L), ErrorCode.INTERNAL_ERROR);
        assertError(() -> service.payOrder(1L, 9002L), ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void partialSeatSaleShouldFailTheTransaction() {
        when(ticketOrderMapper.selectByIdForUpdate(9001L))
                .thenReturn(order(1L, "UNPAID", LocalDateTime.now().plusMinutes(5)));
        when(ticketOrderItemMapper.selectList(any())).thenReturn(items());
        when(showtimeSeatMapper.markSeatsSoldByOrder(
                1001L, Arrays.asList(101L, 102L), 9001L)).thenReturn(1);

        assertError(() -> service.payOrder(1L, 9001L), ErrorCode.INTERNAL_ERROR);

        verify(ticketOrderMapper, never()).payUnpaidOrder(any(), any());
    }

    @Test
    void failedConditionalOrderUpdateShouldConflict() {
        when(ticketOrderMapper.selectByIdForUpdate(9001L))
                .thenReturn(order(1L, "UNPAID", LocalDateTime.now().plusMinutes(5)));
        when(ticketOrderItemMapper.selectList(any())).thenReturn(items());
        when(showtimeSeatMapper.markSeatsSoldByOrder(
                1001L, Arrays.asList(101L, 102L), 9001L)).thenReturn(2);
        when(ticketOrderMapper.payUnpaidOrder(anyLong(), any())).thenReturn(0);

        assertError(() -> service.payOrder(1L, 9001L), ErrorCode.ORDER_STATUS_CONFLICT);
    }

    @Test
    void invalidIdentifiersShouldBeRejectedBeforeQuery() {
        assertError(() -> service.payOrder(null, 9001L), ErrorCode.UNAUTHORIZED);
        assertError(() -> service.payOrder(1L, 0L), ErrorCode.INVALID_ID);
        verify(ticketOrderMapper, never()).selectByIdForUpdate(any());
    }

    private TicketOrder order(Long userId, String status, LocalDateTime expireTime) {
        TicketOrder order = new TicketOrder();
        order.setId(9001L);
        order.setUserId(userId);
        order.setShowtimeId(1001L);
        order.setStatus(status);
        order.setExpireTime(expireTime);
        return order;
    }

    private java.util.List<TicketOrderItem> items() {
        TicketOrderItem first = new TicketOrderItem();
        first.setOrderId(9001L);
        first.setSeatId(101L);
        TicketOrderItem second = new TicketOrderItem();
        second.setOrderId(9001L);
        second.setSeatId(102L);
        return Arrays.asList(first, second);
    }

    private void assertError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
