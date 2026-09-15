-- Step 13.2: Activity review aggregate persistence
-- Target: MySQL 8.0.16+
-- This migration is intentionally repeatable for an existing MovieLife database.

USE movielife;

DROP PROCEDURE IF EXISTS migrate_step_13_2;

DELIMITER //

CREATE PROCEDURE migrate_step_13_2()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_activity'
      AND COLUMN_NAME = 'avg_score'
  ) THEN
    ALTER TABLE tb_activity
      ADD COLUMN avg_score DECIMAL(3,2) NOT NULL DEFAULT 0.00
        AFTER price_max;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_activity'
      AND COLUMN_NAME = 'score_count'
  ) THEN
    ALTER TABLE tb_activity
      ADD COLUMN score_count INT NOT NULL DEFAULT 0
        AFTER avg_score;
  END IF;

  IF EXISTS (
    SELECT 1
    FROM tb_review review
    LEFT JOIN tb_activity activity ON activity.id = review.target_id
    WHERE review.target_type = 'ACTIVITY'
      AND review.status = 1
      AND activity.id IS NULL
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Step 13.2 migration blocked: active Activity review target is missing';
  END IF;
END//

DELIMITER ;

CALL migrate_step_13_2();
DROP PROCEDURE migrate_step_13_2;

UPDATE tb_activity activity
LEFT JOIN (
  SELECT target_id,
         ROUND(AVG(score), 2) AS avg_score,
         COUNT(*) AS score_count
  FROM tb_review
  WHERE target_type = 'ACTIVITY'
    AND status = 1
  GROUP BY target_id
) review_aggregate ON review_aggregate.target_id = activity.id
SET activity.avg_score = COALESCE(review_aggregate.avg_score, 0.00),
    activity.score_count = COALESCE(review_aggregate.score_count, 0);
