package com.movielife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("tb_ticket_order_item")
public class TicketOrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long seatId;
    private BigDecimal price;
    private String ticketCode;
    private Integer ticketStatus;
}
