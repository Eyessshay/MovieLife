package com.movielife.service.impl;

import com.movielife.dto.CreateTicketOrderDTO;
import com.movielife.entity.TicketOrder;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.service.TicketOrderCreationContext;
import com.movielife.service.TicketOrderCreationValidator;
import com.movielife.service.TicketOrderService;
import com.movielife.service.SeatLockService;
import com.movielife.vo.CreateTicketOrderVO;
import com.movielife.vo.IssueTicketVO;
import java.util.List;
import com.movielife.vo.PageVO;
import com.movielife.vo.PayTicketOrderVO;
import com.movielife.vo.TicketOrderDetailVO;
import com.movielife.vo.TicketOrderListItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TicketOrderServiceImpl implements TicketOrderService {

    private static final int REDIS_CLEANUP_ATTEMPTS = 2;

    private final TicketOrderCreationValidator creationValidator;
    private final TicketOrderTransactionService transactionService;
    private final SeatLockService seatLockService;
    private final TicketOrderQueryService queryService;
    private final TicketOrderCancellationService cancellationService;
    private final TicketOrderPaymentService paymentService;
    private final TicketOrderIssuanceService issuanceService;

    @Override
    public List<IssueTicketVO> issueTickets(Long userId, Long orderId) {
        return issuanceService.issueTickets(userId, orderId);
    }

    @Override
    public CreateTicketOrderVO createOrder(Long userId, CreateTicketOrderDTO dto) {
        TicketOrderCreationContext context = creationValidator.validate(userId, dto);
        if (context.isConsumed()) {
            CreateTicketOrderVO existingOrder = toCreateOrderVO(context.getExistingOrder());
            cleanupRedisBestEffort(existingOrder.getOrderId(), context);
            return existingOrder;
        }

        try {
            CreateTicketOrderVO createdOrder = transactionService.createNewOrder(context);
            cleanupRedisBestEffort(createdOrder.getOrderId(), context);
            return createdOrder;
        } catch (DuplicateKeyException exception) {
            TicketOrderCreationContext retryContext = creationValidator.validate(userId, dto);
            if (retryContext.isConsumed()) {
                CreateTicketOrderVO existingOrder = toCreateOrderVO(retryContext.getExistingOrder());
                cleanupRedisBestEffort(existingOrder.getOrderId(), retryContext);
                return existingOrder;
            }
            throw exception;
        }
    }

    @Override
    public PageVO<TicketOrderListItemVO> queryMyOrders(
            Long userId, Integer page, Integer pageSize) {
        return queryService.queryMyOrders(userId, page, pageSize);
    }

    @Override
    public TicketOrderDetailVO queryOrderDetail(Long userId, Long orderId) {
        return queryService.queryOrderDetail(userId, orderId);
    }

    @Override
    public void cancelOrder(Long userId, Long orderId) {
        cancellationService.cancelOrder(userId, orderId);
    }

    @Override
    public PayTicketOrderVO payOrder(Long userId, Long orderId) {
        return paymentService.payOrder(userId, orderId);
    }

    private void cleanupRedisBestEffort(Long orderId, TicketOrderCreationContext context) {
        for (int attempt = 1; attempt <= REDIS_CLEANUP_ATTEMPTS; attempt++) {
            try {
                seatLockService.cleanupLock(
                        context.getShowtimeId(), context.getSeatIds(), context.getLockToken());
                return;
            } catch (RuntimeException exception) {
                if (attempt < REDIS_CLEANUP_ATTEMPTS) {
                    log.warn("Order {} committed, retrying Redis seat-lock cleanup: "
                                    + "lockToken={}, showtimeId={}, seatIds={}, attempt={}",
                            orderId, context.getLockToken(), context.getShowtimeId(),
                            context.getSeatIds(), attempt, exception);
                } else {
                    log.error("Order {} committed, Redis seat-lock cleanup failed: "
                                    + "lockToken={}, showtimeId={}, seatIds={}, attempts={}",
                            orderId, context.getLockToken(), context.getShowtimeId(),
                            context.getSeatIds(), REDIS_CLEANUP_ATTEMPTS, exception);
                }
            }
        }
    }

    private CreateTicketOrderVO toCreateOrderVO(TicketOrder order) {
        if (order == null || order.getId() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        CreateTicketOrderVO result = new CreateTicketOrderVO();
        result.setOrderId(order.getId());
        result.setStatus(order.getStatus());
        result.setTotalAmount(order.getTotalAmount());
        result.setExpireTime(order.getExpireTime());
        return result;
    }
}
