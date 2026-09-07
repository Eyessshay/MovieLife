package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.enums.TicketOrderStatus;
import com.movielife.enums.TicketStatus;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.TicketOrderMapper;
import com.movielife.mapper.TicketOrderItemMapper;
import com.movielife.vo.IssueTicketVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketOrderIssuanceService {
    private final TicketOrderMapper orderMapper;
    private final TicketOrderItemMapper itemMapper;

    @Transactional
    public List<IssueTicketVO> issueTickets(Long userId, Long orderId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (orderId == null || orderId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }
        TicketOrder order = orderMapper.selectByIdForUpdate(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!TicketOrderStatus.PAID.getDbValue().equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT);
        }
        List<TicketOrderItem> items = itemMapper.selectList(new LambdaQueryWrapper<TicketOrderItem>()
                .eq(TicketOrderItem::getOrderId, orderId).orderByAsc(TicketOrderItem::getSeatId));
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        List<TicketOrderItem> pending = new ArrayList<>();
        Set<Long> seatIds = new HashSet<>();
        Set<Long> itemIds = new HashSet<>();
        Set<String> codes = new HashSet<>();
        for (TicketOrderItem item : items) {
            if (item == null || item.getId() == null || item.getSeatId() == null
                    || !orderId.equals(item.getOrderId()) || !itemIds.add(item.getId())
                    || !seatIds.add(item.getSeatId())) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            if (Integer.valueOf(TicketStatus.PENDING.getCode()).equals(item.getTicketStatus())
                    && item.getTicketCode() == null) {
                pending.add(item);
            } else if (!Integer.valueOf(TicketStatus.VALID.getCode()).equals(item.getTicketStatus())
                    || item.getTicketCode() == null || item.getTicketCode().trim().isEmpty()
                    || !codes.add(item.getTicketCode())) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
        }
        for (TicketOrderItem item : pending) {
            item.setTicketCode(UUID.randomUUID().toString().replace("-", ""));
            item.setTicketStatus(TicketStatus.VALID.getCode());
        }
        if (!pending.isEmpty() && itemMapper.issueBatch(orderId, pending,
                TicketStatus.PENDING.getCode(), TicketStatus.VALID.getCode()) != pending.size()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        List<IssueTicketVO> result = new ArrayList<>();
        for (TicketOrderItem item : items) {
            IssueTicketVO ticket = new IssueTicketVO();
            ticket.setSeatId(item.getSeatId());
            ticket.setTicketCode(item.getTicketCode());
            ticket.setTicketStatus(item.getTicketStatus());
            result.add(ticket);
        }
        return result;
    }
}
