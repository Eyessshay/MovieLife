package com.movielife.service.impl;

import com.movielife.dto.CreateTicketOrderDTO;
import com.movielife.entity.TicketOrder;
import com.movielife.service.TicketOrderCreationContext;
import com.movielife.service.TicketOrderCreationValidator;
import com.movielife.service.SeatLockService;
import com.movielife.vo.CreateTicketOrderVO;
import com.movielife.vo.PageVO;
import com.movielife.vo.PayTicketOrderVO;
import com.movielife.vo.TicketOrderDetailVO;
import com.movielife.vo.TicketOrderListItemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketOrderServiceImplTest {

    @Mock
    private TicketOrderCreationValidator creationValidator;
    @Mock
    private TicketOrderTransactionService transactionService;
    @Mock
    private SeatLockService seatLockService;
    @Mock
    private TicketOrderQueryService queryService;
    @Mock
    private TicketOrderCancellationService cancellationService;
    @Mock
    private TicketOrderPaymentService paymentService;

    private TicketOrderServiceImpl service;
    private CreateTicketOrderDTO request;

    @BeforeEach
    void setUp() {
        service = new TicketOrderServiceImpl(
                creationValidator, transactionService, seatLockService,
                queryService, cancellationService, paymentService);
        request = new CreateTicketOrderDTO();
        request.setLockToken("lock-token");
    }

    @Test
    void consumedTokenShouldReturnExistingOrderWithoutEnteringTransaction() {
        TicketOrderCreationContext context = consumedContext(9001L);
        when(creationValidator.validate(1L, request)).thenReturn(context);

        CreateTicketOrderVO result = service.createOrder(1L, request);

        assertThat(result.getOrderId()).isEqualTo(9001L);
        assertThat(result.getStatus()).isEqualTo("PAID");
        assertThat(result.getTotalAmount()).isEqualByComparingTo("95.00");
        verify(transactionService, never()).createNewOrder(context);
        verify(seatLockService).cleanupLock(1001L, Arrays.asList(101L, 102L), "lock-token");
    }

    @Test
    void unconsumedTokenShouldEnterTransactionOnce() {
        TicketOrderCreationContext context = unconsumedContext();
        CreateTicketOrderVO created = new CreateTicketOrderVO();
        created.setOrderId(9002L);
        when(creationValidator.validate(1L, request)).thenReturn(context);
        when(transactionService.createNewOrder(context)).thenReturn(created);

        assertThat(service.createOrder(1L, request)).isSameAs(created);
        verify(transactionService).createNewOrder(context);
        verify(seatLockService).cleanupLock(1001L, Arrays.asList(101L, 102L), "lock-token");
    }

    @Test
    void uniqueTokenRaceShouldRevalidateAfterFailedTransactionAndReturnWinner() {
        TicketOrderCreationContext initial = new TicketOrderCreationContext();
        TicketOrderCreationContext winner = consumedContext(9003L);
        when(creationValidator.validate(1L, request)).thenReturn(initial, winner);
        when(transactionService.createNewOrder(initial))
                .thenThrow(new DuplicateKeyException("uk_order_lock_token"));

        CreateTicketOrderVO result = service.createOrder(1L, request);

        assertThat(result.getOrderId()).isEqualTo(9003L);
        verify(creationValidator, org.mockito.Mockito.times(2)).validate(1L, request);
        verify(seatLockService).cleanupLock(1001L, Arrays.asList(101L, 102L), "lock-token");
    }

    @Test
    void cleanupFailureAfterCommitShouldBeRetriedAndMustNotFailCreation() {
        TicketOrderCreationContext context = unconsumedContext();
        CreateTicketOrderVO created = new CreateTicketOrderVO();
        created.setOrderId(9004L);
        when(creationValidator.validate(1L, request)).thenReturn(context);
        when(transactionService.createNewOrder(context)).thenReturn(created);
        doThrow(new IllegalStateException("redis unavailable"))
                .doThrow(new IllegalStateException("redis unavailable"))
                .when(seatLockService).cleanupLock(
                        1001L, Arrays.asList(101L, 102L), "lock-token");

        assertThat(service.createOrder(1L, request)).isSameAs(created);
        verify(seatLockService, times(2)).cleanupLock(
                1001L, Arrays.asList(101L, 102L), "lock-token");
    }

    @Test
    void cleanupShouldStopAfterRetrySucceeds() {
        TicketOrderCreationContext context = unconsumedContext();
        CreateTicketOrderVO created = new CreateTicketOrderVO();
        created.setOrderId(9005L);
        when(creationValidator.validate(1L, request)).thenReturn(context);
        when(transactionService.createNewOrder(context)).thenReturn(created);
        doThrow(new IllegalStateException("temporary redis failure"))
                .doNothing()
                .when(seatLockService).cleanupLock(
                        1001L, Arrays.asList(101L, 102L), "lock-token");

        assertThat(service.createOrder(1L, request)).isSameAs(created);
        verify(seatLockService, times(2)).cleanupLock(
                1001L, Arrays.asList(101L, 102L), "lock-token");
    }

    @Test
    void sameTokenRetryAfterCleanupFailureShouldReturnOriginalOrderAndRetryCleanup() {
        TicketOrderCreationContext initial = unconsumedContext();
        TicketOrderCreationContext existing = consumedContext(9006L);
        CreateTicketOrderVO created = new CreateTicketOrderVO();
        created.setOrderId(9006L);
        when(creationValidator.validate(1L, request)).thenReturn(initial, existing);
        when(transactionService.createNewOrder(initial)).thenReturn(created);
        doThrow(new IllegalStateException("redis unavailable"))
                .doThrow(new IllegalStateException("redis unavailable"))
                .doNothing()
                .when(seatLockService).cleanupLock(
                        1001L, Arrays.asList(101L, 102L), "lock-token");

        assertThat(service.createOrder(1L, request).getOrderId()).isEqualTo(9006L);
        assertThat(service.createOrder(1L, request).getOrderId()).isEqualTo(9006L);

        verify(transactionService, times(1)).createNewOrder(initial);
        verify(seatLockService, times(3)).cleanupLock(
                1001L, Arrays.asList(101L, 102L), "lock-token");
    }

    @Test
    void unrelatedDuplicateShouldNotBeHiddenWhenTokenStillUnconsumed() {
        TicketOrderCreationContext initial = new TicketOrderCreationContext();
        TicketOrderCreationContext retry = new TicketOrderCreationContext();
        DuplicateKeyException duplicate = new DuplicateKeyException("another constraint");
        when(creationValidator.validate(1L, request)).thenReturn(initial, retry);
        when(transactionService.createNewOrder(initial)).thenThrow(duplicate);

        assertThatThrownBy(() -> service.createOrder(1L, request)).isSameAs(duplicate);
    }

    @Test
    void orderListQueryShouldDelegateToReadOnlyQueryService() {
        PageVO<TicketOrderListItemVO> page =
                new PageVO<>(Collections.emptyList(), 0L, 1, 20);
        when(queryService.queryMyOrders(1L, 1, 20)).thenReturn(page);

        assertThat(service.queryMyOrders(1L, 1, 20)).isSameAs(page);
        verify(queryService).queryMyOrders(1L, 1, 20);
    }

    @Test
    void orderDetailQueryShouldDelegateToReadOnlyQueryService() {
        TicketOrderDetailVO detail = new TicketOrderDetailVO();
        detail.setOrderId(9001L);
        when(queryService.queryOrderDetail(1L, 9001L)).thenReturn(detail);

        assertThat(service.queryOrderDetail(1L, 9001L)).isSameAs(detail);
        verify(queryService).queryOrderDetail(1L, 9001L);
    }

    @Test
    void cancellationShouldDelegateToTransactionalCancellationService() {
        service.cancelOrder(1L, 9001L);

        verify(cancellationService).cancelOrder(1L, 9001L);
    }

    @Test
    void paymentShouldDelegateToTransactionalPaymentService() {
        PayTicketOrderVO paid = new PayTicketOrderVO();
        paid.setOrderId(9001L);
        when(paymentService.payOrder(1L, 9001L)).thenReturn(paid);

        assertThat(service.payOrder(1L, 9001L)).isSameAs(paid);
        verify(paymentService).payOrder(1L, 9001L);
    }

    private TicketOrderCreationContext consumedContext(Long orderId) {
        TicketOrder order = new TicketOrder();
        order.setId(orderId);
        order.setStatus("PAID");
        order.setTotalAmount(new BigDecimal("95.00"));
        order.setExpireTime(LocalDateTime.now().plusMinutes(10));
        TicketOrderCreationContext context = new TicketOrderCreationContext();
        context.setExistingOrder(order);
        context.setShowtimeId(1001L);
        context.setSeatIds(Arrays.asList(101L, 102L));
        context.setLockToken("lock-token");
        return context;
    }

    private TicketOrderCreationContext unconsumedContext() {
        TicketOrderCreationContext context = new TicketOrderCreationContext();
        context.setShowtimeId(1001L);
        context.setSeatIds(Arrays.asList(101L, 102L));
        context.setLockToken("lock-token");
        return context;
    }
}
