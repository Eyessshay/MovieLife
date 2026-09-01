package com.movielife.controller;

import com.movielife.common.Result;
import com.movielife.dto.CollectionCheckDTO;
import com.movielife.dto.CollectionQueryDTO;
import com.movielife.interceptor.LoginRequired;
import com.movielife.service.CollectionService;
import com.movielife.vo.CollectionCheckVO;
import com.movielife.vo.CollectionVO;
import com.movielife.vo.PageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/collections")
@RequiredArgsConstructor
public class CollectionController {

    private final CollectionService collectionService;

    @PutMapping("/{targetType}/{targetId}")
    @LoginRequired
    public Result<Void> collect(@PathVariable String targetType, @PathVariable Long targetId) {
        collectionService.collect(targetType, targetId);
        return Result.ok();
    }

    @DeleteMapping("/{targetType}/{targetId}")
    @LoginRequired
    public Result<Void> uncollect(@PathVariable String targetType, @PathVariable Long targetId) {
        collectionService.uncollect(targetType, targetId);
        return Result.ok();
    }

    @GetMapping
    @LoginRequired
    public Result<PageVO<CollectionVO>> list(@Valid CollectionQueryDTO query) {
        return Result.ok(collectionService.queryCollections(query));
    }

    @GetMapping("/check")
    @LoginRequired
    public Result<CollectionCheckVO> check(@Valid CollectionCheckDTO query) {
        return Result.ok(collectionService.check(query.getTargetType(), query.getTargetId()));
    }
}
