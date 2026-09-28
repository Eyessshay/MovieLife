USE movielife;
SET NAMES utf8mb4;

SELECT
  (SELECT COUNT(*) FROM tb_user WHERE id BETWEEN 1604000000 AND 1604999999) AS demo_users,
  (SELECT COUNT(*) FROM tb_venue WHERE id IN (1604000101, 1604000102) AND status = 1) AS demo_venues,
  (SELECT COUNT(*) FROM tb_venue_staff WHERE user_id = 1604000002 AND venue_id = 1604000101 AND status = 1) AS target_staff,
  (SELECT COUNT(*) FROM tb_venue_staff WHERE user_id = 1604000003 AND venue_id = 1604000102 AND status = 1) AS other_staff,
  (SELECT COUNT(*) FROM tb_venue_staff WHERE user_id = 1604000003 AND venue_id = 1604000101 AND status = 1) AS other_staff_leak,
  (SELECT COUNT(*) FROM tb_venue_staff WHERE user_id = 1604000004 AND venue_id = 1604000101 AND status = 0) AS disabled_staff,
  (SELECT COUNT(*) FROM tb_venue_staff WHERE user_id = 1604000004 AND venue_id = 1604000101 AND status = 1) AS disabled_staff_leak,
  (SELECT COUNT(*) FROM tb_venue_staff WHERE user_id = 1604000001) AS buyer_staff_leak,
  (SELECT COUNT(*) FROM tb_seat WHERE hall_id = 1604000301 AND base_status = 1) AS seeded_hall_seats,
  (SELECT COUNT(*) FROM tb_seat WHERE hall_id = 1604000302 AND base_status = 1) AS creation_hall_seats,
  (SELECT COUNT(*) FROM tb_showtime WHERE id = 1604000901 AND biz_type = 'ACTIVITY'
     AND biz_id = 1604000501 AND venue_id = 1604000101 AND hall_id = 1604000301
     AND start_time > NOW() AND end_time > start_time AND status = 1
     AND total_seats = 6 AND available_seats = 6) AS future_showtime,
  (SELECT COUNT(*) FROM tb_showtime_seat WHERE showtime_id = 1604000901) AS showtime_seats,
  (SELECT COUNT(*) FROM tb_showtime_seat WHERE showtime_id = 1604000901
     AND status = 0 AND lock_order_id IS NULL AND price = 168.00) AS available_showtime_seats,
  (SELECT COUNT(*) FROM tb_showtime WHERE hall_id = 1604000302 AND end_time > NOW()) AS creation_hall_conflicts,
  (SELECT COUNT(*) FROM tb_activity WHERE id = 1604000501 AND status = 1) AS on_sale_activity,
  (SELECT COUNT(*) FROM tb_review WHERE target_type = 'MOVIE' AND target_id = 1604000601 AND status = 1) AS movie_reviews,
  (SELECT COUNT(*) FROM tb_ai_summary_task t JOIN tb_ai_review_summary s ON s.id = t.current_summary_id
     WHERE t.id = 1604000802 AND t.target_type = 'MOVIE' AND t.target_id = 1604000601
       AND t.status = 'SUCCEEDED' AND t.dirty_version = t.processed_version
       AND s.target_type = t.target_type AND s.target_id = t.target_id
       AND s.source_review_count = 3 AND s.input_review_count = 3) AS ready_summary,
  (SELECT COUNT(*) FROM tb_ticket_order WHERE showtime_id = 1604000901) AS seeded_orders,
  (SELECT COUNT(*) FROM tb_ticket_order_item i JOIN tb_ticket_order o ON o.id = i.order_id
     WHERE o.showtime_id = 1604000901 AND i.ticket_code IS NOT NULL) AS seeded_tickets;
