# MovieLife Server

MovieLife 是基于 Java 8 和 Spring Boot 2.7.18 的本地文娱与固定座位票务后端。阶段 1～15 已冻结，覆盖用户、电影与活动发现、评价、收藏、Redis 临时锁座、订单、支付、出票、退款、核销，以及 Venue Staff 固定座位 Activity 场次运营。

Stage 16 只处理交付与演示工程化，不改变既有业务接口、状态机或 MySQL/Redis 职责边界。

## 支持环境

- Windows 10/11 与 PowerShell 5.1 或更高版本
- JDK 8
- 项目自带 Maven Wrapper
- MySQL 8.0.17
- Redis 7
- 默认应用端口 `8081`
- 时区 `Asia/Shanghai`

其他操作系统、MySQL 版本或 Redis 大版本目前不属于冻结验收矩阵。

## 配置安全

`.env.example` 是配置契约模板，不包含可用密码或 API Key。Spring Boot 不会自动读取 `.env`；请通过当前 PowerShell、IDE Run Configuration 或受控部署环境注入变量。

禁止提交 `.env`、真实密码、登录 Token、票码或供应商 API Key。若凭据曾出现在未跟踪文件中且归当前账号管理，应先在供应商控制台撤销再重新创建；若凭据来源不明或不属于当前账号，则视为已泄露且不可用，从本地移除并停止使用，不尝试操作他人账号或凭据。

AI Review Summary 默认关闭。核心登录、发现、锁座和票务演示不需要模型供应商配置。只有显式设置 `AI_REVIEW_SUMMARY_ENABLED=true` 时，才必须同时提供有效的 Endpoint、API Key 和模型名；缺失或非法配置会使应用安全地拒绝启动，错误信息只列出变量名，不输出配置值。

完整环境变量清单、默认值、敏感级别和缺失行为见 `docs/MovieLife_项目技术设计.md` 的 Stage 16.1 章节。

## 本地启动

先导入 `../sql/movielife.sql`，并启动 MySQL 8.0.17 与 Redis 7。然后在当前 PowerShell 会话中设置最少配置：

```powershell
$env:MYSQL_PASSWORD = '<local-mysql-password>'
$env:AI_REVIEW_SUMMARY_ENABLED = 'false'
.\mvnw.cmd spring-boot:run
```

可按需设置 `MYSQL_URL`、`MYSQL_USERNAME`、Redis连接信息和 `SERVER_PORT`。开发环境若需要从日志读取模拟验证码，可临时设置：

```powershell
$env:AUTH_LOG_CODE_ENABLED = 'true'
```

该选项只能用于受控的本地开发，演示结束后应恢复为 `false`。

## 启动与验收契约

应用启动时会查询 Movie ID 1 并执行 Redis `PING`；只有 MySQL 和 Redis 都可用时才完成启动。`STARTUP_CHECK_ENABLED=false` 只允许用于不依赖基础设施的编译或隔离测试，不能作为演示成功的替代方案。

Step 16.1 的最低验收为：

1. AI关闭时，应用连接真实 MySQL 和 Redis 后正常启动。
2. 真实 HTTP 请求能够访问既有公开接口。
3. AI开启但缺少供应商配置时，应用拒绝启动且不输出密钥。
4. Git 跟踪文件和全部历史不包含真实供应商 Key。
5. 全量测试与不跳过测试的 `mvn clean package` 成功。

一键基础设施、应用生命周期脚本、确定性演示数据和自动业务演示属于后续 Step 16.2～16.5，本 Step 不提前实现。
