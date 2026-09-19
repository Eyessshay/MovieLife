package com.movielife.service;

import com.movielife.enums.AiSummaryTargetType;

public interface ReviewSummaryTargetPolicy {
    AiSummaryTargetType targetType();
    void validateForSnapshot(Long targetId);
}
