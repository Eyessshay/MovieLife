package com.movielife.controller;

import com.movielife.common.Result;
import com.movielife.service.ShowtimeService;
import com.movielife.vo.ShowtimeDetailVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/showtimes")
@RequiredArgsConstructor
public class ShowtimeController {

    private final ShowtimeService showtimeService;

    @GetMapping("/{id}")
    public Result<ShowtimeDetailVO> detail(@PathVariable Long id) {
        return Result.ok(showtimeService.queryShowtimeDetail(id));
    }
}
