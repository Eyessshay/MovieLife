# MovieLife Server

## 项目定位与完成范围

MovieLife 是一个面向本地文娱场景的 Java 后端项目，重点展示固定座位 Activity 的完整票务与场馆运营能力，而不是只有电影信息的 CRUD 示例。

当前阶段 1～16 已完成并冻结。项目覆盖用户登录、电影与活动发现、场馆 GEO、评价与点赞、收藏与 Feed、Redis 临时锁座、订单、支付、出票、退款、核销、AI Review Summary，以及 Venue Staff 固定座位 Activity 场次管理。Stage 16 提供可复现基础设施、应用生命周期、确定性演示数据和自动 HTTP 演示。

> 本仓库当前只交付后端 API，没有网页首页。访问 `http://127.0.0.1:18081/` 返回 404 Whitelabel 页面是正常现象；请使用自动演示、API 或 `/actuator/health`。

## 1. 架构与职责边界

```text
PowerShell 交付入口
        |
        v
Spring Boot 2.7.18 / Java 8
   |                    |
   v                    v
MySQL 8.0.17         Redis 7.2.5
最终业务事实          临时状态与加速数据
```

- MySQL 是用户、内容、场馆、员工权限、场次、场次座位、订单、票券、评价聚合和 AI Summary 的最终事实来源。
- Redis 保存登录态、验证码与限流、临时座位锁、锁座 Token、Feed、点赞关系、缓存和 Venue GEO 等临时或加速数据。
- Redis 临时锁座不等于 MySQL 已售座位；只有订单事务成功后，ShowtimeSeat、订单与余票才形成最终事实。
- `lockToken` 只能消费一次。取消、超时和退款通过数据库事务恢复座位与余票，Redis 清理在事务提交后按所有权条件执行。
- Venue Staff 权限唯一来源是 `tb_venue_staff(user_id, venue_id, status=1)`，不依赖客户端角色声明或 Redis 管理状态。
- AI Review Summary 默认关闭；关闭或未配置供应商时不影响核心票务演示。

权威数据库结构为 `sql/movielife.sql`，完整 API 契约见 `docs/API接口设计.md`，详细设计见 `docs/MovieLife_项目技术设计.md`。

## 2. 支持环境

- Windows 10/11
- Windows PowerShell 5.1 或更高版本
- Docker Desktop，包含 Docker Compose v2
- JDK 8，且 `java.exe` 可从当前终端访问
- Git
- 网络可访问 Maven Central（首次构建需要下载依赖）
- 建议至少保留 4 GB 可用内存和 3 GB 可用磁盘空间

冻结运行版本为 Spring Boot 2.7.18、MySQL 8.0.17、Redis 7.2.5，时区为 `Asia/Shanghai`。项目自带 Maven Wrapper，不要求全局安装 Maven。其他操作系统或依赖大版本不属于最终验收范围。

快速检查：

```powershell
java -version
docker version
docker compose version
git --version
```

`java -version` 必须显示 Java 8。启动前请确保 Docker Desktop 已完成启动。

## 3. 五分钟启动与自动演示

### 3.1 获取代码

```powershell
git clone <repository-url> MovieLife
Set-Location .\MovieLife
```

如果代码已经在本机，只需进入仓库根目录。下文所有交付脚本均从仓库根目录运行。

### 3.2 一条命令完成启动和演示

常规 PowerShell：

```powershell
.\demo-run.ps1
```

若系统禁止运行脚本，使用不会永久修改执行策略的入口：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\demo-run.ps1
```

该入口会自动完成：

1. 检查 Java 8、Docker 和端口。
2. 创建随机本地 MySQL Secret。
3. 启动专用 MySQL 8.0.17 与 Redis 7.2.5。
4. 首次导入 `sql/movielife.sql`。
5. 安装并核验确定性演示数据。
6. 使用 Maven Wrapper 构建应用。
7. 以 `demo` Profile 启动并轮询真实健康状态。
8. 自动跑通主演示及停售、取消、退款、跨场馆权限分支。
9. 核验 MySQL 最终事实、Redis 锁座清理和其他模块隔离。
10. 保存脱敏报告到 `logs/demo/reports/latest.md`。

首次下载 Maven 依赖时可能超过五分钟，后续使用本地缓存会更快。自动演示完成后，终端应显示第 `[32]` 项通过以及 `MovieLife automated business demo completed.`。

已经运行过演示时，必须显式恢复确定性基线：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\demo-run.ps1 -Reset
```

