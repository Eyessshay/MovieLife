# MovieLife

MovieLife 是一个面向本地文娱场景的 Java 后端项目。项目以固定座位活动为例，串起场馆员工创建场次、用户选座购票和现场核销的完整流程。仓库提供 HTTP API 和可重复运行的自动演示。

## 功能

- **内容发现**：浏览电影、活动和附近场馆，支持评价、点赞、收藏和动态 Feed。
- **选座票务**：查看座位图、临时锁座、下单、支付、出票和核销，覆盖取消、超时与退款后的座位释放。
- **场馆运营**：场馆员工可管理所属场馆的固定座位场次。自动演示覆盖停售、跨场馆权限和停用员工限制。
- **评价摘要**：可选的 AI 评价摘要默认关闭，运行核心票务演示无需配置 AI 服务。

## 技术栈

使用 Java 8、Spring Boot 2.7.18、MySQL 8.0.17 和 Redis 7.2.5。MySQL 保存场次、座位、订单和票券的最终状态，Redis 管理登录态、临时锁座、缓存和场馆地理索引。

## 快速开始

准备 Windows 10/11、PowerShell 5.1 或更高版本、JDK 8、Git 和已启动的 Docker Desktop（含 Docker Compose v2）。项目自带 Maven Wrapper，首次构建需要访问 Maven Central。

```powershell
git clone https://github.com/Eyessshay/MovieLife.git
Set-Location .\MovieLife
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\demo-run.ps1
```

脚本会启动 MySQL 和 Redis、安装演示数据、构建并启动应用，然后通过 HTTP API 执行场次创建、选座购票和核销等流程。

- 健康检查：`http://127.0.0.1:18081/actuator/health`
- 演示报告：`logs/demo/reports/latest.md`
- 再次演示：运行同一命令并附加 `-Reset`，恢复演示数据基线。

运行 `demo-status.ps1` 查看状态，运行 `demo-down.ps1` 停止演示。应用入口及脚本参数见 [后端运行指南](movielife-server/README.md)。

## 项目文档

- [API 接口设计](docs/API接口设计.md)
- [项目技术设计](docs/MovieLife_项目技术设计.md)
- [项目功能实现学习指南](docs/项目功能实现学习指南.md)
- [数据库结构](sql/movielife.sql)
- [演示数据说明](demo/README.md)
