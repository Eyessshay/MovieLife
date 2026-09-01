package com.movielife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.movielife.entity.Post;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PostMapper extends BaseMapper<Post> {

    @Update("UPDATE tb_post SET liked = liked + 1 WHERE id = #{id}")
    int incrementLikeCount(@Param("id") Long id);

    @Update("UPDATE tb_post SET liked = liked - 1 WHERE id = #{id} AND liked > 0")
    int decrementLikeCount(@Param("id") Long id);
}
