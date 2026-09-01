package com.movielife.vo;

import lombok.Data;

import java.time.LocalDate;

@Data
public class UserMeVO {

    private Long id;
    private String nickName;
    private String icon;
    private Integer gender;
    private String city;
    private LocalDate birthday;
}
