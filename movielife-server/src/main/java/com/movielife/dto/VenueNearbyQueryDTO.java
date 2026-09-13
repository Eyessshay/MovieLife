package com.movielife.dto;

import com.movielife.enums.VenueType;
import lombok.Data;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class VenueNearbyQueryDTO {

    @NotBlank(message = "城市不能为空")
    private String city;

    @NotNull(message = "场馆类型不能为空")
    private VenueType type;

    @NotNull(message = "经度不能为空")
    @DecimalMin(value = "-180.0", message = "经度必须在-180到180之间")
    @DecimalMax(value = "180.0", message = "经度必须在-180到180之间")
    private Double x;

    @NotNull(message = "纬度不能为空")
    @DecimalMin(value = "-90.0", message = "纬度必须在-90到90之间")
    @DecimalMax(value = "90.0", message = "纬度必须在-90到90之间")
    private Double y;

    @Min(value = 100, message = "查询距离必须在100到50000米之间")
    @Max(value = 50000, message = "查询距离必须在100到50000米之间")
    private Integer distance = 5000;

    @Min(value = 1, message = "页码必须大于等于1")
    private Integer page = 1;

    @Min(value = 1, message = "每页数量必须大于等于1")
    @Max(value = 100, message = "每页数量不能超过100")
    private Integer pageSize = 10;

    private String bizType;
    private Long bizId;

    @AssertTrue(message = "bizType和bizId必须同时为空或同时有值")
    public boolean isBizFilterPairValid() {
        return hasText(bizType) == (bizId != null);
    }

    @AssertTrue(message = "当前仅支持MOVIE或ACTIVITY业务类型")
    public boolean isBizTypeValid() {
        return !hasText(bizType)
                || "MOVIE".equals(bizType)
                || "ACTIVITY".equals(bizType);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
