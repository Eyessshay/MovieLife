package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.enums.TicketOrderStatus;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.TicketOrderItemMapper;
import com.movielife.mapper.TicketOrderMapper;
import com.movielife.vo.PayTicketOrderVO;
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
public class TicketOrderPaymentService {

    private final TicketOrderMapper ticketOrderMapper;
    private final TicketOrderItemMapper ticketOrderItemMapper;
    private final ShowtimeSeatMapper showtimeSeatMapper;

    @Transactional
    public PayTicketOrderVO payOrder(Long userId, Long orderId) {
        validateRequest(userId, orderId);

        TicketOrder order = ticketOrderMapper.selectByIdForUpdate(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (TicketOrderStatus.PAID.getDbValue().equals(order.getStatus())) {
            return toResult(order);
        }
        if (!TicketOrderStatus.UNPAID.getDbValue().equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT);
        }

        LocalDateTime payTime = LocalDateTime.now().withNano(0);
        if (order.getExpireTime() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (!order.getExpireTime().isAfter(payTime)) {
            throw new BusinessException(ErrorCode.ORDER_EXPIRED);
        }

        List<TicketOrderItem> items = ticketOrderItemMapper.selectList(
                new LambdaQueryWrapper<TicketOrderItem>()
                        .eq(TicketOrderItem::getOrderId, orderId));
        List<Long> seatIds = extractSeatIds(items);
        int sold = showtimeSeatMapper.markSeatsSoldByOrder(
                order.getShowtimeId(), seatIds, orderId);
        if (sold != seatIds.size()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (ticketOrderMapper.payUnpaidOrder(orderId, payTime) != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT);
        }

        order.setStatus(TicketOrderStatus.PAID.getDbValue());
        order.setPayTime(payTime);
        return toResult(order);
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

    private PayTicketOrderVO toResult(TicketOrder order) {
        if (order.getId() == null || order.getPayTime() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        PayTicketOrderVO result = new PayTicketOrderVO();
        result.setOrderId(order.getId());
        result.setStatus(order.getStatus());
        result.setPayTime(order.getPayTime());
        return result;
    }
}
