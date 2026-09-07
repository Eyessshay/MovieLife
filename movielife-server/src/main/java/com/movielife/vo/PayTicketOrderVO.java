package com.movielife.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PayTicketOrderVO {

    private Long orderId;
    private String status;
    private LocalDateTime payTime;
}
