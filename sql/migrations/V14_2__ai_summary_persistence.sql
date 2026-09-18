-- Step 14.2: generic Review AI Summary persistence and task foundation
-- Target: MySQL 8.0.16+
-- This migration is intentionally repeatable for an existing MovieLife database.

USE movielife;

DROP PROCEDURE IF EXISTS migrate_step_14_2;

DELIMITER //

CREATE PROCEDURE migrate_step_14_2()
BEGIN
  IF EXISTS (SELECT 1 FROM tb_ai_review_summary LIMIT 1)
     AND EXISTS (
       SELECT 1 FROM information_schema.COLUMNS
       WHERE TABLE_SCHEMA = DATABASE()
         AND TABLE_NAME = 'tb_ai_review_summary'
         AND COLUMN_NAME = 'source_fingerprint'
     ) = 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Step 14.2 migration blocked: legacy AI summaries cannot be fingerprinted safely';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_ai_review_summary'
      AND COLUMN_NAME = 'prompt_version'
  ) THEN
    ALTER TABLE tb_ai_review_summary
      ADD COLUMN prompt_version VARCHAR(64) NOT NULL AFTER target_id;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_ai_review_summary'
      AND COLUMN_NAME = 'source_fingerprint'
  ) THEN
    ALTER TABLE tb_ai_review_summary
      ADD COLUMN source_fingerprint CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL AFTER prompt_version;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_ai_review_summary'
      AND COLUMN_NAME = 'source_version'
  ) THEN
    ALTER TABLE tb_ai_review_summary
      ADD COLUMN source_version BIGINT NOT NULL AFTER source_fingerprint;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_ai_review_summary'
      AND COLUMN_NAME = 'input_review_count'
  ) THEN
    ALTER TABLE tb_ai_review_summary
      ADD COLUMN input_review_count INT NOT NULL DEFAULT 0 AFTER source_review_count;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_ai_review_summary'
      AND INDEX_NAME = 'uk_ai_summary_target_input'
  ) THEN
    ALTER TABLE tb_ai_review_summary
      ADD UNIQUE KEY uk_ai_summary_target_input
        (target_type, target_id, prompt_version, source_fingerprint);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_ai_review_summary'
      AND CONSTRAINT_NAME = 'chk_ai_summary_target_type'
      AND CONSTRAINT_TYPE = 'CHECK'
  ) THEN
    ALTER TABLE tb_ai_review_summary
      ADD CONSTRAINT chk_ai_summary_target_type
        CHECK (target_type IN ('MOVIE', 'ACTIVITY'));
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_ai_review_summary'
      AND CONSTRAINT_NAME = 'chk_ai_summary_versions'
      AND CONSTRAINT_TYPE = 'CHECK'
  ) THEN
    ALTER TABLE tb_ai_review_summary
      ADD CONSTRAINT chk_ai_summary_versions CHECK (source_version > 0 AND version > 0);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_ai_review_summary'
      AND CONSTRAINT_NAME = 'chk_ai_summary_counts'
      AND CONSTRAINT_TYPE = 'CHECK'
  ) THEN
    ALTER TABLE tb_ai_review_summary
      ADD CONSTRAINT chk_ai_summary_counts CHECK (
        source_review_count >= 0
        AND input_review_count >= 0
        AND input_review_count <= source_review_count
      );
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_ai_review_summary'
      AND CONSTRAINT_NAME = 'chk_ai_summary_fingerprint'
      AND CONSTRAINT_TYPE = 'CHECK'
  ) THEN
    ALTER TABLE tb_ai_review_summary
      ADD CONSTRAINT chk_ai_summary_fingerprint
        CHECK (CHAR_LENGTH(source_fingerprint) = 64);
  END IF;
END//

DELIMITER ;

CALL migrate_step_14_2();
DROP PROCEDURE migrate_step_14_2;

CREATE TABLE IF NOT EXISTS tb_ai_summary_task (
  id BIGINT NOT NULL AUTO_INCREMENT,
  target_type VARCHAR(16) NOT NULL,
  target_id BIGINT NOT NULL,
  dirty_version BIGINT NOT NULL DEFAULT 0,
  processed_version BIGINT NOT NULL DEFAULT 0,
  processing_version BIGINT NULL,
  last_summary_version INT NOT NULL DEFAULT 0,
  current_summary_id BIGINT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  attempt_count INT NOT NULL DEFAULT 0,
  next_retry_time DATETIME(3) NULL,
  lease_owner VARCHAR(128) NULL,
  lease_until DATETIME(3) NULL,
  last_error_code VARCHAR(64) NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_task_target (target_type, target_id),
  KEY idx_ai_task_dispatch (status, next_retry_time, lease_until, update_time),
  KEY idx_ai_task_current_summary (current_summary_id),
  CONSTRAINT fk_ai_task_current_summary FOREIGN KEY (current_summary_id)
    REFERENCES tb_ai_review_summary (id),
  CONSTRAINT chk_ai_task_target_type CHECK (target_type IN ('MOVIE', 'ACTIVITY')),
  CONSTRAINT chk_ai_task_status CHECK (
    status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'RETRY_WAIT',
               'DEAD', 'INSUFFICIENT_REVIEWS')
  ),
  CONSTRAINT chk_ai_task_versions CHECK (
    dirty_version >= 0
    AND processed_version >= 0
    AND processed_version <= dirty_version
    AND last_summary_version >= 0
  ),
  CONSTRAINT chk_ai_task_attempts CHECK (attempt_count >= 0),
  CONSTRAINT chk_ai_task_retry_time CHECK (
    (status = 'RETRY_WAIT' AND next_retry_time IS NOT NULL)
    OR (status <> 'RETRY_WAIT' AND next_retry_time IS NULL)
  ),
  CONSTRAINT chk_ai_task_lease CHECK (
    (status = 'PROCESSING'
      AND processing_version IS NOT NULL
      AND lease_owner IS NOT NULL
      AND lease_until IS NOT NULL)
    OR
    (status <> 'PROCESSING'
      AND processing_version IS NULL
      AND lease_owner IS NULL
      AND lease_until IS NULL)
  )
) ENGINE=InnoDB COMMENT='AI review summary tasks';
