package com.movielife.vo;

import lombok.Data;

@Data
public class IssueTicketVO {
    private Long seatId;
    private String ticketCode;
    private Integer ticketStatus;
}
