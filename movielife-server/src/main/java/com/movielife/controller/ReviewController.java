package com.movielife.controller;

import com.movielife.common.Result;
import com.movielife.dto.CreateReviewDTO;
import com.movielife.dto.ReviewQueryDTO;
import com.movielife.dto.UpdateReviewDTO;
import com.movielife.interceptor.LoginRequired;
import com.movielife.service.ReviewService;
import com.movielife.vo.PageVO;
import com.movielife.vo.ReviewLikeVO;
import com.movielife.vo.ReviewVO;
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
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping
    @LoginRequired
    public Result<ReviewVO> create(@Valid @RequestBody CreateReviewDTO request) {
        return Result.ok(reviewService.createReview(request));
    }

    @GetMapping
    public Result<PageVO<ReviewVO>> list(@Valid ReviewQueryDTO query) {
        return Result.ok(reviewService.queryReviews(query));
    }

    @GetMapping("/{id}")
    public Result<ReviewVO> detail(@PathVariable Long id) {
        return Result.ok(reviewService.queryReviewDetail(id));
    }

    @PutMapping("/{id}")
    @LoginRequired
    public Result<ReviewVO> update(@PathVariable Long id,
                                   @Valid @RequestBody UpdateReviewDTO request) {
        return Result.ok(reviewService.updateReview(id, request));
    }

    @DeleteMapping("/{id}")
    @LoginRequired
    public Result<Void> delete(@PathVariable Long id) {
        reviewService.deleteReview(id);
        return Result.ok();
    }

    @PutMapping("/{id}/like")
    @LoginRequired
    public Result<ReviewLikeVO> like(@PathVariable Long id) {
        return Result.ok(reviewService.likeReview(id));
    }

    @DeleteMapping("/{id}/like")
    @LoginRequired
    public Result<ReviewLikeVO> unlike(@PathVariable Long id) {
        return Result.ok(reviewService.unlikeReview(id));
    }
}
