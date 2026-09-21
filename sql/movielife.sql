-- MovieLife database schema
-- Target: MySQL 8.0+
-- The application should validate polymorphic references represented by
-- target_type/target_id or biz_type/biz_id in the service layer.

CREATE DATABASE IF NOT EXISTS movielife
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE movielife;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE IF NOT EXISTS tb_user (
  id BIGINT NOT NULL AUTO_INCREMENT,
  phone VARCHAR(20) NOT NULL,
  nick_name VARCHAR(64) NOT NULL,
  icon VARCHAR(255) NULL,
  gender TINYINT NULL COMMENT '0 unknown, 1 male, 2 female',
  city VARCHAR(64) NULL,
  birthday DATE NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_phone (phone),
  KEY idx_user_city (city),
  KEY idx_user_create_time (create_time)
) ENGINE=InnoDB COMMENT='users';

CREATE TABLE IF NOT EXISTS tb_movie (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(128) NOT NULL,
  original_title VARCHAR(128) NULL,
  cover_url VARCHAR(255) NULL,
  category VARCHAR(128) NULL,
  region VARCHAR(64) NULL,
  language VARCHAR(64) NULL,
  release_date DATE NULL,
  duration INT NULL,
  director VARCHAR(255) NULL,
  actors TEXT NULL,
  summary TEXT NULL,
  avg_score DECIMAL(3,2) NOT NULL DEFAULT 0.00,
  score_count INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0 coming, 1 showing, 2 offline',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_movie_status_release (status, release_date),
  KEY idx_movie_score (avg_score, score_count),
  KEY idx_movie_title (title)
) ENGINE=InnoDB COMMENT='movies';

CREATE TABLE IF NOT EXISTS tb_venue (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  type VARCHAR(32) NOT NULL COMMENT 'cinema/theater/livehouse/exhibition',
  city VARCHAR(64) NOT NULL,
  address VARCHAR(255) NOT NULL,
  longitude DECIMAL(10,6) NULL,
  latitude DECIMAL(10,6) NULL,
  phone VARCHAR(32) NULL,
  open_time VARCHAR(128) NULL,
  avg_score DECIMAL(3,2) NOT NULL DEFAULT 0.00,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '0 disabled, 1 enabled',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_venue_city_type (city, type),
  KEY idx_venue_location (longitude, latitude),
  KEY idx_venue_status (status)
) ENGINE=InnoDB COMMENT='venues';

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

CREATE TABLE IF NOT EXISTS tb_activity (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(128) NOT NULL,
  cover_url VARCHAR(255) NULL,
  type VARCHAR(32) NOT NULL COMMENT 'concert/drama/talkshow/exhibition/livehouse',
  city VARCHAR(64) NOT NULL,
  description TEXT NULL,
  price_min DECIMAL(10,2) NULL,
  price_max DECIMAL(10,2) NULL,
  avg_score DECIMAL(3,2) NOT NULL DEFAULT 0.00,
  score_count INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '0 draft, 1 on sale, 2 offline',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_activity_city_type (city, type),
  KEY idx_activity_status (status)
) ENGINE=InnoDB COMMENT='activities';

CREATE TABLE IF NOT EXISTS tb_hall (
  id BIGINT NOT NULL AUTO_INCREMENT,
  venue_id BIGINT NOT NULL,
  name VARCHAR(64) NOT NULL,
  seat_template_id BIGINT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_hall_venue_name (venue_id, name),
  KEY idx_hall_venue (venue_id),
  CONSTRAINT fk_hall_venue FOREIGN KEY (venue_id) REFERENCES tb_venue (id)
) ENGINE=InnoDB COMMENT='venue halls';

CREATE TABLE IF NOT EXISTS tb_seat (
  id BIGINT NOT NULL AUTO_INCREMENT,
  hall_id BIGINT NOT NULL,
  row_no INT NOT NULL,
  col_no INT NOT NULL,
  seat_no VARCHAR(16) NOT NULL,
  seat_type VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
  base_status TINYINT NOT NULL DEFAULT 1 COMMENT '0 disabled, 1 enabled',
  PRIMARY KEY (id),
  UNIQUE KEY uk_seat_hall_seat_no (hall_id, seat_no),
  UNIQUE KEY uk_seat_hall_position (hall_id, row_no, col_no),
  KEY idx_seat_hall_status (hall_id, base_status),
  CONSTRAINT fk_seat_hall FOREIGN KEY (hall_id) REFERENCES tb_hall (id)
) ENGINE=InnoDB COMMENT='physical seat templates';

