package com.movielife.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateReviewDTO {

    @NotBlank(message = "评价目标类型不能为空")
    private String targetType;

    @NotNull(message = "评价目标不能为空")
    @Positive(message = "评价目标ID必须为正整数")
    private Long targetId;

    @NotNull(message = "评分不能为空")
    private BigDecimal score;

    @NotBlank(message = "评价内容不能为空")
    @Size(max = 2000, message = "评价内容不能超过2000字")
    private String content;

    private List<String> images;
}
