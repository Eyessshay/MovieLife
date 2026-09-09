package com.movielife.service;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TicketVerificationResult {

    private String ticketCode;
    private Integer ticketStatus;
    private LocalDateTime usedTime;
    private Long orderId;
    private Long showtimeId;
    private Long venueId;
    private Long hallId;
    private Long seatId;
    private String seatNo;
}
