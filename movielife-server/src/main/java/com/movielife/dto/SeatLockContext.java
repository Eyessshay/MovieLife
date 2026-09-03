package com.movielife.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Carries validated and normalized seat-lock data inside the service layer.
 */
@Data
public class SeatLockContext {

    private Long userId;
    private Long showtimeId;
    private Long hallId;
    private List<Long> seatIds;
    private String lockToken;
    private LocalDateTime expireTime;
}
