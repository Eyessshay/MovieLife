package com.movielife.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;

@Data
public class ReviewQueryDTO {

    @NotBlank(message = "评价目标类型不能为空")
    private String targetType;

    @NotNull(message = "评价目标不能为空")
    @Positive(message = "评价目标ID必须为正整数")
    private Long targetId;

    @Min(value = 1, message = "页码必须大于等于1")
    private Integer page = 1;

    @Min(value = 1, message = "每页数量必须大于等于1")
    @Max(value = 100, message = "每页数量不能超过100")
    private Integer pageSize = 20;

    @Pattern(regexp = "latest|likeCount|score", message = "评价排序方式不合法")
    private String sortBy = "latest";
}
