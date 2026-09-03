package com.movielife.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Redis metadata stored for one seat-lock token.
 */
@Data
public class SeatLockMetadata {

    private Long userId;
    private Long showtimeId;
    private List<Long> seatIds;
    private LocalDateTime expireTime;
}
