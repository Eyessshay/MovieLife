package com.movielife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("tb_movie")
public class Movie {

    @TableId(type = IdType.AUTO)
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
    private String actors;
    private String summary;
    private BigDecimal avgScore;
    private Integer scoreCount;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
