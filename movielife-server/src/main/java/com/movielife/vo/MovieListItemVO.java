package com.movielife.vo;

import com.movielife.constants.MovieStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class MovieListItemVO {

    private Long id;
    private String title;
    private String coverUrl;
    private String category;
    private LocalDate releaseDate;
    private BigDecimal avgScore;
    private Integer scoreCount;
    private MovieStatus status;
    private Boolean collected;
}
