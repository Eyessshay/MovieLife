package com.movielife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.movielife.entity.TicketOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TicketOrderMapper extends BaseMapper<TicketOrder> {

    @Select("SELECT * FROM tb_ticket_order WHERE lock_token = #{lockToken}")
    TicketOrder selectByLockToken(@Param("lockToken") String lockToken);

    @Select("SELECT * FROM tb_ticket_order WHERE id = #{orderId} FOR UPDATE")
    TicketOrder selectByIdForUpdate(@Param("orderId") Long orderId);

    @Update("UPDATE tb_ticket_order SET status = 'CANCELED' " +
            "WHERE id = #{orderId} AND user_id = #{userId} AND status = 'UNPAID'")
    int cancelUnpaidOrder(@Param("orderId") Long orderId,
                          @Param("userId") Long userId);

    @Update("UPDATE tb_ticket_order SET status = 'CANCELED' " +
            "WHERE id = #{orderId} AND status = 'UNPAID' " +
            "AND expire_time <= #{now}")
    int cancelExpiredUnpaidOrder(@Param("orderId") Long orderId,
                                 @Param("now") LocalDateTime now);

    @Select("SELECT id FROM tb_ticket_order " +
            "WHERE status = 'UNPAID' AND expire_time <= #{now} " +
            "ORDER BY expire_time ASC, id ASC LIMIT #{limit}")
    List<Long> selectExpiredUnpaidOrderIds(@Param("now") LocalDateTime now,
                                           @Param("limit") Integer limit);

    @Update("UPDATE tb_ticket_order SET status = 'PAID', pay_time = #{payTime} " +
            "WHERE id = #{orderId} AND status = 'UNPAID' " +
            "AND expire_time > #{payTime}")
    int payUnpaidOrder(@Param("orderId") Long orderId,
                       @Param("payTime") LocalDateTime payTime);
}
