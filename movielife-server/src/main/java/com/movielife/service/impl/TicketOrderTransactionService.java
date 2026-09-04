package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.movielife.entity.ShowtimeSeat;
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
import com.movielife.service.ShowtimeService;
import com.movielife.service.TicketOrderCreationContext;
import com.movielife.vo.CreateTicketOrderVO;
import com.movielife.vo.ShowtimeDetailVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TicketOrderTransactionService {

    static final long ORDER_PAYMENT_TTL_MINUTES = 15L;
    private static final int AVAILABLE_STATUS = 0;

    private final ShowtimeService showtimeService;
    private final ShowtimeMapper showtimeMapper;
    private final ShowtimeSeatMapper showtimeSeatMapper;
    private final TicketOrderMapper ticketOrderMapper;
    private final TicketOrderItemMapper ticketOrderItemMapper;

    @Transactional
    public CreateTicketOrderVO createNewOrder(TicketOrderCreationContext context) {
        validateContext(context);
        ShowtimeDetailVO showtime = showtimeService.queryShowtimeDetail(context.getShowtimeId());
        List<ShowtimeSeat> showtimeSeats = queryAndValidateShowtimeSeats(context);
        Map<Long, BigDecimal> seatPriceMap = buildSeatPriceMap(showtimeSeats, context.getSeatIds());

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime orderExpireTime = calculateOrderExpireTime(now, showtime.getStartTime());
        BigDecimal totalAmount = calculateTotalAmount(context.getSeatIds(), seatPriceMap);

        TicketOrder order = buildOrder(context, totalAmount, orderExpireTime, now);
        if (ticketOrderMapper.insert(order) != 1 || order.getId() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        List<TicketOrderItem> items = buildOrderItems(order.getId(), context.getSeatIds(), seatPriceMap);
        if (ticketOrderItemMapper.insertBatch(items) != items.size()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        int heldSeatCount = showtimeSeatMapper.holdSeatsForOrder(
                context.getShowtimeId(), context.getSeatIds(), order.getId());
        if (heldSeatCount != context.getSeatIds().size()) {
            throw new BusinessException(ErrorCode.SEAT_NOT_AVAILABLE);
        }

        int showtimeUpdated = showtimeMapper.decreaseAvailableSeats(
                context.getShowtimeId(), context.getSeatIds().size());
        if (showtimeUpdated != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return toCreateOrderVO(order);
    }

    LocalDateTime calculateOrderExpireTime(LocalDateTime now, LocalDateTime showtimeStartTime) {
        if (now == null || showtimeStartTime == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        LocalDateTime normalExpireTime = now.plusMinutes(ORDER_PAYMENT_TTL_MINUTES);
        LocalDateTime orderExpireTime = normalExpireTime.isBefore(showtimeStartTime)
                ? normalExpireTime
                : showtimeStartTime;
        if (!orderExpireTime.isAfter(now)) {
            throw new BusinessException(ErrorCode.SHOWTIME_NOT_FOUND);
        }
        return orderExpireTime;
    }

    private List<ShowtimeSeat> queryAndValidateShowtimeSeats(TicketOrderCreationContext context) {
        List<ShowtimeSeat> showtimeSeats = showtimeSeatMapper.selectList(
                new LambdaQueryWrapper<ShowtimeSeat>()
                        .eq(ShowtimeSeat::getShowtimeId, context.getShowtimeId())
                        .in(ShowtimeSeat::getSeatId, context.getSeatIds())
        );
        if (showtimeSeats.size() != context.getSeatIds().size()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        Map<Long, ShowtimeSeat> statesBySeatId = new HashMap<>();
        for (ShowtimeSeat state : showtimeSeats) {
            if (state.getSeatId() == null || statesBySeatId.put(state.getSeatId(), state) != null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            if (!Integer.valueOf(AVAILABLE_STATUS).equals(state.getStatus())
                    || state.getLockOrderId() != null) {
                throw new BusinessException(ErrorCode.SEAT_NOT_AVAILABLE);
            }
        }
        if (!statesBySeatId.keySet().containsAll(context.getSeatIds())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return showtimeSeats;
    }

    private Map<Long, BigDecimal> buildSeatPriceMap(List<ShowtimeSeat> showtimeSeats,
                                                     List<Long> seatIds) {
        Map<Long, BigDecimal> prices = new HashMap<>();
        for (ShowtimeSeat state : showtimeSeats) {
            if (state.getPrice() == null || prices.put(state.getSeatId(), state.getPrice()) != null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
        }
        if (prices.size() != seatIds.size() || !prices.keySet().containsAll(seatIds)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return prices;
    }

    private BigDecimal calculateTotalAmount(List<Long> seatIds,
                                            Map<Long, BigDecimal> seatPriceMap) {
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Long seatId : seatIds) {
            BigDecimal price = seatPriceMap.get(seatId);
            if (price == null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            totalAmount = totalAmount.add(price);
        }
        return totalAmount;
    }

    private TicketOrder buildOrder(TicketOrderCreationContext context,
                                   BigDecimal totalAmount,
                                   LocalDateTime orderExpireTime,
                                   LocalDateTime now) {
        TicketOrder order = new TicketOrder();
        order.setUserId(context.getUserId());
        order.setShowtimeId(context.getShowtimeId());
        order.setLockToken(context.getLockToken());
        order.setTotalAmount(totalAmount);
        order.setStatus(TicketOrderStatus.UNPAID.getDbValue());
        order.setExpireTime(orderExpireTime);
        order.setCreateTime(now);
        return order;
    }

    private List<TicketOrderItem> buildOrderItems(Long orderId,
                                                   List<Long> seatIds,
                                                   Map<Long, BigDecimal> seatPriceMap) {
        List<TicketOrderItem> items = new ArrayList<>(seatIds.size());
        for (Long seatId : seatIds) {
            TicketOrderItem item = new TicketOrderItem();
            item.setOrderId(orderId);
            item.setSeatId(seatId);
            item.setPrice(seatPriceMap.get(seatId));
            item.setTicketStatus(TicketStatus.PENDING.getCode());
            items.add(item);
        }
        return items;
    }

    private CreateTicketOrderVO toCreateOrderVO(TicketOrder order) {
        CreateTicketOrderVO result = new CreateTicketOrderVO();
        result.setOrderId(order.getId());
        result.setStatus(order.getStatus());
        result.setTotalAmount(order.getTotalAmount());
        result.setExpireTime(order.getExpireTime());
        return result;
    }

    private void validateContext(TicketOrderCreationContext context) {
        if (context == null
                || context.isConsumed()
                || context.getUserId() == null
                || context.getShowtimeId() == null
                || context.getSeatIds() == null
                || context.getSeatIds().isEmpty()
                || context.getLockToken() == null
                || context.getLockMetadata() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