CREATE TABLE IF NOT EXISTS tb_showtime (
  id BIGINT NOT NULL AUTO_INCREMENT,
  biz_type VARCHAR(16) NOT NULL COMMENT 'MOVIE/ACTIVITY',
  biz_id BIGINT NOT NULL,
  venue_id BIGINT NOT NULL,
  hall_id BIGINT NOT NULL,
  create_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
    COMMENT 'venue-scoped idempotency key for admin creation',
  start_time DATETIME NOT NULL,
  end_time DATETIME NOT NULL,
  language_version VARCHAR(32) NULL,
  base_price DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  total_seats INT NOT NULL DEFAULT 0,
  available_seats INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '0 stopped, 1 on sale, 2 finished',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_showtime_venue_create_request (venue_id, create_request_id),
  KEY idx_showtime_biz (biz_type, biz_id, start_time, status),
  KEY idx_showtime_venue_time (venue_id, start_time),
  KEY idx_showtime_hall_time (hall_id, start_time),
  CONSTRAINT fk_showtime_venue FOREIGN KEY (venue_id) REFERENCES tb_venue (id),
  CONSTRAINT fk_showtime_hall FOREIGN KEY (hall_id) REFERENCES tb_hall (id)
) ENGINE=InnoDB COMMENT='movie/activity showtimes';

CREATE TABLE IF NOT EXISTS tb_showtime_seat (
  id BIGINT NOT NULL AUTO_INCREMENT,
  showtime_id BIGINT NOT NULL,
  seat_id BIGINT NOT NULL,
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0 available, 1 held by unpaid order, 2 sold; pre-order locks live in Redis',
  price DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  lock_order_id BIGINT NULL,
  version INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_showtime_seat (showtime_id, seat_id),
  KEY idx_showtime_seat_status (showtime_id, status),
  KEY idx_showtime_seat_lock_order (lock_order_id),
  CONSTRAINT fk_showtime_seat_showtime FOREIGN KEY (showtime_id) REFERENCES tb_showtime (id),
  CONSTRAINT fk_showtime_seat_seat FOREIGN KEY (seat_id) REFERENCES tb_seat (id)
) ENGINE=InnoDB COMMENT='seat state for each showtime';

CREATE TABLE IF NOT EXISTS tb_ticket_order (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  showtime_id BIGINT NOT NULL,
  lock_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'one-time Redis seat-lock token consumed by this order',
  total_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  status VARCHAR(16) NOT NULL DEFAULT 'UNPAID' COMMENT 'UNPAID/PAID/CANCELED/REFUNDED',
  expire_time DATETIME NOT NULL COMMENT 'independent payment deadline for the unpaid order',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  pay_time DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_lock_token (lock_token),
  KEY idx_order_user_time (user_id, create_time),
  KEY idx_order_showtime_status (showtime_id, status),
  KEY idx_order_expire_status (expire_time, status),
  CONSTRAINT fk_order_user FOREIGN KEY (user_id) REFERENCES tb_user (id),
  CONSTRAINT fk_order_showtime FOREIGN KEY (showtime_id) REFERENCES tb_showtime (id)
) ENGINE=InnoDB COMMENT='ticket orders';

CREATE TABLE IF NOT EXISTS tb_ticket_order_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  seat_id BIGINT NOT NULL,
  price DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  ticket_code VARCHAR(64) NULL,
  ticket_status TINYINT NOT NULL DEFAULT 0 COMMENT '0 pending, 1 valid, 2 used, 3 refunded',
  used_time DATETIME NULL COMMENT 'first successful verification time; immutable after use',
  used_by_user_id BIGINT NULL COMMENT 'venue staff user who first verified the ticket',
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_item_seat (order_id, seat_id),
  UNIQUE KEY uk_ticket_code (ticket_code),
  KEY idx_order_item_seat (seat_id),
  KEY idx_order_item_ticket_status (ticket_status),
  KEY idx_order_item_used_by_time (used_by_user_id, used_time),
  CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES tb_ticket_order (id),
  CONSTRAINT fk_order_item_seat FOREIGN KEY (seat_id) REFERENCES tb_seat (id),
  CONSTRAINT fk_order_item_used_by_user FOREIGN KEY (used_by_user_id) REFERENCES tb_user (id),
  CONSTRAINT chk_order_item_ticket_status CHECK (ticket_status IN (0, 1, 2, 3)),
  CONSTRAINT chk_order_item_used_evidence CHECK (
    (ticket_status = 2 AND used_time IS NOT NULL AND used_by_user_id IS NOT NULL)
    OR (ticket_status <> 2 AND used_time IS NULL AND used_by_user_id IS NULL)
  )
) ENGINE=InnoDB COMMENT='order seats';

