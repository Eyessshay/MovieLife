# MovieLife 下一对话上下文（Stage 15 已冻结）

## 当前状态

- 阶段 1～15 均已完成并冻结。
- Stage 15 方向为“Venue Staff 固定座位 Activity 场次运营”，包含管理查询、原子创建、停售/恢复和跨链路验收。
- Stage 15 最终冻结提交以当前仓库最新提交为准。
- 当前权威文件：
  - `sql/movielife.sql`
  - `docs/API接口设计.md`（v0.37）
  - `docs/MovieLife_项目技术设计.md`
  - `docs/next_conversation_context_stage15.md`
- Step 15.4 新增 4 项真实 HTTP 验收测试；Stage 15 与订单交叉专项 101 项、全量 609 项全部通过，0 failure、0 error、0 skipped；不跳过测试的 `mvn clean package` 为 `BUILD SUCCESS`。
- 真实验收使用隔离 MySQL 8.0.17、Redis 7 和随机 HTTP 端口；测试数据、锁座 Key、服务与容器已清理，Redis 最终 `DBSIZE=0`。

## Stage 15 冻结边界

1. 管理权限唯一事实为 `tb_venue_staff(user_id, venue_id, status=1)`；普通用户、停用员工和其他 Venue 员工不能管理目标场馆。
2. 管理范围只支持路径 Venue 下的固定座位 `ACTIVITY`，不支持 Movie、非固定座位或 Activity 主数据管理。
3. 管理查询可见待售、停售和历史场次，固定按 `start_time DESC, id DESC` 稳定分页，并批量装载 Activity/Hall。
4. 创建由服务端固定 `ACTIVITY + status=1`；事务先锁 Hall，再检查全部状态的重叠排期，一次读取有效 Seat，原子创建 Showtime 和批量 ShowtimeSeat。
5. 创建后必须满足 `total_seats = available_seats = 有效 Seat 数 = ShowtimeSeat 行数`；第一版全部座位价格等于 `basePrice`。
6. 创建幂等唯一约束为 `(venue_id, create_request_id)`；规范化参数一致返回原场次，不一致返回 `SHOWTIME_CREATE_REQUEST_MISMATCH`，并发相同 requestId 不重复创建场次或座位。
7. 只允许未来场次执行 `ON_SALE(1) ↔ STOPPED(0)`；重复请求幂等，`status=2` 不由管理接口人工操作。恢复时重新校验 Venue、Activity、Hall 和完整库存事实。
8. 停售不删除 ShowtimeSeat、不重置余票、不释放占座、不取消订单、不退款、不删除 Redis 锁；旧 lockToken 自然过期，停售后不能用它创建新订单。
9. 订单与停售统一先锁 Showtime。订单余票扣减仍要求 `status=1` 和完整 affectedRows；竞争结果只能是订单完整成立后停售，或订单事务完整回滚，不再存在锁升级死锁窗口。
10. 已成立订单继续使用阶段 8～10 的取消、超时、支付、退款、出票和核销状态机；Stage 15 不改变 lockToken 一次消费和 MySQL 最终事实边界。
11. 管理能力不创建 Redis 管理状态，不改变收藏、Review Like、Venue GEO 或 Review AI Summary，也不触发 AI Summary Task。
12. Stage 15 不包含改期、改厅、强制退票、订单迁移、座位模板同步、GEO 自动同步或非固定座位票务。

## 最终验收闭环

```text
场馆员工验证码登录 → 管理列表 → 创建固定座位 Activity 场次
→ 匿名查询 Activity 场次 → Seat Map → 用户锁座 → 创建订单
→ 支付 → 出票 → 本场馆员工核销
```

停售分支验证“临时锁后停售拒绝新锁和新订单”，并验证停售前已成立订单仍可取消；恢复后重新建单、支付和退款时，ShowtimeSeat 与 `available_seats` 全程收敛。

## 下一阶段启动规则

- 下一阶段尚未指定；开始前先只读检查当前分支、最新冻结提交和工作树。
- 一次只选择一个明确且独立的方向，先给出精简总体规划，再一次推进一个小 Step。
- 每个 Step 继续遵守“设计 → 实现 → 自动化测试 → 真实 MySQL / Redis / HTTP 联调 → `mvn clean package` → Git commit → 冻结”。
- 不得回改阶段 1～15 已冻结业务语义，也不得顺带扩展其他模块。
