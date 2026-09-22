# MovieLife 下一对话上下文（Step 15.3 已冻结）

## 当前状态

- 阶段 1～14 已完成并冻结。
- Stage 15 方向固定为“Venue Staff 固定座位 Activity 场次运营”。
- Step 15.1“管理权限与场次查询基线”、Step 15.2“创建固定座位 Activity 场次”和 Step 15.3“停售与恢复销售”均已完成并冻结；Stage 15 尚未整体完成。
- 当前新增状态入口：
  - `POST /api/admin/venues/{venueId}/activity-showtimes/{showtimeId}/stop-sale`
  - `POST /api/admin/venues/{venueId}/activity-showtimes/{showtimeId}/resume-sale`
- Step 15.3 新增 19 项专项测试；管理与订单交叉回归 81 项、全量 605 项全部通过，0 failure、0 error、0 skipped；不跳过测试的 `mvn clean package` 为 `BUILD SUCCESS`。
- 真实联调使用隔离 MySQL 8.0.17、Redis 7 和随机 HTTP 端口；测试数据、缓存和容器均已清理，Redis 最终 `DBSIZE=0`。

## Step 15.3 冻结边界

1. 权限只认路径 Venue 的有效 `tb_venue_staff`；场次必须同时匹配 `showtimeId + venueId + biz_type=ACTIVITY`，跨场馆不泄露数据。
2. 只允许未来场次执行 `ON_SALE(1) ↔ STOPPED(0)`；重复请求幂等成功，`status=2`、已开始或已结束场次拒绝人工操作。
3. 状态事务使用 Showtime 行锁和带预期旧状态、场馆、业务类型、未来时间条件的 UPDATE；影响行数必须严格为 1。
4. 恢复销售重新校验 Venue 启用、Activity `status=1`、Hall 仍属于 Venue、ShowtimeSeat 总数等于 `total_seats`，以及可用座位事实等于 `available_seats`；不一致返回 `SHOWTIME_INVENTORY_INCONSISTENT`。
5. 停售不删除 ShowtimeSeat、不重置余票、不释放占座、不取消订单、不退款、不删除 Redis 锁，也不触发 Review AI Summary。
6. 停售前已生成但未建单的 lockToken 保留至自然过期；停售后创建订单被拒绝，MySQL 订单、明细、座位和余票全部不变化。
7. 订单扣减 `available_seats` 的条件 UPDATE 已增强为同时要求 `showtime.status=1`；与停售并发时只能得到“订单完整成立后停售”或“订单事务完整回滚”两种一致终态。
8. 已成立订单继续遵守阶段 8～10 的支付、取消、超时、退款、出票和核销语义；停售后的原未支付订单仍可正常取消并恢复座位及余票。
9. 本 Step 不提供 Activity 主数据管理、场次改期/改厅、人工 `status=2`、强制退票、订单迁移、座位模板同步、GEO 同步或非固定座位票务。

## 下一 Step 启动规则

- 先只读检查当前分支、冻结提交和工作树，确认干净后再设计。
- 一次只推进一个明确小 Step，继续遵守“设计 → 实现 → 自动化测试 → 真实 MySQL / Redis / HTTP 联调 → `mvn clean package` → Git commit → 冻结”。
- 不得回改 Step 15.1 查询、Step 15.2 创建或 Step 15.3 状态切换与订单并发保护语义。
