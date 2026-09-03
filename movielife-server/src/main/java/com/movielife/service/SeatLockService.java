package com.movielife.service;

import com.movielife.dto.SeatLockRequest;
import com.movielife.dto.SeatReleaseRequest;
import com.movielife.vo.SeatLockVO;

public interface SeatLockService {

    SeatLockVO lock(Long showtimeId, SeatLockRequest request);

    void release(Long showtimeId, SeatReleaseRequest request);
}
