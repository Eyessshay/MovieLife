package com.movielife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.movielife.entity.ShowtimeSeat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ShowtimeSeatMapper extends BaseMapper<ShowtimeSeat> {

    @Update("<script>" +
            "UPDATE tb_showtime_seat " +
            "SET status = 1, lock_order_id = #{orderId}, version = version + 1 " +
            "WHERE showtime_id = #{showtimeId} " +
            "AND seat_id IN " +
            "<foreach collection='seatIds' item='seatId' open='(' separator=',' close=')'>" +
            "#{seatId}" +
            "</foreach> " +
            "AND status = 0 " +
            "AND lock_order_id IS NULL" +
            "</script>")
    int holdSeatsForOrder(@Param("showtimeId") Long showtimeId,
                          @Param("seatIds") List<Long> seatIds,
                          @Param("orderId") Long orderId);
}
