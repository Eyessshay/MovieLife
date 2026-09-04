package com.movielife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("tb_ticket_order")
public class TicketOrder {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long showtimeId;
    private String lockToken;
    private BigDecimal totalAmount;
    private String status;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
}
