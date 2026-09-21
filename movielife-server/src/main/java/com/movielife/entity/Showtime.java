package com.movielife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("tb_showtime")
public class Showtime {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String bizType;
    private Long bizId;
    private Long venueId;
    private Long hallId;
    private String createRequestId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String languageVersion;
    private BigDecimal basePrice;
    private Integer totalSeats;
    private Integer availableSeats;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
