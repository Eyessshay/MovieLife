package com.movielife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("tb_hall")
public class Hall {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long venueId;
    private String name;
    private Long seatTemplateId;
}
