package com.movielife.controller;

import com.movielife.common.Result;
import com.movielife.dto.VerifyTicketDTO;
import com.movielife.interceptor.LoginRequired;
import com.movielife.service.TicketVerificationResult;
import com.movielife.service.impl.TicketVerificationService;
import com.movielife.utils.UserHolder;
import com.movielife.vo.VerifyTicketVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/admin/tickets")
@RequiredArgsConstructor
public class TicketVerificationController {

    private final TicketVerificationService ticketVerificationService;

    @PostMapping("/verify")
    @LoginRequired
    public Result<VerifyTicketVO> verifyTicket(@Valid @RequestBody VerifyTicketDTO request) {
        TicketVerificationResult result = ticketVerificationService.verifyTicket(
                UserHolder.getUserId(), request.getTicketCode());
        return Result.ok(toVO(result));
    }

    private VerifyTicketVO toVO(TicketVerificationResult result) {
        VerifyTicketVO vo = new VerifyTicketVO();
        vo.setTicketCode(result.getTicketCode());
        vo.setTicketStatus(result.getTicketStatus());
        vo.setUsedTime(result.getUsedTime());
        vo.setOrderId(result.getOrderId());
        vo.setShowtimeId(result.getShowtimeId());
        vo.setVenueId(result.getVenueId());
        vo.setHallId(result.getHallId());
        vo.setSeatId(result.getSeatId());
        vo.setSeatNo(result.getSeatNo());
        return vo;
    }
}
