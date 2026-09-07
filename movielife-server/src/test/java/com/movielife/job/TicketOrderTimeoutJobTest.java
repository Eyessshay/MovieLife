package com.movielife.job;

import com.movielife.mapper.TicketOrderMapper;
import com.movielife.service.impl.TicketOrderCancellationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketOrderTimeoutJobTest {

    @Mock private TicketOrderMapper ticketOrderMapper;
    @Mock private TicketOrderCancellationService cancellationService;

    private TicketOrderTimeoutJob job;

    @BeforeEach
    void setUp() {
        job = new TicketOrderTimeoutJob(ticketOrderMapper, cancellationService);
    }

    @Test
    void shouldScanOneBoundedBatchAndProcessEveryOrder() {
        when(ticketOrderMapper.selectExpiredUnpaidOrderIds(
                any(LocalDateTime.class), any(Integer.class)))
                .thenReturn(Arrays.asList(11L, 12L, 13L));

        job.cancelExpiredOrders();

        ArgumentCaptor<LocalDateTime> timeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(ticketOrderMapper).selectExpiredUnpaidOrderIds(
                timeCaptor.capture(), org.mockito.ArgumentMatchers.eq(100));
        assertThat(timeCaptor.getValue()).isBeforeOrEqualTo(LocalDateTime.now());
        verify(cancellationService).cancelExpiredOrder(11L);
        verify(cancellationService).cancelExpiredOrder(12L);
        verify(cancellationService).cancelExpiredOrder(13L);
    }

    @Test
    void oneOrderFailureShouldNotInterruptTheBatch() {
        when(ticketOrderMapper.selectExpiredUnpaidOrderIds(any(), any()))
                .thenReturn(Arrays.asList(11L, 12L, 13L));
        doAnswer(invocation -> {
            if (Long.valueOf(12L).equals(invocation.getArgument(0))) {
                throw new IllegalStateException("broken order");
            }
            return null;
        }).when(cancellationService).cancelExpiredOrder(anyLong());

        job.cancelExpiredOrders();

        verify(cancellationService).cancelExpiredOrder(11L);
        verify(cancellationService).cancelExpiredOrder(12L);
        verify(cancellationService).cancelExpiredOrder(13L);
    }

    @Test
    void emptyBatchShouldReturnWithoutStartingCancellation() {
        when(ticketOrderMapper.selectExpiredUnpaidOrderIds(any(), any()))
                .thenReturn(Collections.emptyList());

        job.cancelExpiredOrders();

        verify(cancellationService, never()).cancelExpiredOrder(any());
    }
}
