package com.movielife.service;

import com.movielife.dto.CreateTicketOrderDTO;
import com.movielife.vo.CreateTicketOrderVO;

public interface TicketOrderService {

    CreateTicketOrderVO createOrder(Long userId, CreateTicketOrderDTO dto);
}