### 3.3 查看运行状态

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\demo-status.ps1
```

成功状态应为 MySQL/Redis `HEALTHY`、Application `UP`、HTTP port `LISTENING`。

- 应用地址：`http://127.0.0.1:18081`
- 健康检查：`http://127.0.0.1:18081/actuator/health`
- 最新脱敏报告：`logs/demo/reports/latest.md`
- 应用日志：`logs/demo/`

健康接口只暴露 `application`、`mysql`、`redis` 状态，不返回连接串、用户名、Token 或异常堆栈。

## 4. 演示账号与确定性数据

演示用户没有固定密码。验证码由正式 `/api/user/code` 接口生成，自动脚本把本地 Redis 当作模拟短信投递通道并自动登录，不需要人工复制验证码或 Token。

| 角色或资源 | 固定标识 | 演示用途 |
| --- | --- | --- |
| 普通购票用户 | 用户 `1604000001`，手机号 `13916040001` | 锁座、下单、支付、取消、退款 |
| 目标场馆有效员工 | 用户 `1604000002`，手机号 `13916040002` | 管理 Venue `1604000101`、核销票券 |
| 其他场馆员工 | 用户 `1604000003`，手机号 `13916040003` | 验证跨 Venue 权限隔离 |
| 目标场馆停用员工 | 用户 `1604000004`，手机号 `13916040004` | 验证停用员工不可管理 |
| 目标场馆 | Venue `1604000101` | 固定座位 Activity 运营 |
| 创建场次厅 | Hall `1604000302`，6 个 Seat | 真实创建场次与票务演示 |
| 在售活动 | Activity `1604000501` | 匿名发现与场次创建 |
| 摘要电影 | Movie `1604000601` | AI Summary 默认关闭时的静态展示事实 |

数据主键位于保留区间 `1604000000..1604999999`，场次时间按当前日期动态生成。数据集不预造已支付订单、票码或锁座结果；这些事实全部由正式 HTTP 链路产生。详细数据边界见 `demo/README.md`。

## 5. 自动演示覆盖范围

主演示：

```text
场馆员工登录 -> 管理列表 -> 创建固定座位 Activity 场次
-> 匿名发现 -> Seat Map -> 用户登录与锁座 -> 创建订单
-> 支付 -> 出票 -> 本场馆员工核销
```

同时验证：

- 临时锁座后停售。
- 停售后拒绝新锁座。
- 停售后拒绝旧 `lockToken` 创建订单。
- 停售前成立的订单仍可取消。
- 恢复后重新锁座、下单、支付和退款。
- 其他 Venue 员工核销失败。
- 最终订单、票、ShowtimeSeat 与余票收敛。
- Redis 不残留 `seat:lock:*`。
- AI Summary、收藏、Review Like 和 Venue GEO 不被票务演示改变。

详细步骤与报告规则见 `demo/AUTOMATED_DEMO.md`。

## 6. 配置说明

一键演示不需要创建 `.env`。基础设施脚本在 `.movielife-runtime/` 生成随机 MySQL Secret，并只向应用子进程注入；目录已被 Git 忽略。

`movielife-server/.env.example` 是手工启动时的配置契约，不包含可用凭据。Spring Boot 不自动加载 `.env`，需要通过当前 PowerShell、IDE 或受控部署环境注入变量。

