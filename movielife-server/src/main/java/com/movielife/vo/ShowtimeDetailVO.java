package com.movielife.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ShowtimeDetailVO {

    private Long id;
    private Long venueId;
    private String venueName;
    private Long hallId;
    private String hallName;
    private String bizType;
    private Long movieId;
    private String movieName;
    private Long activityId;
    private String activityName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private BigDecimal basePrice;
}
