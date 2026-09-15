package com.movielife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.movielife.entity.Activity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface ActivityMapper extends BaseMapper<Activity> {

    @Update("UPDATE tb_activity " +
            "SET avg_score = ROUND((avg_score * score_count + #{score}) / (score_count + 1), 2), " +
            "score_count = score_count + 1 " +
            "WHERE id = #{activityId} AND status = 1")
    int addReviewScore(@Param("activityId") Long activityId, @Param("score") BigDecimal score);

    @Update("UPDATE tb_activity " +
            "SET avg_score = ROUND((avg_score * score_count - #{oldScore} + #{newScore}) / score_count, 2) " +
            "WHERE id = #{activityId} AND score_count > 0")
    int replaceReviewScore(@Param("activityId") Long activityId,
                           @Param("oldScore") BigDecimal oldScore,
                           @Param("newScore") BigDecimal newScore);

    @Update("UPDATE tb_activity " +
            "SET avg_score = CASE WHEN score_count = 1 THEN 0 " +
            "ELSE ROUND((avg_score * score_count - #{score}) / (score_count - 1), 2) END, " +
            "score_count = score_count - 1 " +
            "WHERE id = #{activityId} AND score_count > 0")
    int removeReviewScore(@Param("activityId") Long activityId, @Param("score") BigDecimal score);
}
