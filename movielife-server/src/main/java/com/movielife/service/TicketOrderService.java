package com.movielife.service;

import com.movielife.dto.CreateTicketOrderDTO;
import com.movielife.vo.CreateTicketOrderVO;
import com.movielife.vo.IssueTicketVO;
import java.util.List;
import com.movielife.vo.PageVO;
import com.movielife.vo.PayTicketOrderVO;
import com.movielife.vo.TicketOrderDetailVO;
import com.movielife.vo.TicketOrderListItemVO;

public interface TicketOrderService {

    List<IssueTicketVO> issueTickets(Long userId, Long orderId);

    CreateTicketOrderVO createOrder(Long userId, CreateTicketOrderDTO dto);

    PageVO<TicketOrderListItemVO> queryMyOrders(
            Long userId, Integer page, Integer pageSize);

    TicketOrderDetailVO queryOrderDetail(Long userId, Long orderId);

    void cancelOrder(Long userId, Long orderId);

    PayTicketOrderVO payOrder(Long userId, Long orderId);
}
