package com.movielife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("tb_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String phone;
    private String nickName;
    private String icon;
    private Integer gender;
    private String city;
    private LocalDate birthday;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
