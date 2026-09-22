package com.movielife.service.impl;

import com.movielife.dto.SeatLockMetadata;
import com.movielife.entity.ShowtimeSeat;
import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.TicketOrderItemMapper;
import com.movielife.mapper.TicketOrderMapper;
import com.movielife.service.ShowtimeService;
import com.movielife.service.TicketOrderCreationContext;
import com.movielife.vo.CreateTicketOrderVO;
import com.movielife.vo.ShowtimeDetailVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketOrderTransactionServiceTest {

    @Mock private ShowtimeService showtimeService;
    @Mock private ShowtimeMapper showtimeMapper;
    @Mock private ShowtimeSeatMapper showtimeSeatMapper;
    @Mock private TicketOrderMapper ticketOrderMapper;
    @Mock private TicketOrderItemMapper ticketOrderItemMapper;

    private TicketOrderTransactionService service;
    private TicketOrderCreationContext context;

    @BeforeEach
    void setUp() {
        service = new TicketOrderTransactionService(showtimeService, showtimeMapper,
                showtimeSeatMapper, ticketOrderMapper, ticketOrderItemMapper);
        context = context();
    }

    private void stubShowtimeAndSeats() {
        ShowtimeDetailVO showtime = new ShowtimeDetailVO();
        showtime.setStartTime(LocalDateTime.now().plusHours(2));
        when(showtimeService.queryShowtimeDetail(1001L)).thenReturn(showtime);
        when(showtimeSeatMapper.selectList(any())).thenReturn(Arrays.asList(
                seatState(101L, "40.00"), seatState(102L, "55.00")));
    }

    @Test
    void shouldCreateOrderAndItemsFromSeatPriceSnapshots() {
        stubShowtimeAndSeats();
        doAnswer(invocation -> {
            TicketOrder order = invocation.getArgument(0);
            order.setId(9001L);
            return 1;
        }).when(ticketOrderMapper).insert(any(TicketOrder.class));
        when(ticketOrderItemMapper.insertBatch(anyList())).thenReturn(2);
        when(showtimeSeatMapper.holdSeatsForOrder(1001L, context.getSeatIds(), 9001L)).thenReturn(2);
        when(showtimeMapper.decreaseAvailableSeats(1001L, 2)).thenReturn(1);

        CreateTicketOrderVO result = service.createNewOrder(context);

        assertThat(result.getOrderId()).isEqualTo(9001L);
        assertThat(result.getStatus()).isEqualTo("UNPAID");
        assertThat(result.getTotalAmount()).isEqualByComparingTo("95.00");
        assertThat(result.getExpireTime()).isAfter(LocalDateTime.now().plusMinutes(14));

        ArgumentCaptor<TicketOrder> orderCaptor = ArgumentCaptor.forClass(TicketOrder.class);
        verify(ticketOrderMapper).insert(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getTotalAmount()).isEqualByComparingTo("95.00");
        assertThat(orderCaptor.getValue().getLockToken()).isEqualTo("lock-token");
        verify(showtimeMapper).selectByIdForUpdate(1001L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TicketOrderItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(ticketOrderItemMapper).insertBatch(itemsCaptor.capture());
        assertThat(itemsCaptor.getValue()).extracting(TicketOrderItem::getSeatId)
                .containsExactly(101L, 102L);
        assertThat(itemsCaptor.getValue()).extracting(TicketOrderItem::getPrice)
                .containsExactly(new BigDecimal("40.00"), new BigDecimal("55.00"));
        assertThat(itemsCaptor.getValue()).allSatisfy(item -> {
            assertThat(item.getTicketCode()).isNull();
            assertThat(item.getTicketStatus()).isZero();
        });
    }

    @Test
    void activityShouldValidateCompleteSeatMapBeforeCreatingOrder() {
        stubShowtimeAndSeats("ACTIVITY");
        stubSuccessfulOrderAndItems();
        when(showtimeSeatMapper.holdSeatsForOrder(1001L, context.getSeatIds(), 9001L)).thenReturn(2);
        when(showtimeMapper.decreaseAvailableSeats(1001L, 2)).thenReturn(1);

        CreateTicketOrderVO result = service.createNewOrder(context);

        assertThat(result.getOrderId()).isEqualTo(9001L);
        verify(showtimeService).querySeatMap(1001L);
    }

    @Test
    void incompleteActivitySeatMapShouldFailBeforeAnyOrderWrite() {
        ShowtimeDetailVO showtime = new ShowtimeDetailVO();
        showtime.setBizType("ACTIVITY");
        showtime.setStartTime(LocalDateTime.now().plusHours(2));
        when(showtimeService.queryShowtimeDetail(1001L)).thenReturn(showtime);
        when(showtimeService.querySeatMap(1001L))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR));

        assertError(ErrorCode.INTERNAL_ERROR, () -> service.createNewOrder(context));

        verify(showtimeSeatMapper, never()).selectList(any());
        verify(ticketOrderMapper, never()).insert(any());
        verify(ticketOrderItemMapper, never()).insertBatch(anyList());
        verify(showtimeSeatMapper, never()).holdSeatsForOrder(any(), anyList(), any());
        verify(showtimeMapper, never()).decreaseAvailableSeats(any(), any());
    }

    @Test
    void partialSeatHoldShouldFailBeforeAvailableCountUpdate() {
        stubShowtimeAndSeats();
        stubSuccessfulOrderAndItems();
        when(showtimeSeatMapper.holdSeatsForOrder(1001L, context.getSeatIds(), 9001L)).thenReturn(1);

        assertError(ErrorCode.SEAT_NOT_AVAILABLE, () -> service.createNewOrder(context));
        verify(showtimeMapper, never()).decreaseAvailableSeats(any(), any());
    }

    @Test
    void itemBatchCountMismatchShouldStopBeforeSeatUpdate() {
        stubShowtimeAndSeats();
        doAnswer(invocation -> { ((TicketOrder) invocation.getArgument(0)).setId(9001L); return 1; })
                .when(ticketOrderMapper).insert(any(TicketOrder.class));
        when(ticketOrderItemMapper.insertBatch(anyList())).thenReturn(1);

        assertError(ErrorCode.INTERNAL_ERROR, () -> service.createNewOrder(context));
        verify(showtimeSeatMapper, never()).holdSeatsForOrder(any(), anyList(), any());
    }

    @Test
    void insufficientAvailableCounterShouldFailAfterSeatHold() {
        stubShowtimeAndSeats();
        stubSuccessfulOrderAndItems();
        when(showtimeSeatMapper.holdSeatsForOrder(1001L, context.getSeatIds(), 9001L)).thenReturn(2);
        when(showtimeMapper.decreaseAvailableSeats(1001L, 2)).thenReturn(0);

        assertError(ErrorCode.INTERNAL_ERROR, () -> service.createNewOrder(context));
    }

    @Test
    void expireTimeShouldBePaymentTtlWhenShowtimeIsLater() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 4, 10, 0);
        assertThat(service.calculateOrderExpireTime(now, now.plusHours(1)))
                .isEqualTo(now.plusMinutes(15));
    }

    @Test
    void expireTimeShouldBeCappedAtShowtimeStart() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 4, 10, 0);
        assertThat(service.calculateOrderExpireTime(now, now.plusMinutes(10)))
                .isEqualTo(now.plusMinutes(10));
    }

    @Test
    void elapsedShowtimeShouldBeRejected() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 4, 10, 0);
        assertError(ErrorCode.SHOWTIME_NOT_FOUND,
                () -> service.calculateOrderExpireTime(now, now));
    }

    private void stubSuccessfulOrderAndItems() {
        doAnswer(invocation -> { ((TicketOrder) invocation.getArgument(0)).setId(9001L); return 1; })
                .when(ticketOrderMapper).insert(any(TicketOrder.class));
        when(ticketOrderItemMapper.insertBatch(anyList())).thenReturn(2);
    }

    private void stubShowtimeAndSeats(String bizType) {
        ShowtimeDetailVO showtime = new ShowtimeDetailVO();
        showtime.setBizType(bizType);
        showtime.setStartTime(LocalDateTime.now().plusHours(2));
        when(showtimeService.queryShowtimeDetail(1001L)).thenReturn(showtime);
        when(showtimeSeatMapper.selectList(any())).thenReturn(Arrays.asList(
                seatState(101L, "40.00"), seatState(102L, "55.00")));
    }

    private TicketOrderCreationContext context() {
        SeatLockMetadata metadata = new SeatLockMetadata();
        TicketOrderCreationContext result = new TicketOrderCreationContext();
        result.setUserId(1L);
        result.setShowtimeId(1001L);
        result.setSeatIds(Arrays.asList(101L, 102L));
        result.setLockToken("lock-token");
        result.setLockMetadata(metadata);
        return result;
    }

    private ShowtimeSeat seatState(Long seatId, String price) {
        ShowtimeSeat state = new ShowtimeSeat();
        state.setShowtimeId(1001L);
        state.setSeatId(seatId);
        state.setStatus(0);
        state.setPrice(new BigDecimal(price));
        state.setVersion(0);
        return state;
    }

    private void assertError(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(expected);
    }
}
