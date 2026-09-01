package com.movielife.controller;

import com.movielife.common.Result;
import com.movielife.dto.FeedQueryDTO;
import com.movielife.interceptor.LoginRequired;
import com.movielife.service.FeedService;
import com.movielife.vo.PostVO;
import com.movielife.vo.ScrollVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/feed")
@RequiredArgsConstructor
public class FeedController {

    private final FeedService feedService;

    @GetMapping("/following")
    @LoginRequired
    public Result<ScrollVO<PostVO>> following(@Valid FeedQueryDTO query) {
        return Result.ok(feedService.queryFollowingFeed(query));
    }
}
