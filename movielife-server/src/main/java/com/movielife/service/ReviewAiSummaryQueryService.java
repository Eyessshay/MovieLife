package com.movielife.service;

import com.movielife.ai.AiReviewSummaryCacheValue;
import com.movielife.config.AiReviewSummaryProperties;
import com.movielife.entity.AiReviewSummary;
import com.movielife.entity.AiSummaryTask;
import com.movielife.enums.AiSummaryPublicStatus;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ReviewMapper;
import com.movielife.utils.JsonListUtils;
import com.movielife.vo.ReviewAiSummaryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReviewAiSummaryQueryService {

    private final ReviewMapper reviewMapper;
    private final AiSummaryPersistenceService persistenceService;
    private final AiReviewSummaryCacheRepository cacheRepository;
    private final AiReviewSummaryProperties properties;
    private final JsonListUtils jsonListUtils;
    private final ReviewSummaryTargetPolicyRegistry policyRegistry;

    public ReviewAiSummaryVO query(String targetType, Long targetId) {
        AiSummaryTargetType resolvedType;
        try {
            resolvedType = AiSummaryTargetType.valueOf(targetType);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(ErrorCode.INVALID_TARGET_TYPE);
        }
        if (targetId == null || targetId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }
        policyRegistry.require(resolvedType).validateForSnapshot(targetId);

        int activeReviewCount = reviewMapper.countActiveByTarget(
                resolvedType.name(), targetId);
        AiSummaryTask task = persistenceService.findTask(resolvedType, targetId);
        if (activeReviewCount < properties.getMinReviews()) {
            return empty(resolvedType, targetId, AiSummaryPublicStatus.NOT_ENOUGH_REVIEWS,
                    activeReviewCount, activeReviewCount);
        }
        if (task == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        AiReviewSummaryCacheValue current = findCurrent(resolvedType, task, targetId);
        if (current != null) {
            boolean ready = "SUCCEEDED".equals(task.getStatus())
                    && task.getDirtyVersion() != null
                    && task.getDirtyVersion().equals(task.getProcessedVersion());
            return result(resolvedType, targetId,
                    ready ? AiSummaryPublicStatus.READY : AiSummaryPublicStatus.REFRESHING,
                    !ready, current);
        }
        if ("DEAD".equals(task.getStatus())) {
            return empty(resolvedType, targetId, AiSummaryPublicStatus.UNAVAILABLE, activeReviewCount, null);
        }
        if ("INSUFFICIENT_REVIEWS".equals(task.getStatus())) {
            return empty(resolvedType, targetId, AiSummaryPublicStatus.NOT_ENOUGH_REVIEWS,
                    activeReviewCount, Math.min(activeReviewCount, properties.getMaxReviews()));
        }
        if ("PENDING".equals(task.getStatus()) || "PROCESSING".equals(task.getStatus())
                || "RETRY_WAIT".equals(task.getStatus())) {
            return empty(resolvedType, targetId, AiSummaryPublicStatus.GENERATING, activeReviewCount, null);
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR);
    }

    private AiReviewSummaryCacheValue findCurrent(AiSummaryTargetType targetType,
                                                  AiSummaryTask task, Long targetId) {
        if (task.getCurrentSummaryId() == null) {
            return null;
        }
        Optional<AiReviewSummaryCacheValue> cached = cacheRepository.find(
                targetType, targetId);
        if (cached.isPresent() && task.getCurrentSummaryId().equals(cached.get().getSummaryId())) {
            return cached.get();
        }
        AiReviewSummary summary = persistenceService.findCurrentSummary(
                targetType, targetId);
        if (summary == null || !task.getCurrentSummaryId().equals(summary.getId())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        cacheRepository.put(summary);
        return cacheValue(summary);
    }

    private ReviewAiSummaryVO empty(AiSummaryTargetType targetType, Long targetId,
                                    AiSummaryPublicStatus status,
                                    Integer sourceCount, Integer inputCount) {
        ReviewAiSummaryVO result = new ReviewAiSummaryVO();
        result.setTargetType(targetType.name());
        result.setTargetId(targetId);
        result.setStatus(status.name());
        result.setSourceReviewCount(sourceCount);
        result.setInputReviewCount(inputCount);
        result.setStale(false);
        return result;
    }

    private ReviewAiSummaryVO result(AiSummaryTargetType targetType, Long targetId,
                                     AiSummaryPublicStatus status,
                                     boolean stale, AiReviewSummaryCacheValue value) {
        ReviewAiSummaryVO result = empty(targetType, targetId, status,
                value.getSourceReviewCount(), value.getInputReviewCount());
        result.setSummary(value.getSummary());
        result.setPros(jsonListUtils.readStrings(value.getPros()));
        result.setCons(jsonListUtils.readStrings(value.getCons()));
        result.setSuitableFor(jsonListUtils.readStrings(value.getSuitableFor()));
        result.setWarningPoints(jsonListUtils.readStrings(value.getWarningPoints()));
        result.setVersion(value.getVersion());
        result.setPromptVersion(value.getPromptVersion());
        result.setGeneratedAt(value.getGeneratedAt());
        result.setStale(stale);
        return result;
    }

    private AiReviewSummaryCacheValue cacheValue(AiReviewSummary summary) {
        AiReviewSummaryCacheValue value = new AiReviewSummaryCacheValue();
        value.setSummaryId(summary.getId());
        value.setTargetType(summary.getTargetType());
        value.setTargetId(summary.getTargetId());
        value.setSummary(summary.getSummary());
        value.setPros(summary.getPros());
        value.setCons(summary.getCons());
        value.setSuitableFor(summary.getSuitableFor());
        value.setWarningPoints(summary.getWarningPoints());
        value.setSourceReviewCount(summary.getSourceReviewCount());
        value.setInputReviewCount(summary.getInputReviewCount());
        value.setVersion(summary.getVersion());
        value.setPromptVersion(summary.getPromptVersion());
        value.setGeneratedAt(summary.getCreateTime());
        return value;
    }
}
