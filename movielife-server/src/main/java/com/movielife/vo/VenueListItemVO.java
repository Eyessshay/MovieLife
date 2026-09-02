package com.movielife.vo;

import com.movielife.enums.VenueType;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class VenueListItemVO {

    private Long id;
    private String name;
    private VenueType type;
    private String city;
    private String address;
    private BigDecimal avgScore;
    private String openTime;
}