| 配置项 | 默认值/条件 | 说明 |
| --- | --- | --- |
| `MYSQL_PASSWORD` | 无默认值，手工启动必填 | MySQL 密码，秘密值 |
| `MYSQL_URL` | 本机 `3306/movielife` | 禁止在连接串内嵌用户名或密码 |
| `MYSQL_USERNAME` | `root` | 一键 Demo 使用受限用户 `movielife` |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DATABASE` | `localhost` / `6379` / `0` | Redis 连接配置 |
| `SPRING_REDIS_PASSWORD` | 未设置 | 仅 Redis 启用认证时提供，秘密值 |
| `SERVER_PORT` | `8081` | Demo Profile 固定监听 `127.0.0.1:18081` |
| `AUTH_LOG_CODE_ENABLED` | `false` | 仅受控本地调试可临时开启 |
| `STARTUP_CHECK_ENABLED` | `true` | 必须保留真实 MySQL 与 Redis 启动检查 |
| `AI_REVIEW_SUMMARY_ENABLED` | `false` | 默认关闭，不影响票务链路 |
| `AI_REVIEW_SUMMARY_ENDPOINT` | AI 开启时必填 | HTTPS 供应商接口 |
| `AI_REVIEW_SUMMARY_API_KEY` | AI 开启时必填 | 供应商密钥，秘密值 |
| `AI_REVIEW_SUMMARY_MODEL` | AI 开启时必填 | 供应商模型名 |

AI 开启但三项供应商配置不完整时，应用会明确拒绝启动，且错误只列变量名，不输出配置值。完整调优变量、默认值和缺失行为见 `.env.example` 与技术设计文档的 Stage 16.1 章节。

## 7. 测试与构建

测试需要健康的 MovieLife MySQL/Redis，且 Demo 应用必须停止以避免占用 Jar。先从仓库根目录执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\demo-down.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\infrastructure.ps1 start

$env:MYSQL_URL = 'jdbc:mysql://127.0.0.1:13306/movielife?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
$env:MYSQL_USERNAME = 'movielife'
$env:MYSQL_PASSWORD = [System.IO.File]::ReadAllText((Resolve-Path '.\.movielife-runtime\mysql-app-password.txt')).Trim()
$env:REDIS_HOST = '127.0.0.1'
$env:REDIS_PORT = '16379'
$env:REDIS_DATABASE = '0'
$env:AI_REVIEW_SUMMARY_ENABLED = 'false'
Set-Location .\movielife-server
```

Stage 16 专项 6 项：

```powershell
.\mvnw.cmd '-Dtest=AiReviewSummaryConfigurationValidatorTest,DemoHealthHttpIntegrationTest' test
```

全量测试：

```powershell
.\mvnw.cmd test
```

不跳过测试的最终构建：

```powershell
.\mvnw.cmd clean package
```

冻结基线为 615 项测试全部通过，其中包含阶段 1～15 原有 609 项与 Stage 16 新增 6 项；不得使用 `-DskipTests` 代替最终验收。

## 8. 停止、重置与彻底清理

以下命令需要在仓库根目录运行。

普通停止，保留演示数据和数据卷：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\demo-down.ps1
```

只安全重置演示数据时，应用必须停止且基础设施保持健康：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\infrastructure.ps1 start
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\demo-data-reset.ps1 -Force
```

最后删除 MovieLife 专用容器、网络和数据卷：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\infrastructure.ps1 reset -Force
```

完整清理顺序为：停止应用 → 启动专用基础设施以安全删除演示数据 → 删除 MovieLife Compose 资源。它只管理 `movielife-mysql`、`movielife-redis`、`movielife-network`、`movielife-mysql-data` 和 `movielife-redis-data`，不会清理其他容器或数据库。生成的本地 Secret 与脱敏报告默认保留在 Git 忽略目录中，可供下次运行和验收追溯。

`stop` 与 `reset -Force` 不同：前者保留数据卷，后者删除 MovieLife 专用数据卷并使下次启动重新导入权威 SQL。

## 9. 常见故障排查

### PowerShell 提示“系统上禁止运行脚本”

不要修改全局执行策略，使用：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\demo-run.ps1
```

