package com.movielife.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PostSummaryVO {
    private Long id;
    private String title;
    private String content;
    private List<String> images;
    private Integer likeCount;
    private Integer commentCount;
}
