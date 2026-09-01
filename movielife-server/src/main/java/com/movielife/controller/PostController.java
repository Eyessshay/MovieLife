package com.movielife.controller;

import com.movielife.common.Result;
import com.movielife.dto.CreatePostDTO;
import com.movielife.dto.PostQueryDTO;
import com.movielife.dto.UpdatePostDTO;
import com.movielife.interceptor.LoginRequired;
import com.movielife.service.PostService;
import com.movielife.vo.PageVO;
import com.movielife.vo.PostLikeVO;
import com.movielife.vo.PostVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    @PostMapping
    @LoginRequired
    public Result<PostVO> create(@Valid @RequestBody CreatePostDTO request) {
        return Result.ok(postService.create(request));
    }

    @GetMapping
    public Result<PageVO<PostVO>> list(@Valid PostQueryDTO query) {
        return Result.ok(postService.query(query));
    }

    @GetMapping("/{id}")
    public Result<PostVO> detail(@PathVariable Long id) {
        return Result.ok(postService.detail(id));
    }

    @PutMapping("/{id}")
    @LoginRequired
    public Result<PostVO> update(@PathVariable Long id, @Valid @RequestBody UpdatePostDTO request) {
        return Result.ok(postService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @LoginRequired
    public Result<Void> delete(@PathVariable Long id) {
        postService.delete(id);
        return Result.ok();
    }

    @PutMapping("/{id}/like")
    @LoginRequired
    public Result<PostLikeVO> like(@PathVariable Long id) {
        return Result.ok(postService.like(id));
    }

    @DeleteMapping("/{id}/like")
    @LoginRequired
    public Result<PostLikeVO> unlike(@PathVariable Long id) {
        return Result.ok(postService.unlike(id));
    }
}
