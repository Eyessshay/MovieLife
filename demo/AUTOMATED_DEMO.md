# Stage 16.5 自动化业务演示

本演示以 Step 16.4 的确定性数据为基线，通过正式 HTTP 接口复现固定座位 Activity
票务闭环。业务过程不直接写 MySQL 或 Redis；数据库和 Redis 只在最后用于核验事实收敛。
AI 外部调用保持关闭，不是演示依赖。

## 运行方式

在仓库根目录执行：

```powershell
.\demo-run.ps1
```

首次运行会检查环境、启动专用 MySQL/Redis、安装确定性数据、构建并启动应用，然后执行
业务演示。若此前已经运行过演示，使用显式安全重置获得相同基线：

```powershell
.\demo-run.ps1 -Reset
```

`-Reset` 只停止脚本记录的 Demo 应用，清理 MovieLife 演示数据闭包，再重新启动应用；
不会清空整个数据库，也不会操作其他容器或非演示数据。未显式指定 `-Reset` 且基线已经
发生交易时，入口会拒绝继续并提示正确命令。

## 覆盖链路

主演示依次完成：目标场馆员工登录、管理列表、创建场次、匿名发现、Seat Map、购票用户
登录与锁座、下单、支付、出票以及目标场馆员工核销。

分支演示同时验证：临时锁座后停售、停售后拒绝新锁座、旧 `lockToken` 不能创建订单、
停售前成立的订单仍可取消、恢复销售后重新下单支付并退款，以及其他 Venue 员工核销失败。
验证码由正式发送接口生成，脚本只从本地 Redis 读取这个验证码作为本地短信投递通道；
Token、订单 ID、锁座 Token 和票码均由前一步结果自动传递，无需人工复制。

## 验收与报告

每一步在终端输出简短业务结果。失败信息包含具体环节、HTTP 状态、业务错误码和受控错误
消息。成功或失败均生成 Markdown 报告：

- `logs/demo/reports/demo-report-yyyyMMdd-HHmmss.md`
- `logs/demo/reports/latest.md`

报告中的手机号、登录 Token、锁座 Token 和票码均脱敏，验证码不写入报告。最终只读核验
订单、票、ShowtimeSeat、余票和演示隔离事实，并确认 Redis 不残留 `seat:lock:*` Key；
同时比较演示前后的 AI Summary、收藏、Review Like 和 Venue GEO HTTP 结果。

PowerShell 是冻结的主交付入口。Postman Collection 属于可选产物，本 Step 未额外维护，
避免形成第二套可能漂移的演示契约。
