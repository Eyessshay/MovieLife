package com.movielife.controller;

import com.movielife.common.Result;
import com.movielife.dto.FollowQueryDTO;
import com.movielife.interceptor.LoginRequired;
import com.movielife.service.FollowService;
import com.movielife.vo.FollowUserVO;
import com.movielife.vo.PageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/follow")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    @PutMapping("/{id}")
    @LoginRequired
    public Result<Void> follow(@PathVariable Long id) {
        followService.follow(id);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @LoginRequired
    public Result<Void> unfollow(@PathVariable Long id) {
        followService.unfollow(id);
        return Result.ok();
    }

    @GetMapping("/common/{id}")
    @LoginRequired
    public Result<List<FollowUserVO>> common(@PathVariable Long id) {
        return Result.ok(followService.common(id));
    }

    @GetMapping("/following/{id}")
    public Result<PageVO<FollowUserVO>> following(@PathVariable Long id, @Valid FollowQueryDTO query) {
        return Result.ok(followService.following(id, query));
    }

    @GetMapping("/followers/{id}")
    public Result<PageVO<FollowUserVO>> followers(@PathVariable Long id, @Valid FollowQueryDTO query) {
        return Result.ok(followService.followers(id, query));
    }
}
