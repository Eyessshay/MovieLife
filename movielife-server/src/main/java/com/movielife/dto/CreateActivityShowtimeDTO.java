package com.movielife.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CreateActivityShowtimeDTO {

    @NotBlank(message = "创建请求标识不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "创建请求标识格式不合法")
    private String requestId;

    @NotNull(message = "活动ID不能为空")
    @Positive(message = "活动ID必须为正整数")
    private Long activityId;

    @NotNull(message = "影厅ID不能为空")
    @Positive(message = "影厅ID必须为正整数")
    private Long hallId;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;

    @NotNull(message = "基础价格不能为空")
    @DecimalMin(value = "0.01", message = "基础价格必须大于0")
    @DecimalMax(value = "99999999.99", message = "基础价格超出范围")
    @Digits(integer = 8, fraction = 2, message = "基础价格最多保留两位小数")
    private BigDecimal basePrice;
}
