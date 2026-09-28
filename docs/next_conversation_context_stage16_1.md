# MovieLife 下一对话上下文（Stage 16 Step 16.1 已冻结）

## 当前状态

- 阶段 1～15 均已完成并冻结。
- Stage 16 方向为“交付与演示工程化”，不新增业务能力。
- Step 16.1“交付契约与凭据安全”已完成并冻结，最终冻结提交以当前仓库最新提交为准。
- 当前权威文件：
  - `sql/movielife.sql`
  - `docs/API接口设计.md`（v0.37）
  - `docs/MovieLife_项目技术设计.md`
  - `movielife-server/README.md`
  - `movielife-server/.env.example`
  - `docs/next_conversation_context_stage16_1.md`

## Step 16.1 冻结内容

1. 冻结验收环境为 Windows 10/11、PowerShell 5.1+、Java 8、Spring Boot 2.7.18、项目 Maven Wrapper、MySQL 8.0.17、Redis 7 和 `Asia/Shanghai` 时区。
2. 环境变量清单已记录必填条件、默认值、敏感级别、安全示例和缺失行为。
3. `SERVER_PORT` 支持环境变量覆盖，默认仍为 8081；Redis 密码只通过 Spring Boot 原生 `SPRING_REDIS_PASSWORD` 可选注入，未设置时不发送 Redis AUTH。
4. `.env.example` 只包含不可用占位值；真实 `.env` 继续被 Git 忽略，Spring Boot 不承诺自动加载 `.env`。
5. AI Review Summary 默认关闭；关闭时不要求 Endpoint、API Key 或模型名，不影响阶段 1～15 的核心业务。
6. AI 显式开启时，Endpoint 必须是带主机名的 HTTP/HTTPS 绝对地址，API Key 与模型名必须非空；配置不完整时应用启动失败，错误只列变量名，不输出配置值。
7. `tools/security/scan-secrets.ps1` 扫描当前工作树、Git 跟踪内容和全部可达提交，只报告文件路径或提交标识，不打印秘密内容。
8. 原本地 `.env` 中的供应商 Key 不属于用户账号，无法也不得代替未知所有者撤销；它已从本地删除，并被永久视为来源不明、已泄露、不可继续使用。Git 当前内容与全部历史从未包含该 Key，无需重写历史。
9. 本 Step 不要求创建新供应商 Key；未来只有在用户主动启用 AI 时，才使用本人账号创建的新 Key，并仅保存在未跟踪配置或安全凭据存储中。
10. 本 Step 不修改阶段 14 AI Summary 状态机、任务、重试、Stream、缓存和公开查询语义，也不修改阶段 1～15 的业务接口或数据库结构。

## 验收结果

- 新增配置校验测试 4 项，与既有真实模型 HTTP Adapter 测试合计 9 项全部通过。
- 全量 613 项测试全部通过，0 failure、0 error、0 skipped。
- 不跳过测试的 `mvn clean package` 为 `BUILD SUCCESS`。
- 隔离 MySQL 8.0.17、Redis 7 和随机 HTTP 端口下，AI关闭时应用通过真实基础设施启动检查，公开 Movie HTTP 请求返回 200。
- AI开启但缺少 Endpoint、API Key 和模型名时，完整应用明确拒绝启动且日志未出现供应商 Key。
- 测试结束时 Redis 只存在既有 Venue GEO 初始化的两个 Key；隔离数据、应用进程和容器均已删除，未操作本机既有 Redis 容器。

## 下一步边界

- 下一步只能开始 Step 16.2“可复现基础设施”。
- Step 16.2 应先冻结 Docker Compose、版本、端口、网络、健康检查、SQL 初始化、数据持久化和安全清理契约，再实现 MySQL 8.0.17 与 Redis 7 编排。
- 不得在 Step 16.2 提前实现应用启停脚本、确定性演示数据、自动业务演示、Postman Collection、前端或 AI 个性化推荐。
- 每个 Step 继续执行“设计 → 实现 → 自动化测试 → 真实 MySQL/Redis/HTTP 联调 → `mvn clean package` → Git commit → 冻结”。
