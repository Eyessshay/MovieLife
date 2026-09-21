# MovieLife 下一对话上下文（Step 15.1 已冻结）

## 当前状态

- 阶段 1～14 已完成并冻结。
- Stage 15 方向固定为“Venue Staff 固定座位 Activity 场次运营”。
- Step 15.1“管理权限与场次查询基线”已完成并冻结；Stage 15 尚未整体完成。
- 当前实现开放：
  - `GET /api/admin/venues/{venueId}/activity-showtimes`
  - `GET /api/admin/venues/{venueId}/activity-showtimes/{showtimeId}`
- Step 15.1 专项测试 17 项、全量测试 569 项全部通过，0 failure、0 error、0 skipped；不跳过测试的 `mvn clean package` 为 `BUILD SUCCESS`。
- 真实联调使用隔离 MySQL 8.0.17、Redis 7 和随机 HTTP 端口；测试数据和 Redis 已清理，Redis 最终 `DBSIZE=0`。

## Step 15.1 冻结边界

1. 管理权限只认 `tb_venue_staff(user_id, venue_id, status=1)`；普通登录、其他场馆员工和停用员工返回 `VENUE_MANAGE_FORBIDDEN`。
2. 列表始终限定路径 Venue 和严格大写 `ACTIVITY`，支持 `page/pageSize/showtimeStatus/startDate/endDate`，按 `start_time DESC, id DESC` 稳定分页。
3. 管理端可查看本场馆待售、停售和历史 Activity 场次，不复用公开查询的未来在售规则。
4. 详情通过 `showtimeId + venueId + ACTIVITY` 联合定位；Movie、其他场馆和不存在的场次统一返回 `SHOWTIME_NOT_FOUND`。
5. Activity 与 Hall 分别批量查询；Hall 跨场馆、关联缺失或库存数值不可能时返回 `INTERNAL_ERROR`。
6. 该能力只读 MySQL，不新增管理 Redis 状态，不改变 Showtime、ShowtimeSeat、余票、锁座、订单、支付、退款、出票、核销、评价、AI Summary 或 GEO 语义。

## 下一步规则

- 下一步只能在明确指令后进入 Step 15.2，先设计固定座位 Activity 场次创建、排期冲突、创建幂等和原子座位初始化契约。
- 不得提前实现停售/恢复销售、改期、改厅、Activity 主数据管理、Movie 管理或非固定座位票务。
- Step 15.2 仍须独立完成：设计、实现、自动化测试、真实 MySQL/Redis/HTTP 联调、`mvn clean package`、Git commit 和冻结。
