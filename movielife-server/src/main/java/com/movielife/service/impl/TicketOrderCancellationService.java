package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.enums.TicketOrderStatus;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TicketOrderCancellationService {

    private final TicketOrderMapper ticketOrderMapper;
    private final TicketOrderItemMapper ticketOrderItemMapper;
    private final ShowtimeSeatMapper showtimeSeatMapper;
    private final ShowtimeMapper showtimeMapper;

    @Transactional
    public void cancelOrder(Long userId, Long orderId) {
        validateRequest(userId, orderId);

        TicketOrder order = ticketOrderMapper.selectByIdForUpdate(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (TicketOrderStatus.CANCELED.getDbValue().equals(order.getStatus())) {
            return;
        }
        if (!TicketOrderStatus.UNPAID.getDbValue().equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT);
        }

        releaseOrderResources(order);
        if (ticketOrderMapper.cancelUnpaidOrder(orderId, userId) != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT);
        }
    }

    @Transactional
    public void cancelExpiredOrder(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return;
        }
        TicketOrder order = ticketOrderMapper.selectByIdForUpdate(orderId);
        LocalDateTime now = LocalDateTime.now();
        if (order == null
                || !TicketOrderStatus.UNPAID.getDbValue().equals(order.getStatus())
                || order.getExpireTime() == null
                || order.getExpireTime().isAfter(now)) {
            return;
        }

        releaseOrderResources(order);
        if (ticketOrderMapper.cancelExpiredUnpaidOrder(orderId, now) != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT);
        }
    }

    private void releaseOrderResources(TicketOrder order) {
        List<TicketOrderItem> items = ticketOrderItemMapper.selectList(
                new LambdaQueryWrapper<TicketOrderItem>()
                        .eq(TicketOrderItem::getOrderId, order.getId()));
        List<Long> seatIds = extractSeatIds(items);

        int released = showtimeSeatMapper.releaseSeatsByOrder(
                order.getShowtimeId(), seatIds, order.getId());
        if (released != seatIds.size()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (showtimeMapper.increaseAvailableSeats(order.getShowtimeId(), seatIds.size()) != 1) {
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

    private List<Long> extractSeatIds(List<TicketOrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        List<Long> seatIds = new ArrayList<>(items.size());
        Set<Long> uniqueSeatIds = new HashSet<>();
        for (TicketOrderItem item : items) {
            if (item == null || item.getSeatId() == null
                    || !uniqueSeatIds.add(item.getSeatId())) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            seatIds.add(item.getSeatId());
        }
        return seatIds;
    }
}
