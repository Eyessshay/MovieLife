package com.movielife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.movielife.entity.TicketOrderItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TicketOrderItemMapper extends BaseMapper<TicketOrderItem> {

    @Insert("<script>" +
            "INSERT INTO tb_ticket_order_item " +
            "(order_id, seat_id, price, ticket_code, ticket_status) VALUES " +
            "<foreach collection='items' item='item' separator=','>" +
            "(#{item.orderId}, #{item.seatId}, #{item.price}, #{item.ticketCode}, #{item.ticketStatus})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("items") List<TicketOrderItem> items);
}