CREATE TABLE IF NOT EXISTS tb_review (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  target_type VARCHAR(16) NOT NULL COMMENT 'MOVIE/ACTIVITY/VENUE',
  target_id BIGINT NOT NULL,
  score DECIMAL(2,1) NOT NULL COMMENT '1.0-5.0; service layer accepts half-star increments only',
  content TEXT NULL,
  images TEXT NULL COMMENT 'JSON array string',
  like_count INT NOT NULL DEFAULT 0,
  comment_count INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '0 deleted, 1 normal, 2 audit',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_review_user_target (user_id, target_type, target_id),
  KEY idx_review_target_time (target_type, target_id, create_time),
  KEY idx_review_target_like (target_type, target_id, like_count),
  CONSTRAINT fk_review_user FOREIGN KEY (user_id) REFERENCES tb_user (id),
  CONSTRAINT chk_review_score CHECK (score >= 1.0 AND score <= 5.0)
) ENGINE=InnoDB COMMENT='reviews';

CREATE TABLE IF NOT EXISTS tb_post (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  title VARCHAR(128) NOT NULL,
  content TEXT NOT NULL,
  images TEXT NULL COMMENT 'JSON array string',
  related_type VARCHAR(16) NULL COMMENT 'MOVIE/ACTIVITY',
  related_id BIGINT NULL,
  liked INT NOT NULL DEFAULT 0,
  comments INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_post_user_time (user_id, create_time),
  KEY idx_post_related_time (related_type, related_id, create_time),
  CONSTRAINT fk_post_user FOREIGN KEY (user_id) REFERENCES tb_user (id)
) ENGINE=InnoDB COMMENT='community posts';

CREATE TABLE IF NOT EXISTS tb_follow (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  follow_user_id BIGINT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_follow_pair (user_id, follow_user_id),
  KEY idx_follow_target (follow_user_id, user_id),
  CONSTRAINT fk_follow_user FOREIGN KEY (user_id) REFERENCES tb_user (id),
  CONSTRAINT fk_follow_target_user FOREIGN KEY (follow_user_id) REFERENCES tb_user (id),
  CONSTRAINT chk_follow_not_self CHECK (user_id <> follow_user_id)
) ENGINE=InnoDB COMMENT='user follows';

CREATE TABLE IF NOT EXISTS tb_comment (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  target_type VARCHAR(16) NOT NULL COMMENT 'REVIEW/POST',
  target_id BIGINT NOT NULL,
  parent_id BIGINT NULL,
  root_id BIGINT NULL,
  content VARCHAR(1000) NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_comment_target_time (target_type, target_id, create_time),
  KEY idx_comment_root_time (root_id, create_time),
  KEY idx_comment_user_time (user_id, create_time),
  CONSTRAINT fk_comment_user FOREIGN KEY (user_id) REFERENCES tb_user (id),
  CONSTRAINT fk_comment_parent FOREIGN KEY (parent_id) REFERENCES tb_comment (id),
  CONSTRAINT fk_comment_root FOREIGN KEY (root_id) REFERENCES tb_comment (id)
) ENGINE=InnoDB COMMENT='comments';

CREATE TABLE IF NOT EXISTS tb_collection (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  target_type VARCHAR(16) NOT NULL COMMENT 'MOVIE/ACTIVITY/POST/REVIEW',
  target_id BIGINT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_collection_user_target (user_id, target_type, target_id),
  KEY idx_collection_target (target_type, target_id, create_time),
  CONSTRAINT fk_collection_user FOREIGN KEY (user_id) REFERENCES tb_user (id)
) ENGINE=InnoDB COMMENT='collections';

