package com.movielife.service.impl;

import com.movielife.dto.CreateTicketOrderDTO;
import com.movielife.entity.TicketOrder;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.service.TicketOrderCreationContext;
import com.movielife.service.TicketOrderCreationValidator;
import com.movielife.service.TicketOrderService;
import com.movielife.vo.CreateTicketOrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketOrderServiceImpl implements TicketOrderService {

    private final TicketOrderCreationValidator creationValidator;
    private final TicketOrderTransactionService transactionService;

    @Override
    public CreateTicketOrderVO createOrder(Long userId, CreateTicketOrderDTO dto) {
        TicketOrderCreationContext context = creationValidator.validate(userId, dto);
        if (context.isConsumed()) {
            return toCreateOrderVO(context.getExistingOrder());
        }

        try {
            return transactionService.createNewOrder(context);
        } catch (DuplicateKeyException exception) {
            TicketOrderCreationContext retryContext = creationValidator.validate(userId, dto);
            if (retryContext.isConsumed()) {
                return toCreateOrderVO(retryContext.getExistingOrder());
            }
            throw exception;
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
