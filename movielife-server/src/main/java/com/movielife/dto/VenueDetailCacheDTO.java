package com.movielife.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class VenueDetailCacheDTO {

    private Long id;
    private String name;
    private String type;
    private String city;
    private String address;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private String phone;
    private String openTime;
    private BigDecimal avgScore;
}
