package com.movielife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.movielife.entity.Collection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CollectionMapper extends BaseMapper<Collection> {

    @Select("SELECT c.id, c.user_id, c.target_type, c.target_id, c.create_time " +
            "FROM tb_collection c INNER JOIN tb_activity a ON a.id = c.target_id " +
            "WHERE c.user_id = #{userId} AND c.target_type = 'ACTIVITY' AND a.status = 1 " +
            "ORDER BY c.create_time DESC, c.id DESC")
    Page<Collection> selectVisibleActivityPage(Page<Collection> page,
                                                @Param("userId") Long userId);
}
