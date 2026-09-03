package com.movielife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("tb_seat")
public class Seat {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long hallId;
    private Integer rowNo;
    private Integer colNo;
    private String seatNo;
    private String seatType;
    private Integer baseStatus;
}
