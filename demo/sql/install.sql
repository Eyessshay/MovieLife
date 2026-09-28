-- MovieLife Stage 16.4 deterministic demo dataset.
-- Ownership boundary: reserved primary-key range 1604000000..1604999999
-- and rows transitively owned by the reserved demo venues/showtimes.
-- This file is intentionally separate from sql/movielife.sql and migrations.

USE movielife;
SET NAMES utf8mb4;

START TRANSACTION;

SET @demo_start = TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL 2 DAY), '19:30:00');
SET @demo_end = DATE_ADD(@demo_start, INTERVAL 2 HOUR);

INSERT INTO tb_user (id, phone, nick_name, city, gender)
VALUES
  (1604000001, '13916040001', '[DEMO-16.4] 购票用户', '上海', 0),
  (1604000002, '13916040002', '[DEMO-16.4] 本场馆员工', '上海', 0),
  (1604000003, '13916040003', '[DEMO-16.4] 其他场馆员工', '上海', 0),
  (1604000004, '13916040004', '[DEMO-16.4] 停用员工', '上海', 0)
ON DUPLICATE KEY UPDATE
  phone = VALUES(phone), nick_name = VALUES(nick_name), city = VALUES(city),
  gender = VALUES(gender), update_time = CURRENT_TIMESTAMP;

INSERT INTO tb_venue
  (id, name, type, city, address, longitude, latitude, phone, open_time, avg_score, status)
VALUES
  (1604000101, '[DEMO-16.4] 星河艺术中心', 'theater', '上海',
   '浦东新区演示路16号', 121.506000, 31.235000, '021-61640001',
   '10:00-22:30', 4.70, 1),
  (1604000102, '[DEMO-16.4] 远岸剧场', 'theater', '上海',
   '长宁区演示路24号', 121.410000, 31.215000, '021-61640002',
   '10:00-22:00', 4.30, 1)
ON DUPLICATE KEY UPDATE
  name = VALUES(name), type = VALUES(type), city = VALUES(city),
  address = VALUES(address), longitude = VALUES(longitude), latitude = VALUES(latitude),
  phone = VALUES(phone), open_time = VALUES(open_time), avg_score = VALUES(avg_score),
  status = VALUES(status), update_time = CURRENT_TIMESTAMP;

INSERT INTO tb_venue_staff (id, user_id, venue_id, status)
VALUES
  (1604000201, 1604000002, 1604000101, 1),
  (1604000202, 1604000003, 1604000102, 1),
  (1604000203, 1604000004, 1604000101, 0)
ON DUPLICATE KEY UPDATE status = VALUES(status), update_time = CURRENT_TIMESTAMP;

INSERT INTO tb_hall (id, venue_id, name)
VALUES
  (1604000301, 1604000101, '[DEMO-16.4] 星光厅'),
  (1604000302, 1604000101, '[DEMO-16.4] 创排厅')
ON DUPLICATE KEY UPDATE venue_id = VALUES(venue_id), name = VALUES(name);

INSERT INTO tb_seat
  (id, hall_id, row_no, col_no, seat_no, seat_type, base_status)
VALUES
  (1604000401, 1604000301, 1, 1, 'A01', 'NORMAL', 1),
  (1604000402, 1604000301, 1, 2, 'A02', 'NORMAL', 1),
  (1604000403, 1604000301, 1, 3, 'A03', 'VIP', 1),
  (1604000404, 1604000301, 2, 1, 'B01', 'NORMAL', 1),
  (1604000405, 1604000301, 2, 2, 'B02', 'NORMAL', 1),
  (1604000406, 1604000301, 2, 3, 'B03', 'VIP', 1),
  (1604000411, 1604000302, 1, 1, 'A01', 'NORMAL', 1),
  (1604000412, 1604000302, 1, 2, 'A02', 'NORMAL', 1),
  (1604000413, 1604000302, 1, 3, 'A03', 'VIP', 1),
  (1604000414, 1604000302, 2, 1, 'B01', 'NORMAL', 1),
  (1604000415, 1604000302, 2, 2, 'B02', 'NORMAL', 1),
  (1604000416, 1604000302, 2, 3, 'B03', 'VIP', 1)
ON DUPLICATE KEY UPDATE
  hall_id = VALUES(hall_id), row_no = VALUES(row_no), col_no = VALUES(col_no),
  seat_no = VALUES(seat_no), seat_type = VALUES(seat_type), base_status = VALUES(base_status);

INSERT INTO tb_activity
  (id, title, cover_url, type, city, description, price_min, price_max,
   avg_score, score_count, status)
VALUES
  (1604000501, '[DEMO-16.4] 即兴喜剧之夜', NULL, 'talkshow', '上海',
   '用于 MovieLife 固定座位 Activity 全链路演示。', 168.00, 168.00,
   0.00, 0, 1)
ON DUPLICATE KEY UPDATE
  title = VALUES(title), type = VALUES(type), city = VALUES(city),
  description = VALUES(description), price_min = VALUES(price_min),
  price_max = VALUES(price_max), avg_score = VALUES(avg_score),
  score_count = VALUES(score_count), status = VALUES(status),
  update_time = CURRENT_TIMESTAMP;

INSERT INTO tb_movie
  (id, title, original_title, cover_url, category, region, language,
   release_date, duration, director, actors, summary, avg_score, score_count, status)
VALUES
  (1604000601, '[DEMO-16.4] 城市放映机', 'City Projector', NULL,
   '剧情/喜剧', '中国大陆', '中文', DATE_SUB(CURDATE(), INTERVAL 30 DAY),
   112, '演示导演', '演示演员甲,演示演员乙',
   '用于展示 Movie 与 Review AI Summary 的确定性样例。', 4.50, 3, 1)
