package com.movielife.vo;

import com.movielife.enums.VenueType;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class VenueDetailVO {

    private Long id;
    private String name;
    private VenueType type;
    private String city;
    private String address;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private String phone;
    private String openTime;
    private BigDecimal avgScore;
}
