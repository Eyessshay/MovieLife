package com.movielife.vo;

import com.movielife.enums.VenueType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ActivityNearbyVO {

    private Long id;
    private String title;
    private String coverUrl;
    private String type;
    private BigDecimal priceMin;
    private BigDecimal priceMax;
    private Long nearestVenueId;
    private String nearestVenueName;
    private VenueType nearestVenueType;
    private String nearestVenueAddress;
    private String venueCity;
    private Double distance;
    private Long nextShowtimeId;
    private LocalDateTime nextStartTime;
    private Integer nextAvailableSeats;
    private Boolean collected;
}
