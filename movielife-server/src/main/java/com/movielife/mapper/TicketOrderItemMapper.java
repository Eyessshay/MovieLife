package com.movielife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.movielife.entity.TicketOrderItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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

    @Update("<script>UPDATE tb_ticket_order_item SET ticket_code = CASE id " +
            "<foreach collection='items' item='item'>" +
            "WHEN #{item.id} THEN #{item.ticketCode} " +
            "</foreach> END, ticket_status = #{issuedStatus} " +
            "WHERE order_id = #{orderId} AND ticket_code IS NULL " +
            "AND ticket_status = #{pendingStatus} AND id IN " +
            "<foreach collection='items' item='item' open='(' separator=',' close=')'>" +
            "#{item.id}</foreach></script>")
    int issueBatch(@Param("orderId") Long orderId,
                   @Param("items") List<TicketOrderItem> items,
                   @Param("pendingStatus") int pendingStatus,
                   @Param("issuedStatus") int issuedStatus);

    @Update("<script>UPDATE tb_ticket_order_item SET ticket_status = #{refundedStatus} " +
            "WHERE order_id = #{orderId} AND ticket_status IN " +
            "<foreach collection='refundableStatuses' item='status' open='(' separator=',' close=')'>" +
            "#{status}</foreach> AND id IN " +
            "<foreach collection='itemIds' item='itemId' open='(' separator=',' close=')'>" +
            "#{itemId}</foreach></script>")
    int markRefundedByOrder(@Param("orderId") Long orderId,
                            @Param("itemIds") List<Long> itemIds,
                            @Param("refundableStatuses") List<Integer> refundableStatuses,
                            @Param("refundedStatus") int refundedStatus);
}
