package com.movielife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.movielife.entity.TicketOrderItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TicketOrderItemMapper extends BaseMapper<TicketOrderItem> {

    @Select("SELECT * FROM tb_ticket_order_item WHERE ticket_code = #{ticketCode}")
    TicketOrderItem selectByTicketCode(@Param("ticketCode") String ticketCode);

    @Select("SELECT * FROM tb_ticket_order_item " +
            "WHERE id = #{itemId} AND order_id = #{orderId} FOR UPDATE")
    TicketOrderItem selectByIdAndOrderIdForUpdate(@Param("itemId") Long itemId,
                                                   @Param("orderId") Long orderId);

    @Select("SELECT * FROM tb_ticket_order_item " +
            "WHERE order_id = #{orderId} ORDER BY id ASC FOR UPDATE")
    List<TicketOrderItem> selectByOrderIdForUpdate(@Param("orderId") Long orderId);

    @Update("UPDATE tb_ticket_order_item SET ticket_status = #{usedStatus}, " +
            "used_time = #{usedTime}, used_by_user_id = #{usedByUserId} " +
            "WHERE id = #{itemId} AND order_id = #{orderId} " +
            "AND ticket_status = #{validStatus} " +
            "AND used_time IS NULL AND used_by_user_id IS NULL")
    int markUsed(@Param("itemId") Long itemId,
                 @Param("orderId") Long orderId,
                 @Param("validStatus") int validStatus,
                 @Param("usedStatus") int usedStatus,
                 @Param("usedTime") LocalDateTime usedTime,
                 @Param("usedByUserId") Long usedByUserId);

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
