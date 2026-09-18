package com.movielife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_ai_summary_task")
public class AiSummaryTask {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String targetType;
    private Long targetId;
    private Long dirtyVersion;
    private Long processedVersion;
    private Long processingVersion;
    private Integer lastSummaryVersion;
    private Long currentSummaryId;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextRetryTime;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private String lastErrorCode;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
