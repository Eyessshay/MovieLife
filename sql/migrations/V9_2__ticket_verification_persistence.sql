-- Step 9.2: ticket verification persistence and refund guard prerequisites
-- Target: MySQL 8.0.16+
-- This migration is intentionally repeatable for an existing MovieLife database.

USE movielife;

CREATE TABLE IF NOT EXISTS tb_venue_staff (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  venue_id BIGINT NOT NULL,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '0 disabled, 1 active',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_venue_staff_user_venue (user_id, venue_id),
  KEY idx_venue_staff_venue_status (venue_id, status),
  CONSTRAINT fk_venue_staff_user FOREIGN KEY (user_id) REFERENCES tb_user (id),
  CONSTRAINT fk_venue_staff_venue FOREIGN KEY (venue_id) REFERENCES tb_venue (id),
  CONSTRAINT chk_venue_staff_status CHECK (status IN (0, 1))
) ENGINE=InnoDB COMMENT='venue staff authorization for ticket verification';

DROP PROCEDURE IF EXISTS migrate_step_9_2;

DELIMITER //

CREATE PROCEDURE migrate_step_9_2()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_ticket_order_item'
      AND COLUMN_NAME = 'used_time'
  ) THEN
    ALTER TABLE tb_ticket_order_item
      ADD COLUMN used_time DATETIME NULL
        COMMENT 'first successful verification time; immutable after use'
        AFTER ticket_status;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_ticket_order_item'
      AND COLUMN_NAME = 'used_by_user_id'
  ) THEN
    ALTER TABLE tb_ticket_order_item
      ADD COLUMN used_by_user_id BIGINT NULL
        COMMENT 'venue staff user who first verified the ticket'
        AFTER used_time;
  END IF;

  IF EXISTS (
    SELECT 1 FROM tb_ticket_order_item
    WHERE ticket_status NOT IN (0, 1, 2, 3)
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Step 9.2 migration blocked: unknown ticket_status exists';
  END IF;

  IF EXISTS (
    SELECT 1 FROM tb_ticket_order_item
    WHERE (ticket_status = 2 AND (used_time IS NULL OR used_by_user_id IS NULL))
       OR (ticket_status <> 2 AND (used_time IS NOT NULL OR used_by_user_id IS NOT NULL))
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Step 9.2 migration blocked: ticket usage evidence is inconsistent';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_ticket_order_item'
      AND INDEX_NAME = 'idx_order_item_used_by_time'
  ) THEN
    ALTER TABLE tb_ticket_order_item
      ADD KEY idx_order_item_used_by_time (used_by_user_id, used_time);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_ticket_order_item'
      AND CONSTRAINT_NAME = 'fk_order_item_used_by_user'
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
  ) THEN
    ALTER TABLE tb_ticket_order_item
      ADD CONSTRAINT fk_order_item_used_by_user
        FOREIGN KEY (used_by_user_id) REFERENCES tb_user (id);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_ticket_order_item'
      AND CONSTRAINT_NAME = 'chk_order_item_ticket_status'
      AND CONSTRAINT_TYPE = 'CHECK'
  ) THEN
    ALTER TABLE tb_ticket_order_item
      ADD CONSTRAINT chk_order_item_ticket_status
        CHECK (ticket_status IN (0, 1, 2, 3));
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_ticket_order_item'
      AND CONSTRAINT_NAME = 'chk_order_item_used_evidence'
      AND CONSTRAINT_TYPE = 'CHECK'
  ) THEN
    ALTER TABLE tb_ticket_order_item
      ADD CONSTRAINT chk_order_item_used_evidence CHECK (
        (ticket_status = 2 AND used_time IS NOT NULL AND used_by_user_id IS NOT NULL)
        OR (ticket_status <> 2 AND used_time IS NULL AND used_by_user_id IS NULL)
      );
  END IF;
END//

DELIMITER ;

CALL migrate_step_9_2();
DROP PROCEDURE migrate_step_9_2;
