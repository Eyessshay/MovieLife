package com.movielife.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AdminActivityShowtimeVO {

    private Long showtimeId;
    private Long activityId;
    private String activityTitle;
    private Long hallId;
    private String hallName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private BigDecimal basePrice;
    private Integer status;
    private Integer totalSeats;
    private Integer availableSeats;
}
