package com.movielife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.movielife.entity.Movie;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface MovieMapper extends BaseMapper<Movie> {

    @Update("UPDATE tb_movie " +
            "SET avg_score = ROUND((avg_score * score_count + #{score}) / (score_count + 1), 2), " +
            "score_count = score_count + 1 " +
            "WHERE id = #{movieId}")
    int addReviewScore(@Param("movieId") Long movieId, @Param("score") BigDecimal score);

    @Update("UPDATE tb_movie " +
            "SET avg_score = ROUND((avg_score * score_count - #{oldScore} + #{newScore}) / score_count, 2) " +
            "WHERE id = #{movieId} AND score_count > 0")
    int replaceReviewScore(@Param("movieId") Long movieId,
                           @Param("oldScore") BigDecimal oldScore,
                           @Param("newScore") BigDecimal newScore);

    @Update("UPDATE tb_movie " +
            "SET avg_score = CASE WHEN score_count = 1 THEN 0 " +
            "ELSE ROUND((avg_score * score_count - #{score}) / (score_count - 1), 2) END, " +
            "score_count = score_count - 1 " +
            "WHERE id = #{movieId} AND score_count > 0")
    int removeReviewScore(@Param("movieId") Long movieId, @Param("score") BigDecimal score);
}
