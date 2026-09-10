package com.movielife.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ActivityListItemVO {

    private Long id;
    private String title;
    private String coverUrl;
    private String type;
    private String city;
    private BigDecimal priceMin;
    private BigDecimal priceMax;
}
