package com.movielife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.movielife.entity.Hall;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HallMapper extends BaseMapper<Hall> {

    @Select("SELECT * FROM tb_hall WHERE id = #{hallId} FOR UPDATE")
    Hall selectByIdForUpdate(@Param("hallId") Long hallId);
}
