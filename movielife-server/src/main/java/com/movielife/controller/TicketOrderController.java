package com.movielife.controller;

import com.movielife.common.Result;
import com.movielife.dto.CreateTicketOrderDTO;
import com.movielife.interceptor.LoginRequired;
import com.movielife.service.TicketOrderService;
import com.movielife.utils.UserHolder;
import com.movielife.vo.CreateTicketOrderVO;
import com.movielife.vo.PageVO;
import com.movielife.vo.PayTicketOrderVO;
import com.movielife.vo.TicketOrderDetailVO;
import com.movielife.vo.TicketOrderListItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping
    @LoginRequired
    public Result<PageVO<TicketOrderListItemVO>> queryMyOrders(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return Result.ok(ticketOrderService.queryMyOrders(
                UserHolder.getUserId(), page, pageSize));
    }

    @GetMapping("/{id}")
    @LoginRequired
    public Result<TicketOrderDetailVO> queryOrderDetail(@PathVariable Long id) {
        return Result.ok(ticketOrderService.queryOrderDetail(UserHolder.getUserId(), id));
    }

    @PostMapping("/{id}/cancel")
    @LoginRequired
    public Result<Void> cancelOrder(@PathVariable Long id) {
        ticketOrderService.cancelOrder(UserHolder.getUserId(), id);
        return Result.ok();
    }

    @PostMapping("/{id}/pay")
    @LoginRequired
    public Result<PayTicketOrderVO> payOrder(@PathVariable Long id) {
        return Result.ok(ticketOrderService.payOrder(UserHolder.getUserId(), id));
    }
}
