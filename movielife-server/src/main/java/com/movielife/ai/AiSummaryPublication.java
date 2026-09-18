package com.movielife.ai;

import com.movielife.enums.AiSummaryTargetType;
import lombok.Data;

@Data
public class AiSummaryPublication {

    private AiSummaryTargetType targetType;
    private Long targetId;
    private Long sourceVersion;
    private String promptVersion;
    private String sourceFingerprint;
    private Integer sourceReviewCount;
    private Integer inputReviewCount;
    private String summary;
    private String pros;
    private String cons;
    private String suitableFor;
    private String warningPoints;
    private String modelName;
}
