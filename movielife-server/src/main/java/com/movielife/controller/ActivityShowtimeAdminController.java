package com.movielife.controller;

import com.movielife.common.Result;
import com.movielife.dto.AdminActivityShowtimeQueryDTO;
import com.movielife.dto.CreateActivityShowtimeDTO;
import com.movielife.interceptor.LoginRequired;
import com.movielife.service.ActivityShowtimeAdminService;
import com.movielife.utils.UserHolder;
import com.movielife.vo.AdminActivityShowtimeVO;
import com.movielife.vo.PageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/admin/venues/{venueId}/activity-showtimes")
@RequiredArgsConstructor
public class ActivityShowtimeAdminController {

    private final ActivityShowtimeAdminService activityShowtimeAdminService;

    @GetMapping
    @LoginRequired
    public Result<PageVO<AdminActivityShowtimeVO>> list(
            @PathVariable Long venueId, @Valid AdminActivityShowtimeQueryDTO query) {
        return Result.ok(activityShowtimeAdminService.queryShowtimes(
                UserHolder.getUserId(), venueId, query));
    }

    @GetMapping("/{showtimeId}")
    @LoginRequired
    public Result<AdminActivityShowtimeVO> detail(
            @PathVariable Long venueId, @PathVariable Long showtimeId) {
        return Result.ok(activityShowtimeAdminService.queryShowtime(
                UserHolder.getUserId(), venueId, showtimeId));
    }

    @PostMapping
    @LoginRequired
    public Result<AdminActivityShowtimeVO> create(
            @PathVariable Long venueId,
            @Valid @RequestBody CreateActivityShowtimeDTO request) {
        return Result.ok(activityShowtimeAdminService.createShowtime(
                UserHolder.getUserId(), venueId, request));
    }
}
