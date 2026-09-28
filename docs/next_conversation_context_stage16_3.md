# MovieLife 下一对话上下文（Stage 16 Step 16.3 已冻结）

## 当前状态

- 阶段 1～15 均已完成并冻结。
- Stage 16 方向为“交付与演示工程化”，不新增业务能力。
- Step 16.1～16.3 已完成并冻结，最终冻结提交以当前仓库最新提交为准。
- 当前权威交付文件：
  - `sql/movielife.sql`
  - `infrastructure/compose.yml`
  - `tools/infrastructure.ps1`
  - `tools/demo-common.ps1`
  - `demo-up.ps1`
  - `demo-status.ps1`
  - `demo-down.ps1`
  - `movielife-server/src/main/resources/application-demo.yml`
  - `movielife-server/README.md`
  - `docs/MovieLife_项目技术设计.md`

## Step 16.3 冻结内容

1. 一键演示入口为仓库根目录 `demo-up.ps1`，负责环境检查、16.2 基础设施启动、Maven Wrapper 打包、Demo 应用启动和真实健康轮询。
2. Demo 固定绑定 `127.0.0.1:18081`，使用专用 Profile、专用 MySQL 用户和 16.2 固定端口；真实启动检查保持开启，AI 与验证码日志关闭。
3. `/actuator/health` 只返回 `application`、`mysql`、`redis` 状态，不返回详情；Actuator 发现页、环境和其他端点不开放。
4. 应用 PID 保存于 Git 忽略的 `.movielife-runtime/demo-app.pid`，日志保存于 `logs/demo/`。
5. 重复 `demo-up.ps1` 对健康的已记录进程幂等返回，不构建或创建第二个 Java 进程。
6. `demo-status.ps1` 只读报告 Docker、MySQL、Redis、应用和端口状态，不显示命令行、配置或凭据。
7. `demo-down.ps1` 只停止 PID 与 Jar 绝对路径、Demo Profile 同时匹配的进程；不依据端口或模糊进程名停止其他进程。随后普通停止 MovieLife 容器并保留数据。
8. 所有启动、健康和停止等待均按状态轮询；端口冲突、Docker 不可用、配置或产物缺失、健康超时和 PID 冲突均明确失败。

## 固定命令

```powershell
.\demo-up.ps1
.\demo-status.ps1
.\demo-down.ps1
```

## 冻结验收

- 一条命令从无 MovieLife 进程/容器状态完成基础设施、构建、应用启动和 HTTP 健康检查。
- 重复启动 PID 不变，只有一个匹配 Java 进程；停止后无 PID、Java 进程或 `18081` 端口残留。
- 端口占用、Docker 不可达和伪造外部 PID 分支均清晰、安全失败。
- 健康响应仅包含三个状态组件，隐藏 Actuator 端点为 404，应用只监听回环地址，日志不含生成密码。
- 全量 615 项测试全部通过，0 failure、0 error、0 skipped；`mvn clean package` 为 `BUILD SUCCESS`。
- 验收应用进程、MovieLife 容器、网络和数据卷已清理；本地随机 Secret 与日志按契约保留且均被 Git 忽略。

## 下一步边界

- 下一步只能开始独立 Step 16.4“确定性演示数据”。
- Step 16.4 开始前先只读检查当前分支、最新冻结提交和工作树。
- 不得回改 16.1 凭据、16.2 基础设施或 16.3 进程/健康契约，除非发现可复现缺陷并单独说明。
- 不得提前实现自动业务演示、Postman Collection、前端、云部署或 AI 个性化推荐。
