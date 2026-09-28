# MovieLife 下一对话上下文（Stage 16 Step 16.2 已冻结）

## 当前状态

- 阶段 1～15 均已完成并冻结。
- Stage 16 方向为“交付与演示工程化”，不新增业务能力。
- Step 16.1“交付契约与凭据安全”和 Step 16.2“可复现基础设施”已完成并冻结，最终冻结提交以当前仓库最新提交为准。
- 当前权威文件：
  - `sql/movielife.sql`
  - `infrastructure/compose.yml`
  - `tools/infrastructure.ps1`
  - `movielife-server/README.md`
  - `docs/MovieLife_项目技术设计.md`
  - `docs/next_conversation_context_stage16_2.md`

## Step 16.2 冻结内容

1. Compose 项目、容器、网络和数据卷均使用固定 `movielife` 专属名称，不发现、不连接、不清理开发者已有数据库或 Redis。
2. MySQL 固定为 `mysql:8.0.17`，Redis 固定为 `redis:7.2.5-alpine`，不使用 `latest` 或浮动大版本标签。
3. 宿主端口固定为回环地址 MySQL `13306`、Redis `16379`，容器端口仍为 `3306`、`6379`。
4. MySQL 空数据卷首次启动只读执行权威 `sql/movielife.sql`；已有数据卷启动不重复初始化，不为容器修改冻结 SQL。
5. MySQL Root 和应用用户密码首次启动随机生成，只写入 Git 忽略的 `.movielife-runtime/` 并以 Compose Secret 挂载；配置、文档和日志不包含密码。
6. MySQL 开启 `log_bin_trust_function_creators=1` 以支持既有事务测试用非管理员用户临时创建 Trigger，不向应用用户授予 `SUPER` 权限。
7. MySQL、Redis 均有健康检查；`start` 等待健康后自动验证 20 张表、关键索引、稳定种子数据和 Redis `PONG`。
8. `start` 可重复执行；`stop` 保留容器、网络、Secret 和数据卷；再次 `start` 保留数据。
9. `reset` 必须附带 `-Force`，只删除 MovieLife Compose 项目的容器、网络和数据卷，保留本地 Secret，不影响其他 Docker 资源。

## 冻结验收

- 从无 MovieLife 容器、网络和数据卷状态首次启动成功，20 张表、关键索引、种子数据和 Redis `PONG` 校验通过。
- 重复启动幂等；普通停止再启动后 MySQL `server_uuid` 与 Redis 持久化标记均保留。
- 无 `-Force` 的重置被拒绝；定向重置后 MovieLife 资源全部删除，既有 `redis`、`trendradar` 容器状态未改变。
- 专用非管理员 MySQL 用户可运行既有 Trigger 事务测试；全量 613 项测试全部通过，0 failure、0 error、0 skipped，`mvn clean package` 为 `BUILD SUCCESS`。
- 打包产物连接 Compose 基础设施启动成功，真实电影列表请求返回 HTTP 200、业务码 `OK`。
- Git 跟踪文件、历史和工作树的供应商凭据扫描通过；验收应用进程、临时 Redis 标记、MovieLife 容器、网络和数据卷已清理。

## 固定命令

```powershell
.\tools\infrastructure.ps1 start
.\tools\infrastructure.ps1 status
.\tools\infrastructure.ps1 verify
.\tools\infrastructure.ps1 stop
.\tools\infrastructure.ps1 reset -Force
```

## 下一步边界

- 下一步只能开始独立 Step 16.3“应用生命周期工程化”。
- Step 16.3 开始前先只读检查当前分支、最新冻结提交和工作树。
- 不得回改 Step 16.2 的镜像、端口、资源命名、SQL 初始化、凭据、持久化或定向清理契约，除非发现可复现缺陷并单独说明。
- 不得提前实现确定性演示数据、自动业务演示、Postman Collection、前端、云部署或 AI 个性化推荐。
