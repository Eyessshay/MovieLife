package com.movielife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("tb_activity")
public class Activity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String coverUrl;
    private String type;
    private String city;
    private String description;
    private BigDecimal priceMin;
    private BigDecimal priceMax;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
