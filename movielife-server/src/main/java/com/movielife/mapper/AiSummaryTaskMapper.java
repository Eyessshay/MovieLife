package com.movielife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.movielife.entity.AiSummaryTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiSummaryTaskMapper extends BaseMapper<AiSummaryTask> {

    @Select("SELECT target_type, target_id FROM tb_ai_summary_task "
            + "WHERE target_type IN ('MOVIE', 'ACTIVITY') AND dirty_version > processed_version AND ("
            + "status = 'PENDING' "
            + "OR (status = 'RETRY_WAIT' AND next_retry_time <= #{now}) "
            + "OR (status = 'PROCESSING' AND lease_until <= #{now})) "
            + "ORDER BY update_time ASC, id ASC LIMIT #{limit}")
    List<AiSummaryTask> selectRecoverableTargets(@Param("now") LocalDateTime now,
                                                 @Param("limit") int limit);

    @Insert("INSERT INTO tb_ai_summary_task "
            + "(target_type, target_id, dirty_version, processed_version, status, create_time, update_time) "
            + "VALUES (#{targetType}, #{targetId}, 1, 0, 'PENDING', #{now}, #{now}) "
            + "ON DUPLICATE KEY UPDATE "
            + "dirty_version = dirty_version + 1, "
            + "next_retry_time = IF(status = 'PROCESSING', next_retry_time, NULL), "
            + "last_error_code = IF(status = 'PROCESSING', last_error_code, NULL), "
            + "attempt_count = IF(status = 'PROCESSING', attempt_count, 0), "
            + "status = IF(status = 'PROCESSING', 'PROCESSING', 'PENDING'), "
            + "update_time = #{now}")
    int markDirty(@Param("targetType") String targetType,
                  @Param("targetId") Long targetId,
                  @Param("now") LocalDateTime now);

    @Select("SELECT * FROM tb_ai_summary_task "
            + "WHERE target_type = #{targetType} AND target_id = #{targetId}")
    AiSummaryTask selectByTarget(@Param("targetType") String targetType,
                                 @Param("targetId") Long targetId);

    @Select("SELECT * FROM tb_ai_summary_task "
            + "WHERE target_type = #{targetType} AND target_id = #{targetId} FOR UPDATE")
    AiSummaryTask selectByTargetForUpdate(@Param("targetType") String targetType,
                                          @Param("targetId") Long targetId);

    @Update("UPDATE tb_ai_summary_task SET "
            + "status = 'PROCESSING', processing_version = dirty_version, "
            + "attempt_count = attempt_count + 1, next_retry_time = NULL, "
            + "lease_owner = #{leaseOwner}, lease_until = #{leaseUntil}, "
            + "last_error_code = NULL, update_time = #{now} "
            + "WHERE target_type = #{targetType} AND target_id = #{targetId} "
            + "AND dirty_version > processed_version AND ("
            + "status = 'PENDING' "
            + "OR (status = 'RETRY_WAIT' AND next_retry_time <= #{now}) "
            + "OR (status = 'PROCESSING' AND lease_until <= #{now}))")
    int claim(@Param("targetType") String targetType,
              @Param("targetId") Long targetId,
              @Param("leaseOwner") String leaseOwner,
              @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("UPDATE tb_ai_summary_task SET lease_until = #{leaseUntil}, update_time = #{now} "
            + "WHERE target_type = #{targetType} AND target_id = #{targetId} "
            + "AND status = 'PROCESSING' AND lease_owner = #{leaseOwner} "
            + "AND lease_until > #{now}")
    int renewLease(@Param("targetType") String targetType,
                   @Param("targetId") Long targetId,
                   @Param("leaseOwner") String leaseOwner,
                   @Param("now") LocalDateTime now,
                   @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("UPDATE tb_ai_summary_task SET "
            + "status = IF(dirty_version > #{sourceVersion}, 'PENDING', 'RETRY_WAIT'), "
            + "processing_version = NULL, "
            + "attempt_count = IF(dirty_version > #{sourceVersion}, 0, attempt_count), "
            + "next_retry_time = IF(dirty_version > #{sourceVersion}, NULL, #{nextRetryTime}), "
            + "lease_owner = NULL, lease_until = NULL, "
            + "last_error_code = IF(dirty_version > #{sourceVersion}, NULL, #{errorCode}), "
            + "update_time = #{now} "
            + "WHERE target_type = #{targetType} AND target_id = #{targetId} "
            + "AND status = 'PROCESSING' AND processing_version = #{sourceVersion} "
            + "AND lease_owner = #{leaseOwner} AND lease_until > #{now}")
    int markRetry(@Param("targetType") String targetType,
                  @Param("targetId") Long targetId,
                  @Param("sourceVersion") Long sourceVersion,
                  @Param("leaseOwner") String leaseOwner,
                  @Param("errorCode") String errorCode,
                  @Param("now") LocalDateTime now,
                  @Param("nextRetryTime") LocalDateTime nextRetryTime);

    @Update("UPDATE tb_ai_summary_task SET "
            + "status = IF(dirty_version > #{sourceVersion}, 'PENDING', 'DEAD'), "
            + "processing_version = NULL, attempt_count = IF(dirty_version > #{sourceVersion}, 0, attempt_count), "
            + "next_retry_time = NULL, lease_owner = NULL, lease_until = NULL, "
            + "last_error_code = IF(dirty_version > #{sourceVersion}, NULL, #{errorCode}), "
            + "update_time = #{now} "
            + "WHERE target_type = #{targetType} AND target_id = #{targetId} "
            + "AND status = 'PROCESSING' AND processing_version = #{sourceVersion} "
            + "AND lease_owner = #{leaseOwner} AND lease_until > #{now}")
    int markDead(@Param("targetType") String targetType,
                 @Param("targetId") Long targetId,
                 @Param("sourceVersion") Long sourceVersion,
                 @Param("leaseOwner") String leaseOwner,
                 @Param("errorCode") String errorCode,
                 @Param("now") LocalDateTime now);

    @Update("UPDATE tb_ai_summary_task SET processed_version = #{sourceVersion}, "
            + "status = IF(dirty_version > #{sourceVersion}, 'PENDING', 'INSUFFICIENT_REVIEWS'), "
            + "processing_version = NULL, attempt_count = 0, next_retry_time = NULL, "
            + "lease_owner = NULL, lease_until = NULL, last_error_code = NULL, "
            + "update_time = #{now} "
            + "WHERE target_type = #{targetType} AND target_id = #{targetId} "
            + "AND status = 'PROCESSING' AND processing_version = #{sourceVersion} "
            + "AND lease_owner = #{leaseOwner} AND lease_until > #{now}")
    int markInsufficient(@Param("targetType") String targetType,
                         @Param("targetId") Long targetId,
                         @Param("sourceVersion") Long sourceVersion,
                         @Param("leaseOwner") String leaseOwner,
                         @Param("now") LocalDateTime now);

    @Update("UPDATE tb_ai_summary_task SET processed_version = #{sourceVersion}, "
            + "last_summary_version = #{lastSummaryVersion}, "
            + "current_summary_id = #{summaryId}, "
            + "status = IF(dirty_version > #{sourceVersion}, 'PENDING', 'SUCCEEDED'), "
            + "processing_version = NULL, attempt_count = 0, next_retry_time = NULL, "
            + "lease_owner = NULL, lease_until = NULL, last_error_code = NULL, "
            + "update_time = #{now} "
            + "WHERE id = #{taskId} AND status = 'PROCESSING' "
            + "AND processing_version = #{sourceVersion} "
            + "AND lease_owner = #{leaseOwner} AND lease_until > #{now}")
    int publish(@Param("taskId") Long taskId,
                @Param("sourceVersion") Long sourceVersion,
                @Param("leaseOwner") String leaseOwner,
                @Param("summaryId") Long summaryId,
                @Param("lastSummaryVersion") Integer lastSummaryVersion,
                @Param("now") LocalDateTime now);
}
