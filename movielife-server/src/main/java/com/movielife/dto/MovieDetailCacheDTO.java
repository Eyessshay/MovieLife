package com.movielife.dto;

import com.movielife.constants.MovieStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class MovieDetailCacheDTO {

    private Long id;
    private String title;
    private String originalTitle;
    private String coverUrl;
    private String category;
    private String region;
    private String language;
    private LocalDate releaseDate;
    private Integer duration;
    private String director;
    private List<String> actors;
    private String summary;
    private BigDecimal avgScore;
    private Integer scoreCount;
    private MovieStatus status;
}
