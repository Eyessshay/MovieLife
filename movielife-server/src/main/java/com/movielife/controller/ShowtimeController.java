package com.movielife.controller;

import com.movielife.common.Result;
import com.movielife.dto.SeatLockRequest;
import com.movielife.dto.SeatReleaseRequest;
import com.movielife.interceptor.LoginRequired;
import com.movielife.service.SeatLockService;
import com.movielife.service.ShowtimeService;
import com.movielife.vo.SeatLockVO;
import com.movielife.vo.ShowtimeDetailVO;
import com.movielife.vo.ShowtimeSeatMapVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/showtimes")
@RequiredArgsConstructor
public class ShowtimeController {

    private final ShowtimeService showtimeService;
    private final SeatLockService seatLockService;

    @GetMapping("/{id}")
    public Result<ShowtimeDetailVO> detail(@PathVariable Long id) {
        return Result.ok(showtimeService.queryShowtimeDetail(id));
    }

    @GetMapping("/{id}/seats")
    public Result<ShowtimeSeatMapVO> seats(@PathVariable Long id) {
        return Result.ok(showtimeService.querySeatMap(id));
    }

    @PostMapping("/{id}/seats/lock")
    @LoginRequired
    public Result<SeatLockVO> lock(@PathVariable Long id,
                                   @Valid @RequestBody SeatLockRequest request) {
        return Result.ok(seatLockService.lock(id, request));
    }

    @PostMapping("/{id}/seats/release")
    @LoginRequired
    public Result<Void> release(@PathVariable Long id,
                                @Valid @RequestBody SeatReleaseRequest request) {
        seatLockService.release(id, request);
        return Result.ok();
    }
}
