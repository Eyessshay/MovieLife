package com.movielife.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class PostVO {
    private Long id;
    private PostUserVO user;
    private String title;
    private String content;
    private List<String> images;
    private String relatedType;
    private Long relatedId;
    private Integer likeCount;
    private Integer commentCount;
    private Boolean liked;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
