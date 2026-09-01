package com.movielife.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

@Data
public class CollectionCheckDTO {

    @NotBlank(message = "收藏目标类型不能为空")
    private String targetType;

    @NotNull(message = "收藏目标不能为空")
    @Positive(message = "收藏目标ID必须为正整数")
    private Long targetId;
}
