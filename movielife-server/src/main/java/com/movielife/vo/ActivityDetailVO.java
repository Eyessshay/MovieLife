package com.movielife.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ActivityDetailVO {

    private Long id;
    private String title;
    private String coverUrl;
    private String type;
    private String city;
    private String description;
    private BigDecimal priceMin;
    private BigDecimal priceMax;
}
