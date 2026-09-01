package com.movielife.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Positive;

@Data
public class PostQueryDTO {

    @Positive(message = "用户ID必须为正整数")
    private Long userId;
    private String relatedType;
    @Positive(message = "关联目标ID必须为正整数")
    private Long relatedId;

    @Min(value = 1, message = "页码必须大于等于1")
    private Integer page = 1;

    @Min(value = 1, message = "每页数量必须大于等于1")
    @Max(value = 100, message = "每页数量不能超过100")
    private Integer pageSize = 20;
}
