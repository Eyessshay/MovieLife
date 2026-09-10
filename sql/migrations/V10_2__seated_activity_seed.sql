-- Step 10.2: repeatable fixed-seat Activity development data
-- Target: MySQL 8.0.16+

USE movielife;

START TRANSACTION;

INSERT INTO tb_activity (id, title, type, city, description, price_min, price_max, status)
VALUES (1, '示例现场活动', 'talkshow', '上海', 'MovieLife 初始化示例活动数据。', 199.00, 299.00, 1)
ON DUPLICATE KEY UPDATE title = VALUES(title), description = VALUES(description),
                        price_min = VALUES(price_min), price_max = VALUES(price_max), status = 1;

INSERT INTO tb_seat (hall_id, row_no, col_no, seat_no, seat_type)
VALUES (3, 1, 1, 'A01', 'NORMAL'),
       (3, 1, 2, 'A02', 'NORMAL'),
       (3, 1, 3, 'A03', 'VIP'),
       (3, 2, 1, 'B01', 'NORMAL'),
       (3, 2, 2, 'B02', 'NORMAL'),
       (3, 2, 3, 'B03', 'VIP')
ON DUPLICATE KEY UPDATE seat_type = VALUES(seat_type), base_status = 1;

INSERT INTO tb_showtime (
  id, biz_type, biz_id, venue_id, hall_id, start_time, end_time,
  language_version, base_price, total_seats, available_seats, status
)
VALUES (
  2, 'ACTIVITY', 1, 2, 3,
  DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 19 HOUR),
  DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 21 HOUR),
  NULL, 199.00, 6, 6, 1
)
ON DUPLICATE KEY UPDATE biz_type = VALUES(biz_type), biz_id = VALUES(biz_id),
                        venue_id = VALUES(venue_id), hall_id = VALUES(hall_id),
                        start_time = VALUES(start_time), end_time = VALUES(end_time),
                        base_price = VALUES(base_price), total_seats = VALUES(total_seats),
                        status = VALUES(status);

INSERT INTO tb_showtime_seat (showtime_id, seat_id, status, price)
SELECT 2, id, 0, CASE WHEN seat_type = 'VIP' THEN 299.00 ELSE 199.00 END
FROM tb_seat
WHERE hall_id = 3 AND base_status = 1
ON DUPLICATE KEY UPDATE price = VALUES(price);

UPDATE tb_showtime
SET total_seats = (
      SELECT COUNT(*) FROM tb_seat WHERE hall_id = 3 AND base_status = 1
    ),
    available_seats = (
      SELECT COUNT(*) FROM tb_showtime_seat WHERE showtime_id = 2 AND status = 0
    )
WHERE id = 2 AND biz_type = 'ACTIVITY' AND biz_id = 1;

COMMIT;
