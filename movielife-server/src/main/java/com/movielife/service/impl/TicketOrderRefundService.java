package com.movielife.service.impl;

import com.movielife.entity.Showtime;
import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.enums.TicketOrderStatus;
import com.movielife.enums.TicketStatus;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.TicketOrderItemMapper;
import com.movielife.mapper.TicketOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TicketOrderRefundService {

    private final TicketOrderMapper ticketOrderMapper;
    private final TicketOrderItemMapper ticketOrderItemMapper;
    private final ShowtimeSeatMapper showtimeSeatMapper;
    private final ShowtimeMapper showtimeMapper;

    @Transactional
    public void refundOrder(Long userId, Long orderId) {
        validateRequest(userId, orderId);

        TicketOrder order = ticketOrderMapper.selectByIdForUpdate(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (TicketOrderStatus.REFUNDED.getDbValue().equals(order.getStatus())) {
            return;
        }
        if (!TicketOrderStatus.PAID.getDbValue().equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT);
        }

        List<TicketOrderItem> items = ticketOrderItemMapper.selectByOrderIdForUpdate(orderId);
        RefundItems refundItems = validateItems(items);

        Showtime showtime = showtimeMapper.selectById(order.getShowtimeId());
        LocalDateTime now = LocalDateTime.now();
        if (showtime == null || showtime.getStartTime() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (!showtime.getStartTime().isAfter(now)) {
            throw new BusinessException(ErrorCode.REFUND_NOT_ALLOWED);
        }

        if (showtimeSeatMapper.releaseSoldSeatsByOrder(
                order.getShowtimeId(), refundItems.seatIds, orderId) != refundItems.seatIds.size()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (showtimeMapper.increaseAvailableSeats(order.getShowtimeId(), refundItems.seatIds.size()) != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (ticketOrderMapper.refundPaidOrder(orderId) != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT);
        }
        if (ticketOrderItemMapper.markRefundedByOrder(orderId, refundItems.itemIds,
                Arrays.asList(TicketStatus.PENDING.getCode(), TicketStatus.VALID.getCode()),
                TicketStatus.REFUNDED.getCode()) != refundItems.itemIds.size()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private void validateRequest(Long userId, Long orderId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (orderId == null || orderId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }
    }

    private RefundItems validateItems(List<TicketOrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        List<Long> itemIds = new ArrayList<>(items.size());
        List<Long> seatIds = new ArrayList<>(items.size());
        Set<Long> uniqueItemIds = new HashSet<>();
        Set<Long> uniqueSeatIds = new HashSet<>();
        for (TicketOrderItem item : items) {
            if (item == null || item.getId() == null || item.getSeatId() == null
                    || !uniqueItemIds.add(item.getId()) || !uniqueSeatIds.add(item.getSeatId())) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            Integer ticketStatus = item.getTicketStatus();
            if (ticketStatus == null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            if (TicketStatus.USED.getCode() == ticketStatus) {
                throw new BusinessException(ErrorCode.REFUND_NOT_ALLOWED);
            }
            if (TicketStatus.PENDING.getCode() != ticketStatus
                    && TicketStatus.VALID.getCode() != ticketStatus) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            itemIds.add(item.getId());
            seatIds.add(item.getSeatId());
        }
        return new RefundItems(itemIds, seatIds);
    }

    private static class RefundItems {
        private final List<Long> itemIds;
        private final List<Long> seatIds;

        private RefundItems(List<Long> itemIds, List<Long> seatIds) {
            this.itemIds = itemIds;
            this.seatIds = seatIds;
        }
    }
}
