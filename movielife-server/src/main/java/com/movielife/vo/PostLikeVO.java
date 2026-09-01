package com.movielife.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PostLikeVO {
    private Boolean liked;
    private Integer likeCount;
}
