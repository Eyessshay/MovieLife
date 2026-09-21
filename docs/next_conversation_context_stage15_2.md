# MovieLife 下一对话上下文（Step 15.2 已冻结）

## 当前状态

- 阶段 1～14 已完成并冻结。
- Stage 15 方向固定为“Venue Staff 固定座位 Activity 场次运营”。
- Step 15.1“管理权限与场次查询基线”和 Step 15.2“创建固定座位 Activity 场次”均已完成并冻结；Stage 15 尚未整体完成。
- 当前新增写入口：`POST /api/admin/venues/{venueId}/activity-showtimes`。
- Step 15.2 新增 17 项专项测试，全量 586 项测试全部通过，0 failure、0 error、0 skipped；不跳过测试的 `mvn clean package` 为 `BUILD SUCCESS`。
- 真实联调使用隔离 MySQL 8.0.17、Redis 7 和随机 HTTP 端口；增量迁移连续执行两次成功，测试数据和 Redis 已清理，Redis 最终 `DBSIZE=0`。

## Step 15.2 冻结边界

1. 创建权限只认 `tb_venue_staff(user_id, venue_id, status=1)`；普通登录、跨场馆员工或停用员工不得创建。
2. 只创建路径 Venue 下的固定座位 `ACTIVITY` 场次；Venue 必须启用、Activity 必须 `status=1`、Hall 必须属于路径 Venue。
3. 服务端固定 `biz_type=ACTIVITY`、`status=1`；开始时间必须晚于当前时间，结束时间必须晚于开始时间，首版所有座位统一使用 `basePrice`。
4. 同一事务内锁定 Hall 行，以当前读检测 `start_time < newEnd AND end_time > newStart`，命中相同 `requestId` 时执行完整幂等匹配；停售场次仍参与冲突判断。
5. 有效物理 Seat 一次读取，空厅拒绝；Showtime 插入必须影响 1 行，ShowtimeSeat 必须一次批量插入且影响行数等于 Seat 数，否则整体回滚。
6. 创建后 `total_seats=available_seats=有效物理座位数`，并与初始化的 `tb_showtime_seat` 数量一致。
7. `tb_showtime(venue_id, create_request_id)` 是创建幂等约束；规范化参数一致返回原场次，不一致返回 `SHOWTIME_CREATE_REQUEST_MISMATCH`。
8. 创建链路不读写 Redis 业务状态、不创建临时锁，不触发 Review AI Summary，也不改变阶段 1～14 的锁座、订单、支付、退款、出票和核销语义。
9. 本 Step 不包含既有场次修改、停售、恢复、改期、改厅、强制退票、订单迁移、座位模板同步、GEO 同步或非固定座位票务。

## 下一 Step 启动规则

- 先只读检查当前分支、冻结提交和工作树，确认干净后再设计。
- 一次只推进一个明确小 Step，继续遵守“设计 → 实现 → 自动化测试 → 真实 MySQL / Redis / HTTP 联调 → `mvn clean package` → Git commit → 冻结”。
- 不得回改 Step 15.1 查询语义或 Step 15.2 创建、幂等、Hall 锁、冲突和事务边界。
