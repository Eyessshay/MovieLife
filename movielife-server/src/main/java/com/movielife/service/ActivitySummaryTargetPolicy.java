package com.movielife.service;

import com.movielife.entity.Activity;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ActivitySummaryTargetPolicy implements ReviewSummaryTargetPolicy {

    private static final int ACTIVE_STATUS = 1;

    private final ActivityMapper activityMapper;

    @Override
    public AiSummaryTargetType targetType() {
        return AiSummaryTargetType.ACTIVITY;
    }

    @Override
    public void validateForSnapshot(Long targetId) {
        Activity activity = activityMapper.selectById(targetId);
        if (activity == null || !Integer.valueOf(ACTIVE_STATUS).equals(activity.getStatus())) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
    }
}
