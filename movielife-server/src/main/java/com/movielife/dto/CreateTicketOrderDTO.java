package com.movielife.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;
import java.util.List;

@Data
public class CreateTicketOrderDTO {

    @NotNull(message = "场次ID不能为空")
    @Positive(message = "场次ID必须为正整数")
    private Long showtimeId;

    @NotEmpty(message = "订单座位不能为空")
    @Size(min = 1, max = 6, message = "单次必须选择1到6个座位")
    @Valid
    private List<@NotNull(message = "座位ID不能为空") @Positive(message = "座位ID必须为正整数") Long> seatIds;

    @NotBlank(message = "锁座凭证不能为空")
    @Size(max = 64, message = "锁座凭证长度不能超过64位")
    private String lockToken;
}
