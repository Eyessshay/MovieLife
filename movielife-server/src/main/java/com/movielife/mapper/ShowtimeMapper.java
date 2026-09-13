package com.movielife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.movielife.entity.Showtime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ShowtimeMapper extends BaseMapper<Showtime> {

    @Select("<script>" +
            "SELECT DISTINCT venue_id FROM tb_showtime " +
            "WHERE biz_type = 'MOVIE' " +
            "AND biz_id = #{movieId} " +
            "AND venue_id IN " +
            "<foreach collection='venueIds' item='venueId' open='(' separator=',' close=')'>" +
            "#{venueId}" +
            "</foreach> " +
            "AND start_time &gt; NOW() " +
            "AND status = 1" +
            "</script>")
    List<Long> selectVenueIdsWithFutureMovieShowtimes(
            @Param("movieId") Long movieId,
            @Param("venueIds") List<Long> venueIds
    );

    @Select("<script>" +
            "SELECT DISTINCT venue_id FROM tb_showtime " +
            "WHERE biz_type = 'ACTIVITY' " +
            "AND biz_id = #{activityId} " +
            "AND venue_id IN " +
            "<foreach collection='venueIds' item='venueId' open='(' separator=',' close=')'>" +
            "#{venueId}" +
            "</foreach> " +
            "AND start_time &gt; NOW() " +
            "AND status = 1" +
            "</script>")
    List<Long> selectVenueIdsWithFutureActivityShowtimes(
            @Param("activityId") Long activityId,
            @Param("venueIds") List<Long> venueIds
    );

    @Select("<script>" +
            "SELECT id, biz_type, biz_id, venue_id, hall_id, start_time, end_time, " +
            "language_version, base_price, total_seats, available_seats, status, " +
            "create_time, update_time FROM tb_showtime " +
            "WHERE biz_type = 'ACTIVITY' " +
            "AND venue_id IN " +
            "<foreach collection='venueIds' item='venueId' open='(' separator=',' close=')'>" +
            "#{venueId}" +
            "</foreach> " +
            "AND start_time &gt; NOW() " +
            "AND status = 1 " +
            "ORDER BY venue_id ASC, start_time ASC, id ASC" +
            "</script>")
    List<Showtime> selectFutureActivityShowtimesByVenueIds(
            @Param("venueIds") List<Long> venueIds
    );

    @Update("UPDATE tb_showtime " +
            "SET available_seats = available_seats - #{seatCount} " +
            "WHERE id = #{showtimeId} " +
            "AND available_seats >= #{seatCount}")
    int decreaseAvailableSeats(@Param("showtimeId") Long showtimeId,
                               @Param("seatCount") Integer seatCount);

    @Update("UPDATE tb_showtime " +
            "SET available_seats = available_seats + #{seatCount} " +
            "WHERE id = #{showtimeId} " +
            "AND available_seats + #{seatCount} <= total_seats")
    int increaseAvailableSeats(@Param("showtimeId") Long showtimeId,
                               @Param("seatCount") Integer seatCount);
}
