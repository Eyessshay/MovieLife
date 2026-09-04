package com.movielife.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CreateTicketOrderVO {

    private Long orderId;
    private String status;
    private BigDecimal totalAmount;
    private LocalDateTime expireTime;
}
