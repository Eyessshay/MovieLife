package com.movielife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.movielife.entity.TicketOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TicketOrderMapper extends BaseMapper<TicketOrder> {

    @Select("SELECT * FROM tb_ticket_order WHERE lock_token = #{lockToken}")
    TicketOrder selectByLockToken(@Param("lockToken") String lockToken);
}
