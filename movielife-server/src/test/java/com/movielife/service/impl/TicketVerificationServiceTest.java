package com.movielife.service.impl;

import com.movielife.entity.Seat;
import com.movielife.entity.Showtime;
import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.TicketOrderItemMapper;
import com.movielife.mapper.TicketOrderMapper;
import com.movielife.mapper.VenueStaffMapper;
import com.movielife.service.TicketVerificationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketVerificationServiceTest {

    private static final String TICKET_CODE = "ticket-code-1";

    @Mock private TicketOrderMapper ticketOrderMapper;
    @Mock private TicketOrderItemMapper ticketOrderItemMapper;
    @Mock private ShowtimeMapper showtimeMapper;
    @Mock private SeatMapper seatMapper;
    @Mock private VenueStaffMapper venueStaffMapper;

    private TicketVerificationService service;

    @BeforeEach
    void setUp() {
        service = new TicketVerificationService(ticketOrderMapper, ticketOrderItemMapper,
                showtimeMapper, seatMapper, venueStaffMapper);
    }

    @Test
    void validTicketShouldBeVerifiedWithOrderThenItemLocking() {
        stubValidTicket();
        when(ticketOrderItemMapper.markUsed(anyLong(), anyLong(), anyInt(), anyInt(), any(), anyLong()))
                .thenReturn(1);

        TicketVerificationResult result = service.verifyTicket(7L, "  " + TICKET_CODE + "  ");

        assertThat(result.getTicketCode()).isEqualTo(TICKET_CODE);
        assertThat(result.getTicketStatus()).isEqualTo(2);
        assertThat(result.getUsedTime()).isNotNull();
        assertThat(result.getOrderId()).isEqualTo(9001L);
        assertThat(result.getShowtimeId()).isEqualTo(1001L);
        assertThat(result.getVenueId()).isEqualTo(501L);
        assertThat(result.getHallId()).isEqualTo(601L);
        assertThat(result.getSeatId()).isEqualTo(101L);
        assertThat(result.getSeatNo()).isEqualTo("A01");
        InOrder lockOrder = inOrder(ticketOrderMapper, ticketOrderItemMapper);
        lockOrder.verify(ticketOrderMapper).selectByIdForUpdate(9001L);
        lockOrder.verify(ticketOrderItemMapper).selectByIdAndOrderIdForUpdate(11L, 9001L);
    }

    @Test
    void invalidRequestAndUnknownCodeShouldUseStableErrors() {
        assertError(() -> service.verifyTicket(null, TICKET_CODE), ErrorCode.UNAUTHORIZED);
        assertError(() -> service.verifyTicket(7L, " "), ErrorCode.INVALID_PARAM);
        assertError(() -> service.verifyTicket(7L, repeat('x', 65)), ErrorCode.INVALID_PARAM);
        when(ticketOrderItemMapper.selectByTicketCode(TICKET_CODE)).thenReturn(null);
        assertError(() -> service.verifyTicket(7L, TICKET_CODE), ErrorCode.TICKET_NOT_FOUND);
        verify(ticketOrderMapper, never()).selectByIdForUpdate(anyLong());
    }

    @Test
    void inactiveOrWrongVenueStaffShouldBeForbiddenBeforeStateChange() {
        stubBeforeAuthorization(validItem());
        when(venueStaffMapper.selectActiveIdForShare(7L, 501L)).thenReturn(null);

        assertError(() -> service.verifyTicket(7L, TICKET_CODE), ErrorCode.TICKET_VERIFY_FORBIDDEN);

        verify(ticketOrderItemMapper, never()).markUsed(anyLong(), anyLong(), anyInt(), anyInt(), any(), anyLong());
        verify(seatMapper, never()).selectById(anyLong());
    }

    @Test
    void ticketAndOrderStatesShouldMapToBusinessErrors() {
        assertStateError(pendingItem(), paidOrder(), ErrorCode.TICKET_NOT_ISSUED);
        assertStateError(usedItem(), paidOrder(), ErrorCode.TICKET_ALREADY_USED);
        assertStateError(refundedItem(), order("REFUNDED"), ErrorCode.TICKET_REFUNDED);
        assertStateError(validItem(), order("UNPAID"), ErrorCode.ORDER_STATUS_CONFLICT);
    }

    @Test
    void failedConditionalUpdateShouldMapFinalUsedStateWithoutOverwritingEvidence() {
        TicketOrderItem valid = validItem();
        TicketOrderItem used = usedItem();
        LocalDateTime firstUsedTime = used.getUsedTime();
        when(ticketOrderItemMapper.selectByTicketCode(TICKET_CODE)).thenReturn(valid);
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(paidOrder());
        when(ticketOrderItemMapper.selectByIdAndOrderIdForUpdate(11L, 9001L))
                .thenReturn(valid, used);
        when(showtimeMapper.selectById(1001L)).thenReturn(showtime());
        when(venueStaffMapper.selectActiveIdForShare(7L, 501L)).thenReturn(71L);
        when(seatMapper.selectById(101L)).thenReturn(seat());
        when(ticketOrderItemMapper.markUsed(anyLong(), anyLong(), anyInt(), anyInt(), any(), anyLong()))
                .thenReturn(0);

        assertError(() -> service.verifyTicket(7L, TICKET_CODE), ErrorCode.TICKET_ALREADY_USED);

        assertThat(used.getUsedTime()).isEqualTo(firstUsedTime);
        assertThat(used.getUsedByUserId()).isEqualTo(8L);
        verify(ticketOrderItemMapper).markUsed(anyLong(), anyLong(), anyInt(), anyInt(), any(), anyLong());
    }

    private void assertStateError(TicketOrderItem item, TicketOrder order, ErrorCode errorCode) {
        org.mockito.Mockito.reset(ticketOrderMapper, ticketOrderItemMapper,
                showtimeMapper, seatMapper, venueStaffMapper);
        when(ticketOrderItemMapper.selectByTicketCode(TICKET_CODE)).thenReturn(item);
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(order);
        when(ticketOrderItemMapper.selectByIdAndOrderIdForUpdate(11L, 9001L)).thenReturn(item);
        when(showtimeMapper.selectById(1001L)).thenReturn(showtime());
        when(venueStaffMapper.selectActiveIdForShare(7L, 501L)).thenReturn(71L);

        assertError(() -> service.verifyTicket(7L, TICKET_CODE), errorCode);
        verify(ticketOrderItemMapper, never()).markUsed(anyLong(), anyLong(), anyInt(), anyInt(), any(), anyLong());
    }

    private void stubValidTicket() {
        stubUntilAuthorization(validItem());
        when(seatMapper.selectById(101L)).thenReturn(seat());
    }

    private void stubUntilAuthorization(TicketOrderItem item) {
        stubBeforeAuthorization(item);
        when(venueStaffMapper.selectActiveIdForShare(7L, 501L)).thenReturn(71L);
    }

    private void stubBeforeAuthorization(TicketOrderItem item) {
        when(ticketOrderItemMapper.selectByTicketCode(TICKET_CODE)).thenReturn(item);
        when(ticketOrderMapper.selectByIdForUpdate(9001L)).thenReturn(paidOrder());
        when(ticketOrderItemMapper.selectByIdAndOrderIdForUpdate(11L, 9001L)).thenReturn(item);
        when(showtimeMapper.selectById(1001L)).thenReturn(showtime());
    }

    private TicketOrderItem validItem() {
        return item(1, null, null);
    }

    private TicketOrderItem pendingItem() {
        return item(0, null, null);
    }

    private TicketOrderItem usedItem() {
        return item(2, LocalDateTime.now().minusMinutes(1).withNano(0), 8L);
    }

    private TicketOrderItem refundedItem() {
        return item(3, null, null);
    }

    private TicketOrderItem item(int status, LocalDateTime usedTime, Long usedByUserId) {
        TicketOrderItem item = new TicketOrderItem();
        item.setId(11L);
        item.setOrderId(9001L);
        item.setSeatId(101L);
        item.setTicketCode(TICKET_CODE);
        item.setTicketStatus(status);
        item.setUsedTime(usedTime);
        item.setUsedByUserId(usedByUserId);
        return item;
    }

    private TicketOrder paidOrder() {
        return order("PAID");
    }

    private TicketOrder order(String status) {
        TicketOrder order = new TicketOrder();
        order.setId(9001L);
        order.setShowtimeId(1001L);
        order.setStatus(status);
        return order;
    }

    private Showtime showtime() {
        Showtime showtime = new Showtime();
        showtime.setId(1001L);
        showtime.setVenueId(501L);
        showtime.setHallId(601L);
        return showtime;
    }

    private Seat seat() {
        Seat seat = new Seat();
        seat.setId(101L);
        seat.setHallId(601L);
        seat.setSeatNo("A01");
        return seat;
    }

    private String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private void assertError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
