package com.movielife.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ReviewLikeVO {

    private Boolean liked;
    private Integer likeCount;
}
