package com.movielife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("tb_venue")
public class Venue {

    @TableId(type = IdType.AUTO)
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
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
