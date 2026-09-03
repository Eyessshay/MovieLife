package com.movielife.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SeatVO {

    private Long seatId;
    private Integer rowNo;
    private Integer colNo;
    private String seatNo;
    private String seatType;
    private Integer status;
    private BigDecimal price;
}
