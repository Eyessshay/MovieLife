package com.movielife.dto;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.util.List;

@Data
public class SeatLockRequest {

    @NotEmpty(message = "锁座座位不能为空")
    @Size(max = 6, message = "单次最多锁定6个座位")
    private List<Long> seatIds;
}