CREATE TABLE IF NOT EXISTS tb_ai_review_summary (
  id BIGINT NOT NULL AUTO_INCREMENT,
  target_type VARCHAR(16) NOT NULL,
  target_id BIGINT NOT NULL,
  prompt_version VARCHAR(64) NOT NULL,
  source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_version BIGINT NOT NULL,
  summary TEXT NULL,
  pros TEXT NULL COMMENT 'JSON array string',
  cons TEXT NULL COMMENT 'JSON array string',
  suitable_for TEXT NULL COMMENT 'JSON array string',
  warning_points TEXT NULL COMMENT 'JSON array string',
  source_review_count INT NOT NULL DEFAULT 0,
  input_review_count INT NOT NULL DEFAULT 0,
  model_name VARCHAR(64) NULL,
  version INT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_summary_target_version (target_type, target_id, version),
  UNIQUE KEY uk_ai_summary_target_input
    (target_type, target_id, prompt_version, source_fingerprint),
  KEY idx_ai_summary_target_time (target_type, target_id, create_time),
  CONSTRAINT chk_ai_summary_target_type CHECK (target_type IN ('MOVIE', 'ACTIVITY')),
  CONSTRAINT chk_ai_summary_versions CHECK (source_version > 0 AND version > 0),
  CONSTRAINT chk_ai_summary_counts CHECK (
    source_review_count >= 0
    AND input_review_count >= 0
    AND input_review_count <= source_review_count
  ),
  CONSTRAINT chk_ai_summary_fingerprint CHECK (CHAR_LENGTH(source_fingerprint) = 64)
) ENGINE=InnoDB COMMENT='AI review summaries';

CREATE TABLE IF NOT EXISTS tb_ai_summary_task (
  id BIGINT NOT NULL AUTO_INCREMENT,
  target_type VARCHAR(16) NOT NULL,
  target_id BIGINT NOT NULL,
  dirty_version BIGINT NOT NULL DEFAULT 0,
  processed_version BIGINT NOT NULL DEFAULT 0,
  processing_version BIGINT NULL,
  last_summary_version INT NOT NULL DEFAULT 0,
  current_summary_id BIGINT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
    COMMENT 'PENDING/PROCESSING/SUCCEEDED/RETRY_WAIT/DEAD/INSUFFICIENT_REVIEWS',
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

CREATE TABLE IF NOT EXISTS tb_user_behavior (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  behavior_type VARCHAR(32) NOT NULL COMMENT 'view/like/collect/score/buy/search',
  target_type VARCHAR(16) NOT NULL,
  target_id BIGINT NOT NULL,
  weight DECIMAL(6,2) NOT NULL DEFAULT 1.00,
  extra TEXT NULL COMMENT 'JSON object string',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_behavior_user_time (user_id, create_time),
  KEY idx_behavior_target_type (target_type, target_id, behavior_type),
  CONSTRAINT fk_behavior_user FOREIGN KEY (user_id) REFERENCES tb_user (id)
) ENGINE=InnoDB COMMENT='user behavior events';

CREATE TABLE IF NOT EXISTS tb_recommendation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  target_type VARCHAR(16) NOT NULL,
  target_id BIGINT NOT NULL,
  reason VARCHAR(500) NULL,
  score DECIMAL(8,4) NOT NULL DEFAULT 0.0000,
  model_version VARCHAR(32) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_recommendation_user_target (user_id, target_type, target_id),
  KEY idx_recommendation_user_score (user_id, score),
  KEY idx_recommendation_target (target_type, target_id),
  CONSTRAINT fk_recommendation_user FOREIGN KEY (user_id) REFERENCES tb_user (id)
) ENGINE=InnoDB COMMENT='personal recommendations';

SET FOREIGN_KEY_CHECKS = 1;

-- Seed data. IDs are stable so the script can be safely re-run in a fresh or
-- development database without creating duplicate reference records.
INSERT INTO tb_user (id, phone, nick_name, city, gender)
VALUES (1, '13800000001', 'MovieLife管理员', '上海', 0),
       (2, '13800000002', '观影用户A', '上海', 1),
       (3, '13800000003', '观影用户B', '上海', 2)
ON DUPLICATE KEY UPDATE nick_name = VALUES(nick_name), city = VALUES(city);

INSERT INTO tb_venue (id, name, type, city, address, longitude, latitude, phone, avg_score)
VALUES (1, 'MovieLife影城', 'cinema', '上海', '徐汇区示例路1号', 121.437000, 31.188000, '021-60000001', 4.50),
       (2, 'MovieLife剧场', 'theater', '上海', '静安区示例路2号', 121.452000, 31.229000, '021-60000002', 4.30)
ON DUPLICATE KEY UPDATE name = VALUES(name), address = VALUES(address), update_time = CURRENT_TIMESTAMP;

