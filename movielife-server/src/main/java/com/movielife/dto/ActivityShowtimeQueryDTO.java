package com.movielife.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import javax.validation.constraints.Positive;
import java.time.LocalDate;

@Data
public class ActivityShowtimeQueryDTO {

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date;

    @Positive(message = "场馆ID必须为正整数")
    private Long venueId;
}
