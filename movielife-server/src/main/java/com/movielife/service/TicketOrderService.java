package com.movielife.service;

import com.movielife.dto.CreateTicketOrderDTO;
import com.movielife.vo.CreateTicketOrderVO;
import com.movielife.vo.PageVO;
import com.movielife.vo.TicketOrderDetailVO;
import com.movielife.vo.TicketOrderListItemVO;

public interface TicketOrderService {

    CreateTicketOrderVO createOrder(Long userId, CreateTicketOrderDTO dto);

    PageVO<TicketOrderListItemVO> queryMyOrders(
            Long userId, Integer page, Integer pageSize);

    TicketOrderDetailVO queryOrderDetail(Long userId, Long orderId);
}
