package com.movielife.service;

import com.movielife.dto.SeatLockRequest;
import com.movielife.dto.SeatReleaseRequest;
import com.movielife.vo.SeatLockVO;

import java.util.List;

public interface SeatLockService {

    SeatLockVO lock(Long showtimeId, SeatLockRequest request);

    void release(Long showtimeId, SeatReleaseRequest request);

    void cleanupLock(Long showtimeId, List<Long> seatIds, String lockToken);
}
