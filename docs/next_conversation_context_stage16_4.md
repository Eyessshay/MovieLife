# MovieLife 下一对话上下文（Stage 16 Step 16.4 已冻结）

## 当前状态

- 阶段 1～15 均已完成并冻结。
- Stage 16 方向为“交付与演示工程化”，不新增业务能力。
- Step 16.1～16.4 已完成并冻结，最终冻结提交以当前仓库最新提交为准。
- 当前权威交付文件：
  - `sql/movielife.sql`
  - `infrastructure/compose.yml`
  - `tools/infrastructure.ps1`
  - `demo-up.ps1` / `demo-status.ps1` / `demo-down.ps1`
  - `demo/sql/install.sql` / `check.sql` / `reset.sql`
  - `tools/demo-data.ps1`
  - `demo-data-install.ps1` / `demo-data-check.ps1` / `demo-data-reset.ps1`
  - `demo/README.md`
  - `docs/MovieLife_项目技术设计.md`

## Step 16.4 冻结内容

1. 演示数据与正式 Schema/Migration 分离，固定主键保留段为 `1604000000..1604999999`，名称使用 `[DEMO-16.4]` 标识。
2. 固定提供普通购票用户、目标场馆有效员工、其他场馆员工和停用员工；`tb_venue_staff` 仍是唯一权限事实。
3. 固定提供两家 Venue、目标 Venue 两个 Hall、每厅 6 个有效 Seat、一个在售 Activity；一厅有未来在售固定座位场次，另一厅无未来排期并可由管理接口创建。
4. 预置场次每次安装更新为当前日期后第 2 天 19:30～21:30，6 行 ShowtimeSeat 全部可售，`total_seats = available_seats = 6`。
5. 可选展示数据包含 Movie、3 条 Review、已发布 AI Review Summary 与 SUCCEEDED Task；Demo 的 AI Worker 仍默认关闭，查询从 MySQL 事实读取并可回填缓存。
6. 不预造订单、支付、票码、核销或 Redis 锁座；安装发现演示 Venue 已有订单时拒绝覆盖，必须先安全重置。
7. `demo-up.ps1` 在启动应用前自动安装并检查数据；独立入口为 `demo-data-install.ps1`、`demo-data-check.ps1` 和 `demo-data-reset.ps1 -Force`。
8. 重置要求应用停止和显式 `-Force`，MySQL 保持外键开启并按依赖逆序删除演示闭包，不使用 DROP、TRUNCATE 或全库清理。
9. Redis 按演示用户/场次归属清理登录态和锁座，固定缓存 Key 按 ID 删除，共享 GEO Key 只 ZREM 演示 Venue 成员；未知 Key 保留。
10. AI Summary 持久化测试的两处全局计数已收窄为测试目标计数，使冻结测试与合法 Demo 摘要共存，不改变生产语义。

## 冻结验收

- 连续两次安装及检查成功，动态场次始终在未来，创建厅无冲突。
- 真实 HTTP 验证目标员工管理查询为 200，普通用户、其他 Venue 员工和停用员工均为 403。
- 真实 HTTP Showtime、Seat Map、AI Summary 与普通用户锁座成功；重置后相关登录 Token、座位锁和 Token Key 均消失。
- 运行中重置和省略 `-Force` 均明确拒绝；区间外 MySQL/Redis 哨兵在重置后保持不变，原始用户 1～3 仍完整。
- 全量自动化测试 615 项全部通过，0 failure、0 error、0 skipped；不跳过测试的 `mvn clean package` 为 `BUILD SUCCESS`。
- 验收临时哨兵、应用进程、MovieLife 容器、网络和数据卷均已清理。

## 下一步边界

- 下一步只能开始独立 Step 16.5“自动化业务演示”。
- 开始前先只读检查当前分支、最新冻结提交和工作树。
- 不得回改阶段 1～15、16.1～16.4 已冻结语义，除非发现可复现缺陷并单独说明。
- 不得提前实现最终交付文档、前端、云部署或 AI 个性化推荐。
