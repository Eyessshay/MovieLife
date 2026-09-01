package com.movielife.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ReviewVO {

    private Long id;
    private ReviewUserVO user;
    private String targetType;
    private Long targetId;
    private BigDecimal score;
    private String content;
    private List<String> images;
    private Integer likeCount;
    private Integer commentCount;
    private Boolean liked;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
