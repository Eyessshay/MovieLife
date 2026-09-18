package com.movielife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_ai_review_summary")
public class AiReviewSummary {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String targetType;
    private Long targetId;
    private String promptVersion;
    private String sourceFingerprint;
    private Long sourceVersion;
    private String summary;
    private String pros;
    private String cons;
    private String suitableFor;
    private String warningPoints;
    private Integer sourceReviewCount;
    private Integer inputReviewCount;
    private String modelName;
    private Integer version;
    private LocalDateTime createTime;
}