ON DUPLICATE KEY UPDATE
  title = VALUES(title), original_title = VALUES(original_title),
  category = VALUES(category), region = VALUES(region), language = VALUES(language),
  release_date = VALUES(release_date), duration = VALUES(duration),
  director = VALUES(director), actors = VALUES(actors), summary = VALUES(summary),
  avg_score = VALUES(avg_score), score_count = VALUES(score_count),
  status = VALUES(status), update_time = CURRENT_TIMESTAMP;

INSERT INTO tb_review
  (id, user_id, target_type, target_id, score, content, like_count,
   comment_count, status, create_time, update_time)
VALUES
  (1604000701, 1604000001, 'MOVIE', 1604000601, 4.5,
   '人物关系自然，城市夜景很有氛围。', 0, 0, 1,
   DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY)),
  (1604000702, 1604000002, 'MOVIE', 1604000601, 5.0,
   '节奏轻快，笑点和情感表达平衡。', 0, 0, 1,
   DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
  (1604000703, 1604000003, 'MOVIE', 1604000601, 4.0,
   '中段稍慢，但结尾完整，适合朋友一起看。', 0, 0, 1,
   DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY))
ON DUPLICATE KEY UPDATE
  user_id = VALUES(user_id), target_type = VALUES(target_type),
  target_id = VALUES(target_id), score = VALUES(score), content = VALUES(content),
  like_count = VALUES(like_count), comment_count = VALUES(comment_count),
  status = VALUES(status), create_time = VALUES(create_time), update_time = VALUES(update_time);

INSERT INTO tb_ai_review_summary
  (id, target_type, target_id, prompt_version, source_fingerprint,
   source_version, summary, pros, cons, suitable_for, warning_points,
   source_review_count, input_review_count, model_name, version, create_time)
VALUES
  (1604000801, 'MOVIE', 1604000601, 'review-summary-v1',
   '1640000016400000164000001640000016400000164000001640000016400000',
   3, '整体评价积极，观众认可城市氛围、人物关系以及轻快的情感表达。',
   '["城市氛围鲜明","人物关系自然","情感与笑点平衡"]',
   '["中段节奏略慢"]', '["朋友结伴观看","偏爱都市轻喜剧的观众"]',
   '["期待全程快节奏的观众需留意中段"]', 3, 3,
   'demo-static-summary', 1, NOW())
ON DUPLICATE KEY UPDATE
  target_type = VALUES(target_type), target_id = VALUES(target_id),
  prompt_version = VALUES(prompt_version), source_fingerprint = VALUES(source_fingerprint),
  source_version = VALUES(source_version), summary = VALUES(summary), pros = VALUES(pros),
  cons = VALUES(cons), suitable_for = VALUES(suitable_for),
  warning_points = VALUES(warning_points), source_review_count = VALUES(source_review_count),
  input_review_count = VALUES(input_review_count), model_name = VALUES(model_name),
  version = VALUES(version), create_time = VALUES(create_time);

INSERT INTO tb_ai_summary_task
  (id, target_type, target_id, dirty_version, processed_version,
   processing_version, last_summary_version, current_summary_id, status,
   attempt_count, next_retry_time, lease_owner, lease_until, last_error_code)
VALUES
  (1604000802, 'MOVIE', 1604000601, 3, 3, NULL, 1,
   1604000801, 'SUCCEEDED', 0, NULL, NULL, NULL, NULL)
ON DUPLICATE KEY UPDATE
  dirty_version = VALUES(dirty_version), processed_version = VALUES(processed_version),
  processing_version = NULL, last_summary_version = VALUES(last_summary_version),
  current_summary_id = VALUES(current_summary_id), status = VALUES(status),
  attempt_count = 0, next_retry_time = NULL, lease_owner = NULL,
  lease_until = NULL, last_error_code = NULL, update_time = CURRENT_TIMESTAMP(3);

INSERT INTO tb_showtime
  (id, biz_type, biz_id, venue_id, hall_id, create_request_id,
   start_time, end_time, language_version, base_price,
   total_seats, available_seats, status)
VALUES
  (1604000901, 'ACTIVITY', 1604000501, 1604000101, 1604000301,
   'demo-16.4-seeded-showtime', @demo_start, @demo_end, NULL,
   168.00, 6, 6, 1)
ON DUPLICATE KEY UPDATE
  biz_type = VALUES(biz_type), biz_id = VALUES(biz_id), venue_id = VALUES(venue_id),
  hall_id = VALUES(hall_id), create_request_id = VALUES(create_request_id),
  start_time = VALUES(start_time), end_time = VALUES(end_time),
  language_version = VALUES(language_version), base_price = VALUES(base_price),
  total_seats = VALUES(total_seats), available_seats = VALUES(available_seats),
  status = VALUES(status), update_time = CURRENT_TIMESTAMP;

INSERT INTO tb_showtime_seat
  (id, showtime_id, seat_id, status, price, lock_order_id, version)
VALUES
  (1604001001, 1604000901, 1604000401, 0, 168.00, NULL, 0),
  (1604001002, 1604000901, 1604000402, 0, 168.00, NULL, 0),
  (1604001003, 1604000901, 1604000403, 0, 168.00, NULL, 0),
  (1604001004, 1604000901, 1604000404, 0, 168.00, NULL, 0),
  (1604001005, 1604000901, 1604000405, 0, 168.00, NULL, 0),
  (1604001006, 1604000901, 1604000406, 0, 168.00, NULL, 0)
ON DUPLICATE KEY UPDATE
  showtime_id = VALUES(showtime_id), seat_id = VALUES(seat_id), status = VALUES(status),
  price = VALUES(price), lock_order_id = NULL, version = VALUES(version);

COMMIT;
