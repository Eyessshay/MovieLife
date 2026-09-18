package com.movielife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.movielife.entity.AiReviewSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiReviewSummaryMapper extends BaseMapper<AiReviewSummary> {

    @Select("SELECT * FROM tb_ai_review_summary "
            + "WHERE target_type = #{targetType} AND target_id = #{targetId} "
            + "AND prompt_version = #{promptVersion} "
            + "AND source_fingerprint = #{sourceFingerprint}")
    AiReviewSummary selectByInput(@Param("targetType") String targetType,
                                  @Param("targetId") Long targetId,
                                  @Param("promptVersion") String promptVersion,
                                  @Param("sourceFingerprint") String sourceFingerprint);

    @Select("SELECT summary.* FROM tb_ai_summary_task task "
            + "JOIN tb_ai_review_summary summary ON summary.id = task.current_summary_id "
            + "AND summary.target_type = task.target_type AND summary.target_id = task.target_id "
            + "WHERE task.target_type = #{targetType} AND task.target_id = #{targetId}")
    AiReviewSummary selectCurrent(@Param("targetType") String targetType,
                                  @Param("targetId") Long targetId);
}
