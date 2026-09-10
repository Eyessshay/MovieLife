package com.movielife.controller;

import com.movielife.common.Result;
import com.movielife.dto.ActivityQueryDTO;
import com.movielife.dto.ActivityShowtimeQueryDTO;
import com.movielife.service.ActivityService;
import com.movielife.vo.ActivityDetailVO;
import com.movielife.vo.ActivityListItemVO;
import com.movielife.vo.ActivityShowtimeVO;
import com.movielife.vo.PageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/activities")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;

    @GetMapping
    public Result<PageVO<ActivityListItemVO>> list(@Valid ActivityQueryDTO query) {
        return Result.ok(activityService.queryActivities(query));
    }

    @GetMapping("/{id}")
    public Result<ActivityDetailVO> detail(@PathVariable Long id) {
        return Result.ok(activityService.queryActivityDetail(id));
    }

    @GetMapping("/{id}/showtimes")
    public Result<List<ActivityShowtimeVO>> showtimes(
            @PathVariable Long id, @Valid ActivityShowtimeQueryDTO query) {
        return Result.ok(activityService.queryActivityShowtimes(id, query));
    }
}
