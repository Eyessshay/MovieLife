package com.movielife.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VerifyTicketVO {

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
