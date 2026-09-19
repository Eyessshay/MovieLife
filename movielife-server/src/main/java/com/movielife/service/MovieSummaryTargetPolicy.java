package com.movielife.service;

import com.movielife.enums.AiSummaryTargetType;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.MovieMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MovieSummaryTargetPolicy implements ReviewSummaryTargetPolicy {

    private final MovieMapper movieMapper;

    @Override
    public AiSummaryTargetType targetType() {
        return AiSummaryTargetType.MOVIE;
    }

    @Override
    public void validateForSnapshot(Long targetId) {
        if (movieMapper.selectById(targetId) == null) {
            throw new BusinessException(ErrorCode.MOVIE_NOT_FOUND);
        }
    }
}
