package com.movielife.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class SeatReleaseRequest {

    @NotBlank(message = "锁座凭证不能为空")
    private String lockToken;
}