`-NoProfile` 也能避开个人 `profile.ps1` 加载失败。

### 浏览器出现 Whitelabel Error Page / 404

根路径没有网页映射，这是后端项目的正常行为。访问 `/actuator/health`，或查看终端自动演示结果和 `logs/demo/reports/latest.md`。

### Docker unavailable / Docker Engine unavailable

启动 Docker Desktop，等待 Engine 就绪，然后确认 `docker info` 与 `docker compose version` 成功。

### Java 版本不正确

脚本只接受 Java 8。调整 `PATH` 或 `JAVA_HOME` 后重新打开终端，并确认 `java -version`。

### 端口 18081、13306 或 16379 被占用

关闭占用端口的进程，或确认是否有上一次未通过 MovieLife 脚本管理的服务。脚本不会停止身份不匹配的外部进程。

### 提示演示数据不在确定性基线

使用显式安全重置：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\demo-run.ps1 -Reset
```

### Maven clean 无法删除 Jar

Demo 应用仍在使用 Jar。先运行 `demo-down.ps1`，再启动基础设施并执行测试或构建。

### 健康检查不是 UP

依次运行 `demo-status.ps1`、`tools/infrastructure.ps1 status` 和 `tools/infrastructure.ps1 verify`，然后查看 `logs/demo/application.log`、`stdout.log`、`stderr.log`。不要把包含秘密值的完整日志发布到公共渠道。

### MySQL 初始化内容与冻结 Schema 不一致

确认没有需要保留的 MovieLife 数据后，执行 `tools/infrastructure.ps1 reset -Force`，再重新运行 `demo-run.ps1`。初始化 SQL 只在新数据卷第一次创建时执行。

## 10. 安全注意事项

- 不提交 `.env`、`.movielife-runtime/`、日志、真实密码、验证码、Token、票码或供应商 API Key。
- 不把密码写入 JDBC URL、Compose 文件、命令示例或异常信息。
- Demo 服务和数据库端口只绑定 `127.0.0.1`，不能直接作为公网部署方案。
- `AUTH_LOG_CODE_ENABLED` 默认必须为 `false`。
- `STARTUP_CHECK_ENABLED=false` 只允许不依赖基础设施的隔离测试，不能用来伪造可用状态。
- 来源不明或不属于本人账号的 Key 一律视为已泄露且不可用，不尝试操作他人账号。
- 历史凭据扫描发现问题时先撤销凭据；未经确认不得重写 Git 历史或强制推送。
- 自动报告只保存脱敏手机号、Token、锁座 Token 和票码，验证码不进入报告。

## 11. 阶段冻结状态

| 阶段 | 已冻结能力 |
| --- | --- |
| 1～5 | 基础工程、用户、电影/活动、场馆、评价、收藏与社区能力 |
| 6～7 | 场次与 Seat Map 查询、Redis 原子临时锁座与释放 |
| 8～10 | 订单、支付、超时、取消、出票、核销和退款状态机 |
| 11～14 | Activity Review、AI Review Summary、恢复与跨链路收敛 |
| 15 | Venue Staff 固定座位 Activity 场次管理与票务交叉验收 |
| 16 | 安全配置、可复现基础设施、生命周期、确定性数据、自动演示和最终交付验收 |

冻结意味着后续阶段不得顺带修改既有 URL、错误码、状态机、权限来源、数据库业务结构或 MySQL/Redis 职责边界。

## 12. 暂不支持的能力

- Web、移动端或管理后台界面。
- AI 个性化推荐、向量召回或推荐排序。
- 真实短信、真实支付网关与支付回调验签。
- Activity 主数据管理、非固定座位票务。
- 场次改期、改厅、订单迁移、强制退票或座位模板同步。
- Venue GEO 自动同步。
- Kubernetes、云部署、高可用集群、生产级监控告警。
- macOS/Linux 的冻结交付保证。

Stage 17 尚未启动。是否增加 AI 个性化推荐或其他能力，应在 Stage 16 完整冻结后单独设计和评估。
