package com.movielife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.movielife.entity.Review;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ReviewMapper extends BaseMapper<Review> {

    @Select("SELECT COUNT(*) FROM tb_review WHERE target_type = #{targetType} " +
            "AND target_id = #{targetId} AND status = 1")
    int countActiveByTarget(@Param("targetType") String targetType,
                            @Param("targetId") Long targetId);

    @Select("SELECT id, score, content FROM tb_review WHERE target_type = #{targetType} " +
            "AND target_id = #{targetId} AND status = 1 " +
            "ORDER BY update_time DESC, id DESC LIMIT #{limit}")
    List<Review> selectActiveSummarySource(@Param("targetType") String targetType,
                                           @Param("targetId") Long targetId,
                                           @Param("limit") int limit);

    @Select("SELECT * FROM tb_review WHERE user_id = #{userId} " +
            "AND target_type = #{targetType} AND target_id = #{targetId} FOR UPDATE")
    Review selectByUserAndTargetForUpdate(@Param("userId") Long userId,
                                          @Param("targetType") String targetType,
                                          @Param("targetId") Long targetId);

    @Select("SELECT * FROM tb_review WHERE id = #{id} FOR UPDATE")
    Review selectByIdForUpdate(@Param("id") Long id);

    @Update("UPDATE tb_review SET score = #{score}, content = #{content}, images = #{images}, " +
            "status = 1, like_count = 0, create_time = #{now}, update_time = #{now} " +
            "WHERE id = #{id} AND status = 0")
    int restoreReview(@Param("id") Long id,
                      @Param("score") java.math.BigDecimal score,
                      @Param("content") String content,
                      @Param("images") String images,
                      @Param("now") java.time.LocalDateTime now);

    @Update("UPDATE tb_review SET score = #{score}, content = #{content}, images = #{images}, " +
            "update_time = #{now} WHERE id = #{id} AND status = 1")
    int updateReview(@Param("id") Long id,
                     @Param("score") java.math.BigDecimal score,
                     @Param("content") String content,
                     @Param("images") String images,
                     @Param("now") java.time.LocalDateTime now);

    @Update("UPDATE tb_review SET status = 0, like_count = 0, update_time = #{now} " +
            "WHERE id = #{id} AND status = 1")
    int softDelete(@Param("id") Long id, @Param("now") java.time.LocalDateTime now);

    @Update("UPDATE tb_review SET like_count = like_count + 1 WHERE id = #{id} AND status = 1")
    int incrementLikeCount(@Param("id") Long id);

    @Update("UPDATE tb_review SET like_count = like_count - 1 " +
            "WHERE id = #{id} AND status = 1 AND like_count > 0")
    int decrementLikeCount(@Param("id") Long id);
}
