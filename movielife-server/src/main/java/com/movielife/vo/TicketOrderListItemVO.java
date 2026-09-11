package com.movielife.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class TicketOrderListItemVO {

    private Long orderId;
    private Long showtimeId;
    private String bizType;
    private Long movieId;
    private String movieName;
    private Long activityId;
    private String activityName;
    private Long venueId;
    private String venueName;
    private Long hallId;
    private String hallName;
    private LocalDateTime startTime;
    private String status;
    private BigDecimal totalAmount;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private Integer seatCount;
}
