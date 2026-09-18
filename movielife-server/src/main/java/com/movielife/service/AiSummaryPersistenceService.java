package com.movielife.service;

import com.movielife.ai.AiSummaryPublication;
import com.movielife.entity.AiReviewSummary;
import com.movielife.entity.AiSummaryTask;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.mapper.AiReviewSummaryMapper;
import com.movielife.mapper.AiSummaryTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AiSummaryPersistenceService {

    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z0-9_]{1,64}");

    private final AiSummaryTaskMapper taskMapper;
    private final AiReviewSummaryMapper summaryMapper;

    public AiSummaryTask markDirty(AiSummaryTargetType targetType,
                                   Long targetId,
                                   LocalDateTime now) {
        validateTarget(targetType, targetId);
        requireTime(now, "now");
        int affected = taskMapper.markDirty(targetType.name(), targetId, now);
        if (affected <= 0) {
            throw new IllegalStateException("Failed to mark AI summary task dirty");
        }
        return requireTask(targetType, targetId);
    }

    public Optional<AiSummaryTask> claim(AiSummaryTargetType targetType,
                                         Long targetId,
                                         String leaseOwner,
                                         LocalDateTime now,
                                         LocalDateTime leaseUntil) {
        validateTarget(targetType, targetId);
        validateLease(leaseOwner, now, leaseUntil);
        int affected = taskMapper.claim(
                targetType.name(), targetId, leaseOwner, now, leaseUntil);
        if (affected == 0) {
            return Optional.empty();
        }
        requireSingleRow(affected, "claim AI summary task");
        return Optional.of(requireTask(targetType, targetId));
    }

    public boolean renewLease(AiSummaryTargetType targetType,
                              Long targetId,
                              String leaseOwner,
                              LocalDateTime now,
                              LocalDateTime leaseUntil) {
        validateTarget(targetType, targetId);
        validateLease(leaseOwner, now, leaseUntil);
        int affected = taskMapper.renewLease(
                targetType.name(), targetId, leaseOwner, now, leaseUntil);
        requireAtMostOneRow(affected, "renew AI summary lease");
        return affected == 1;
    }

    public boolean markRetry(AiSummaryTargetType targetType,
                             Long targetId,
                             Long sourceVersion,
                             String leaseOwner,
                             String errorCode,
                             LocalDateTime now,
                             LocalDateTime nextRetryTime) {
        validateTransition(targetType, targetId, sourceVersion, leaseOwner, now);
        validateErrorCode(errorCode);
        requireTime(nextRetryTime, "nextRetryTime");
        if (!nextRetryTime.isAfter(now)) {
            throw new IllegalArgumentException("nextRetryTime must be after now");
        }
        int affected = taskMapper.markRetry(targetType.name(), targetId, sourceVersion,
                leaseOwner, errorCode, now, nextRetryTime);
        requireAtMostOneRow(affected, "mark AI summary retry");
        return affected == 1;
    }

    public boolean markDead(AiSummaryTargetType targetType,
                            Long targetId,
                            Long sourceVersion,
                            String leaseOwner,
                            String errorCode,
                            LocalDateTime now) {
        validateTransition(targetType, targetId, sourceVersion, leaseOwner, now);
        validateErrorCode(errorCode);
        int affected = taskMapper.markDead(targetType.name(), targetId, sourceVersion,
                leaseOwner, errorCode, now);
        requireAtMostOneRow(affected, "mark AI summary dead");
        return affected == 1;
    }

    public boolean markInsufficient(AiSummaryTargetType targetType,
                                    Long targetId,
                                    Long sourceVersion,
                                    String leaseOwner,
                                    LocalDateTime now) {
        validateTransition(targetType, targetId, sourceVersion, leaseOwner, now);
        int affected = taskMapper.markInsufficient(
                targetType.name(), targetId, sourceVersion, leaseOwner, now);
        requireAtMostOneRow(affected, "mark AI summary insufficient");
        return affected == 1;
    }

    @Transactional
    public AiReviewSummary publish(AiSummaryPublication publication,
                                   String leaseOwner,
                                   LocalDateTime now) {
        validatePublication(publication);
        validateLeaseOwner(leaseOwner);
        requireTime(now, "now");

        AiSummaryTargetType targetType = publication.getTargetType();
        AiSummaryTask task = taskMapper.selectByTargetForUpdate(
                targetType.name(), publication.getTargetId());
        validatePublicationLease(task, publication.getSourceVersion(), leaseOwner, now);

        AiReviewSummary summary = summaryMapper.selectByInput(
                targetType.name(), publication.getTargetId(), publication.getPromptVersion(),
                publication.getSourceFingerprint());
        int lastSummaryVersion = task.getLastSummaryVersion();
        if (summary == null) {
            lastSummaryVersion++;
            summary = toEntity(publication, lastSummaryVersion, now);
            requireSingleRow(summaryMapper.insert(summary), "insert AI review summary");
            if (summary.getId() == null) {
                throw new IllegalStateException("AI review summary id was not generated");
            }
        }

        int affected = taskMapper.publish(task.getId(), publication.getSourceVersion(),
                leaseOwner, summary.getId(), lastSummaryVersion, now);
        requireSingleRow(affected, "publish AI review summary");
        return summary;
    }

    public AiSummaryTask findTask(AiSummaryTargetType targetType, Long targetId) {
        validateTarget(targetType, targetId);
        return taskMapper.selectByTarget(targetType.name(), targetId);
    }

    public AiReviewSummary findCurrentSummary(AiSummaryTargetType targetType, Long targetId) {
        validateTarget(targetType, targetId);
        return summaryMapper.selectCurrent(targetType.name(), targetId);
    }

    private AiSummaryTask requireTask(AiSummaryTargetType targetType, Long targetId) {
        AiSummaryTask task = taskMapper.selectByTarget(targetType.name(), targetId);
        if (task == null) {
            throw new IllegalStateException("AI summary task disappeared after update");
        }
        return task;
    }

    private AiReviewSummary toEntity(AiSummaryPublication publication,
                                     int version,
                                     LocalDateTime now) {
        AiReviewSummary summary = new AiReviewSummary();
        summary.setTargetType(publication.getTargetType().name());
        summary.setTargetId(publication.getTargetId());
        summary.setPromptVersion(publication.getPromptVersion());
        summary.setSourceFingerprint(publication.getSourceFingerprint());
        summary.setSourceVersion(publication.getSourceVersion());
        summary.setSummary(publication.getSummary());
        summary.setPros(publication.getPros());
        summary.setCons(publication.getCons());
        summary.setSuitableFor(publication.getSuitableFor());
        summary.setWarningPoints(publication.getWarningPoints());
        summary.setSourceReviewCount(publication.getSourceReviewCount());
        summary.setInputReviewCount(publication.getInputReviewCount());
        summary.setModelName(publication.getModelName());
        summary.setVersion(version);
        summary.setCreateTime(now);
        return summary;
    }

    private void validatePublication(AiSummaryPublication publication) {
        if (publication == null) {
            throw new IllegalArgumentException("publication is required");
        }
        validateTarget(publication.getTargetType(), publication.getTargetId());
        if (publication.getSourceVersion() == null || publication.getSourceVersion() <= 0) {
            throw new IllegalArgumentException("sourceVersion must be positive");
        }
        if (!StringUtils.hasText(publication.getPromptVersion())
                || publication.getPromptVersion().length() > 64) {
            throw new IllegalArgumentException("promptVersion is invalid");
        }
        if (publication.getSourceFingerprint() == null
                || !FINGERPRINT.matcher(publication.getSourceFingerprint()).matches()) {
            throw new IllegalArgumentException("sourceFingerprint must be lowercase SHA-256 hex");
        }
        if (publication.getSourceReviewCount() == null
                || publication.getInputReviewCount() == null
                || publication.getSourceReviewCount() < 0
                || publication.getInputReviewCount() < 0
                || publication.getInputReviewCount() > publication.getSourceReviewCount()) {
            throw new IllegalArgumentException("review counts are invalid");
        }
        if (!StringUtils.hasText(publication.getSummary())
                || !StringUtils.hasText(publication.getModelName())
                || publication.getModelName().length() > 64) {
            throw new IllegalArgumentException("summary and modelName are required");
        }
    }

    private void validatePublicationLease(AiSummaryTask task,
                                          Long sourceVersion,
                                          String leaseOwner,
                                          LocalDateTime now) {
        if (task == null
                || !"PROCESSING".equals(task.getStatus())
                || !sourceVersion.equals(task.getProcessingVersion())
                || !leaseOwner.equals(task.getLeaseOwner())
                || task.getLeaseUntil() == null
                || !task.getLeaseUntil().isAfter(now)) {
            throw new IllegalStateException("AI summary lease is no longer valid");
        }
    }

    private void validateTransition(AiSummaryTargetType targetType,
                                    Long targetId,
                                    Long sourceVersion,
                                    String leaseOwner,
                                    LocalDateTime now) {
        validateTarget(targetType, targetId);
        if (sourceVersion == null || sourceVersion <= 0) {
            throw new IllegalArgumentException("sourceVersion must be positive");
        }
        validateLeaseOwner(leaseOwner);
        requireTime(now, "now");
    }

    private void validateTarget(AiSummaryTargetType targetType, Long targetId) {
        if (targetType == null || targetId == null || targetId <= 0) {
            throw new IllegalArgumentException("AI summary target is invalid");
        }
    }

    private void validateLease(String leaseOwner,
                               LocalDateTime now,
                               LocalDateTime leaseUntil) {
        validateLeaseOwner(leaseOwner);
        requireTime(now, "now");
        requireTime(leaseUntil, "leaseUntil");
        if (!leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("leaseUntil must be after now");
        }
    }

    private void validateLeaseOwner(String leaseOwner) {
        if (!StringUtils.hasText(leaseOwner) || leaseOwner.length() > 128) {
            throw new IllegalArgumentException("leaseOwner is invalid");
        }
    }

    private void validateErrorCode(String errorCode) {
        if (errorCode == null || !ERROR_CODE.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("errorCode must be a sanitized code");
        }
    }

    private void requireTime(LocalDateTime value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private void requireSingleRow(int affected, String operation) {
        if (affected != 1) {
            throw new IllegalStateException("Failed to " + operation + ", affectedRows=" + affected);
        }
    }

    private void requireAtMostOneRow(int affected, String operation) {
        if (affected < 0 || affected > 1) {
            throw new IllegalStateException("Failed to " + operation + ", affectedRows=" + affected);
        }
    }
}
