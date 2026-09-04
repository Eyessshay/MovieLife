package com.movielife.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class TicketOrderItemVO {

    private Long seatId;
    private Integer rowNo;
    private Integer colNo;
    private String seatNo;
    private String seatType;
    private BigDecimal price;
    private String ticketCode;
    private Integer ticketStatus;
}
