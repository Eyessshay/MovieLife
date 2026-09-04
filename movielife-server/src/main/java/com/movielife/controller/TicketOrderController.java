package com.movielife.controller;

import com.movielife.common.Result;
import com.movielife.dto.CreateTicketOrderDTO;
import com.movielife.interceptor.LoginRequired;
import com.movielife.service.TicketOrderService;
import com.movielife.utils.UserHolder;
import com.movielife.vo.CreateTicketOrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class TicketOrderController {

    private final TicketOrderService ticketOrderService;

    @PostMapping
    @LoginRequired
    public Result<CreateTicketOrderVO> createOrder(
            @Valid @RequestBody CreateTicketOrderDTO request) {
        return Result.ok(ticketOrderService.createOrder(UserHolder.getUserId(), request));
    }
}
