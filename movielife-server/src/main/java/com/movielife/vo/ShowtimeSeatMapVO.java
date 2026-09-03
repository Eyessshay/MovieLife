package com.movielife.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ShowtimeSeatMapVO {

    private Long showtimeId;
    private Long hallId;
    private String hallName;
    private BigDecimal basePrice;
    private List<SeatVO> seats;
}
