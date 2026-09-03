package com.movielife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("tb_showtime_seat")
public class ShowtimeSeat {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long showtimeId;
    private Long seatId;
    private Integer status;
    private BigDecimal price;
    private Long lockOrderId;
    private Integer version;
}
