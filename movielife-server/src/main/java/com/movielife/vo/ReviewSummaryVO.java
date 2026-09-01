package com.movielife.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@AllArgsConstructor
public class ReviewSummaryVO {
    private Long id;
    private BigDecimal score;
    private String content;
    private List<String> images;
    private Integer likeCount;
}
