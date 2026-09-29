# Stage 16 最终交付验收记录

## 验收范围

本记录用于证明一个不了解 MovieLife 的使用者可以只依据 `movielife-server/README.md`，在受支持的 Windows、PowerShell 5.1、Java 8 与 Docker Desktop 环境中完成启动、演示、验证和清理。

冻结对象只包含 Stage 16 交付工程化，不改变阶段 1～15 的业务接口、状态机、权限来源、数据库业务结构或 MySQL/Redis 职责边界。

## 验收步骤

| 序号 | 验收动作 | 冻结命令或证据 | 结果 |
| --- | --- | --- | --- |
| 1 | 获取并核对代码 | 当前分支 `master`，验收起点提交 `809e0e0`；工作树只有本 Step 预期文档变更 | 通过 |
| 2 | 准备安全配置 | 删除两个旧的项目生成 Secret 后，一键入口重新生成两个 44 字节随机 Secret；路径受 Git 忽略，AI 保持关闭 | 通过 |
| 3 | 从无 MovieLife 运行资源状态启动 | 启动前应用、`18081`、专用容器、网络和数据卷均为 0；按 README 执行 `demo-run.ps1` | 通过 |
| 4 | 自动初始化数据 | MySQL Schema、关键索引、种子数据、动态未来演示数据和 Redis `PING` 均由脚本校验 | 通过 |
| 5 | 完整 HTTP 演示 | `[00]`～`[32]` 全部 PASS，主演示及停售、取消、退款、跨 Venue 核销隔离均成功 | 通过 |
| 6 | MySQL/Redis 最终事实 | 订单依次为 `PAID/CANCELED/REFUNDED`；主演示库存 `6:4`、分支库存 `6:6`；票为 1 USED + 1 VALID；`seat:lock:*` 为 0 | 通过 |
| 7 | Stage 16 专项 | `AiReviewSummaryConfigurationValidatorTest` 4 项 + `DemoHealthHttpIntegrationTest` 2 项 | 6/6 通过 |
| 8 | 全量测试 | `mvnw.cmd test`，覆盖阶段 1～15 原有 609 项及 Stage 16 新增 6 项 | 615 项，0 failure、0 error、0 skipped |
| 9 | 不跳过测试的构建 | `mvnw.cmd clean package`，未传入跳过或排除参数 | 615 项全部通过，`BUILD SUCCESS` |
| 10 | 停止与清理 | 先删除演示 MySQL/Redis 数据，再删除 MovieLife Compose 资源 | 应用、PID、`18081`、专用容器、网络和数据卷均为 0 |
| 11 | 凭据与文档检查 | 扫描当前跟踪文件、全部 Git 历史和工作树；检查报告完整手机号与长 Token 模式 | 无供应商凭据命中，报告命中均为 0 |
| 12 | Git 冻结提交 | 冻结提交包含本记录，提交号以 `git log -1 --oneline` 为准；不创建 Tag | 通过 |

## 关键实测证据

- 验收日期：2026-09-29，时区 `Asia/Shanghai`。
- 健康接口返回 `UP`，且组件名严格为 `application,mysql,redis`。
- `demo-status.ps1` 返回 MySQL/Redis `HEALTHY`、Application `UP`、HTTP port `LISTENING`。
- 最新自动报告未出现完整演示手机号或 24 位以上十六进制 Token/票码。
- 验收前后既有 `redis` 容器保持运行，`trendradar` 容器保持原停止状态；MovieLife 清理未改变它们。
- 最终保留 Git 忽略的本地 Secret 和脱敏报告，便于下次启动与验收追溯；它们不属于容器、数据库或临时业务 Key。

## 最终资源边界

验收清理只能删除 MovieLife 专用应用进程、演示保留 ID 数据闭包、关联临时 Redis Key，以及 Compose 项目 `movielife` 的两个容器、独立网络和两个数据卷。其他 Java 进程、Docker 容器、数据库和 Redis Key 不属于清理范围。

Git Tag 不属于本 Step 必需产物；如需版本标签，应在冻结提交完成后另行决定。
