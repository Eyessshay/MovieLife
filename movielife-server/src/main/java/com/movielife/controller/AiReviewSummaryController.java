package com.movielife.controller;

import com.movielife.common.Result;
import com.movielife.service.MovieReviewAiSummaryQueryService;
import com.movielife.vo.ReviewAiSummaryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/summaries")
@RequiredArgsConstructor
public class AiReviewSummaryController {

    private final MovieReviewAiSummaryQueryService queryService;

    @GetMapping("/{targetType}/{targetId}")
    public Result<ReviewAiSummaryVO> query(@PathVariable String targetType,
                                           @PathVariable Long targetId) {
        return Result.ok(queryService.query(targetType, targetId));
    }
}
