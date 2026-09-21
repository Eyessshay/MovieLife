-- Step 15.2: venue-scoped Activity showtime creation idempotency
-- Target: MySQL 8.0.16+
-- This migration is intentionally repeatable for an existing MovieLife database.

USE movielife;

DROP PROCEDURE IF EXISTS migrate_step_15_2;

DELIMITER //

CREATE PROCEDURE migrate_step_15_2()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_showtime'
      AND COLUMN_NAME = 'create_request_id'
  ) THEN
    ALTER TABLE tb_showtime
      ADD COLUMN create_request_id VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'venue-scoped idempotency key for admin creation'
        AFTER hall_id;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_showtime'
      AND INDEX_NAME = 'uk_showtime_venue_create_request'
  ) THEN
    ALTER TABLE tb_showtime
      ADD UNIQUE KEY uk_showtime_venue_create_request (venue_id, create_request_id);
  END IF;
END//

DELIMITER ;

CALL migrate_step_15_2();
DROP PROCEDURE migrate_step_15_2;
