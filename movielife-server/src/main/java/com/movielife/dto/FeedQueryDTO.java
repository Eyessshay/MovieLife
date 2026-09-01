package com.movielife.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Positive;

@Data
public class FeedQueryDTO {

    @Positive(message = "时间游标必须为正整数")
    private Long maxTime;

    @Min(value = 0, message = "滚动偏移量不能小于0")
    private Integer offset = 0;

    @Min(value = 1, message = "每页数量必须大于等于1")
    @Max(value = 50, message = "每页数量不能超过50")
    private Integer pageSize = 10;
}
