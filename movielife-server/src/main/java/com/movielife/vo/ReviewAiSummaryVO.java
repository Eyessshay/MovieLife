package com.movielife.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ReviewAiSummaryVO {

    private String targetType;
    private Long targetId;
    private String status;
    private String summary;
    private List<String> pros;
    private List<String> cons;
    private List<String> suitableFor;
    private List<String> warningPoints;
    private Integer sourceReviewCount;
    private Integer inputReviewCount;
    private Integer version;
    private String promptVersion;
    private LocalDateTime generatedAt;
    private Boolean stale;
}
