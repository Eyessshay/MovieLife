# MovieLife 下一对话上下文（Stage 16.5 已冻结）

## 当前状态

- 阶段 1～15 均已完成并冻结。
- Stage 16 Step 16.1～16.5 已完成并冻结；最新冻结提交以当前仓库最新提交为准。
- Step 16.5 新增 `demo-run.ps1` 与 `tools/demo-business.ps1`，自动复现固定座位 Activity 票务闭环和停售、取消、退款、跨 Venue 核销隔离分支。
- 演示以 Step 16.4 确定性数据为唯一基线，业务变更只调用正式 HTTP 接口；MySQL 和 Redis 只用于最终事实验收。
- PowerShell 是主交付入口，Postman Collection 未作为重复契约维护。
- 自动业务演示连续两轮各 32 项检查全部通过；第二轮使用显式 `-Reset`。全量 615 项测试 0 failure、0 error、0 skipped，`mvn clean package` 为 `BUILD SUCCESS`。

## Step 16.5 冻结边界

1. 默认执行 `demo-run.ps1`；已有交易状态时必须显式使用 `demo-run.ps1 -Reset`，不得静默重置。
2. `-Reset` 只管理已记录的 Demo 应用和 Step 16.4 演示数据闭包，不影响其他进程、容器或非演示数据。
3. 验证码由正式发送接口创建，脚本只把本地 Redis 当作测试投递通道；其余业务过程全部经正式 HTTP 完成。
4. 主链路包含员工管理、场次创建、匿名发现、Seat Map、锁座、下单、支付、出票和本场馆核销。
5. 分支固定覆盖停售拒绝新锁和旧 Token 下单、停售前订单取消、恢复后支付退款，以及其他 Venue 员工核销失败。
6. 最终只读验收要求订单、票、ShowtimeSeat 和余票收敛，Redis 无 `seat:lock:*` 残留。
7. AI Summary、收藏、Review Like 和 Venue GEO 在演示前后保持不变，外部 AI 不是演示依赖。
8. 报告写入 Git 忽略的 `logs/demo/reports/`，手机号、登录 Token、锁座 Token 和票码必须脱敏，验证码不得写入。
9. Step 16.5 不新增业务接口，不修改 SQL Schema、Redis Key 语义或阶段 1～15 状态机，不包含前端、部署和 AI 个性化推荐。

## 下一阶段启动规则

- 下一步只能进入独立 Step 16.6 最终交付文档与验收清单。
- 开始前先只读检查当前分支、最新冻结提交和工作树。
- 先给出精简设计，再只推进一个小 Step；不得回改阶段 1～15 或 Step 16.1～16.5 已冻结语义。
- 每个 Step 继续遵守“设计 → 实现 → 自动化测试 → 真实 MySQL / Redis / HTTP 联调 → `mvn clean package` → Git commit → 冻结”。
