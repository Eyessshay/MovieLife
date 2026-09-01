package com.movielife.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FollowUserVO {
    private Long id;
    private String nickName;
    private String icon;
    private String city;
}
