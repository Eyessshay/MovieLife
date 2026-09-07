package com.movielife.service.impl;

import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.TicketOrderMapper;
import com.movielife.mapper.TicketOrderItemMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TicketOrderIssuanceServiceTest {
    @Test
    void shortUpdateAndUniqueViolationMustPropagateForRollback() {
        TicketOrderMapper orders = mock(TicketOrderMapper.class);
        TicketOrderItemMapper items = mock(TicketOrderItemMapper.class);
        TicketOrderIssuanceService service = new TicketOrderIssuanceService(orders, items);
        TicketOrder order = new TicketOrder();
        order.setUserId(1L);
        order.setStatus("PAID");
        when(orders.selectByIdForUpdate(1L)).thenReturn(order);
        when(items.selectList(any())).thenAnswer(invocation -> Arrays.asList(item(1L), item(2L)));
        when(items.issueBatch(eq(1L), anyList(), eq(0), eq(1))).thenReturn(1);
        assertThatThrownBy(() -> service.issueTickets(1L, 1L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        when(items.issueBatch(eq(1L), anyList(), eq(0), eq(1)))
                .thenThrow(new DuplicateKeyException("uk_ticket_code"));
        assertThatThrownBy(() -> service.issueTickets(1L, 1L)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void invalidRequestAndMissingOrderMustFailBeforeItemAccess() {
        TicketOrderMapper orders = mock(TicketOrderMapper.class);
        TicketOrderItemMapper items = mock(TicketOrderItemMapper.class);
        TicketOrderIssuanceService service = new TicketOrderIssuanceService(orders, items);
        assertThatThrownBy(() -> service.issueTickets(null, 1L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.issueTickets(1L, 0L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.issueTickets(1L, 1L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
        verifyNoInteractions(items);
    }

    private TicketOrderItem item(Long id) {
        TicketOrderItem item = new TicketOrderItem();
        item.setId(id);
        item.setOrderId(1L);
        item.setSeatId(id);
        item.setTicketStatus(0);
        return item;
    }
}
