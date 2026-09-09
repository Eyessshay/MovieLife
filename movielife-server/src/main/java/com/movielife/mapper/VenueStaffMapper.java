package com.movielife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.movielife.entity.VenueStaff;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface VenueStaffMapper extends BaseMapper<VenueStaff> {

    @Select("SELECT id FROM tb_venue_staff " +
            "WHERE user_id = #{userId} AND venue_id = #{venueId} AND status = 1 " +
            "LIMIT 1 FOR SHARE")
    Long selectActiveIdForShare(@Param("userId") Long userId,
                                @Param("venueId") Long venueId);
}
