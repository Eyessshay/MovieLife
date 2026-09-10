package com.movielife.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ActivityShowtimeVO {

    private Long showtimeId;
    private Long venueId;
    private String venueName;
    private Long hallId;
    private String hallName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private BigDecimal basePrice;
    private Integer availableSeats;
}
