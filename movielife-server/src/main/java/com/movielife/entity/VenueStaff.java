package com.movielife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_venue_staff")
public class VenueStaff {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long venueId;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
