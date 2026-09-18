package com.movielife.ai;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiReviewSummaryCacheValue {

    private Long summaryId;
    private String targetType;
    private Long targetId;
    private String summary;
    private String pros;
    private String cons;
    private String suitableFor;
    private String warningPoints;
    private Integer sourceReviewCount;
    private Integer inputReviewCount;
    private Integer version;
    private String promptVersion;
    private LocalDateTime generatedAt;
}
