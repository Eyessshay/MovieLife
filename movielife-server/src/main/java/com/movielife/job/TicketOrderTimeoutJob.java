package com.movielife.job;

import com.movielife.mapper.TicketOrderMapper;
import com.movielife.service.impl.TicketOrderCancellationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class TicketOrderTimeoutJob {

    static final int BATCH_SIZE = 100;

    private final TicketOrderMapper ticketOrderMapper;
    private final TicketOrderCancellationService cancellationService;

    @Scheduled(
            fixedDelayString = "${movielife.order.timeout.fixed-delay-ms:60000}",
            initialDelayString = "${movielife.order.timeout.initial-delay-ms:60000}")
    public void cancelExpiredOrders() {
        LocalDateTime scanTime = LocalDateTime.now();
        List<Long> orderIds = ticketOrderMapper.selectExpiredUnpaidOrderIds(
                scanTime, BATCH_SIZE);
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }
        for (Long orderId : orderIds) {
            try {
                cancellationService.cancelExpiredOrder(orderId);
            } catch (RuntimeException exception) {
                log.error("Failed to cancel expired unpaid order, orderId={}",
                        orderId, exception);
            }
        }
    }
}
