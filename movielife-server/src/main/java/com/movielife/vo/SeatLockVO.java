package com.movielife.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SeatLockVO {

    private String lockToken;
    private LocalDateTime expireTime;
}