INSERT INTO tb_hall (id, venue_id, name)
VALUES (1, 1, '1号厅'), (2, 1, '2号厅'), (3, 2, '主剧场')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO tb_seat (id, hall_id, row_no, col_no, seat_no, seat_type)
VALUES (1, 1, 1, 1, '1排1座', 'NORMAL'),
       (2, 1, 1, 2, '1排2座', 'NORMAL'),
       (3, 1, 1, 3, '1排3座', 'VIP'),
       (4, 1, 2, 1, '2排1座', 'NORMAL'),
       (5, 1, 2, 2, '2排2座', 'NORMAL'),
       (6, 1, 2, 3, '2排3座', 'VIP')
ON DUPLICATE KEY UPDATE seat_type = VALUES(seat_type), base_status = 1;

INSERT INTO tb_seat (hall_id, row_no, col_no, seat_no, seat_type)
VALUES (3, 1, 1, 'A01', 'NORMAL'),
       (3, 1, 2, 'A02', 'NORMAL'),
       (3, 1, 3, 'A03', 'VIP'),
       (3, 2, 1, 'B01', 'NORMAL'),
       (3, 2, 2, 'B02', 'NORMAL'),
       (3, 2, 3, 'B03', 'VIP')
ON DUPLICATE KEY UPDATE seat_type = VALUES(seat_type), base_status = 1;

INSERT INTO tb_movie (id, title, original_title, category, region, language, release_date, duration, director, actors, summary, avg_score, score_count, status)
VALUES (1, '示例电影', 'Sample Movie', '剧情/喜剧', '中国大陆', '中文', '2026-08-01', 120, '示例导演', '示例演员A,示例演员B', 'MovieLife 初始化示例电影数据。', 4.50, 1, 1)
ON DUPLICATE KEY UPDATE title = VALUES(title), summary = VALUES(summary), avg_score = VALUES(avg_score), score_count = VALUES(score_count), status = VALUES(status);

INSERT INTO tb_activity (id, title, type, city, description, price_min, price_max, status)
VALUES (1, '示例现场活动', 'talkshow', '上海', 'MovieLife 初始化示例活动数据。', 99.00, 299.00, 1)
ON DUPLICATE KEY UPDATE title = VALUES(title), description = VALUES(description), status = VALUES(status);

INSERT INTO tb_showtime (id, biz_type, biz_id, venue_id, hall_id, start_time, end_time, language_version, base_price, total_seats, available_seats, status)
VALUES (1, 'MOVIE', 1, 1, 1, '2026-09-01 19:30:00', '2026-09-01 21:30:00', '国语 2D', 45.00, 6, 6, 1),
       (2, 'ACTIVITY', 1, 2, 3,
        DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 19 HOUR),
        DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 21 HOUR),
        NULL, 199.00, 6, 6, 1)
ON DUPLICATE KEY UPDATE start_time = VALUES(start_time), end_time = VALUES(end_time),
                        total_seats = VALUES(total_seats),
                        status = VALUES(status);

INSERT INTO tb_showtime_seat (showtime_id, seat_id, status, price)
SELECT 1, id, 0, CASE WHEN seat_type = 'VIP' THEN 60.00 ELSE 45.00 END
FROM tb_seat WHERE hall_id = 1
ON DUPLICATE KEY UPDATE price = VALUES(price), status = 0;

INSERT INTO tb_showtime_seat (showtime_id, seat_id, status, price)
SELECT 2, id, 0, CASE WHEN seat_type = 'VIP' THEN 299.00 ELSE 199.00 END
FROM tb_seat WHERE hall_id = 3 AND base_status = 1
ON DUPLICATE KEY UPDATE price = VALUES(price);

INSERT INTO tb_follow (user_id, follow_user_id)
VALUES (2, 3)
ON DUPLICATE KEY UPDATE create_time = create_time;

INSERT INTO tb_post (id, user_id, title, content, related_type, related_id)
VALUES (1, 2, '欢迎来到 MovieLife', '这是初始化帖子，可用于联调帖子、评论和 Feed。', 'MOVIE', 1)
ON DUPLICATE KEY UPDATE title = VALUES(title), content = VALUES(content);

INSERT INTO tb_review (id, user_id, target_type, target_id, score, content)
VALUES (1, 2, 'MOVIE', 1, 4.5, '初始化评价：节奏紧凑，适合周末观看。')
ON DUPLICATE KEY UPDATE score = VALUES(score), content = VALUES(content);
