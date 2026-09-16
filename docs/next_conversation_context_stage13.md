# MovieLife 下一对话上下文（Stage 13 已冻结）

请在开始下一阶段前完整继承以下上下文，并以当前仓库的实际代码和最新设计文档为准。

## 当前状态

- 阶段 1～13 均已完成并冻结，不重新设计已经冻结的模块。
- 阶段 13 最终提交：`3a8703c test: freeze stage 13 activity review`。
- 当前权威文件：
  - `sql/movielife.sql`
  - `docs/API接口设计.md`
  - `docs/MovieLife_项目技术设计.md`
  - `docs/handoff_updated_stage13.docx`
- 阶段 13 验证证据：定向测试 19 项、阶段 11～12 交叉测试 20 项、全量 486 项全部通过；真实 MySQL、Redis、随机端口 HTTP 联调通过；`mvn clean package` 为 `BUILD SUCCESS`；测试数据、Review Like 和锁座 Key 无残留。

## 必须保持的票务边界

1. Redis `TEMP_LOCKED` 只表示创建订单前的临时资格；订单后的最终事实是 MySQL 中的 `TicketOrder`、`TicketOrderItem`、`ShowtimeSeat` 和 `available_seats`。
2. `lockToken` 只能成功消费一次。MySQL 已存在对应订单时，校验用户、场次和规范化 `seatIds`；一致则返回原订单，不再把 Redis Key 是否存在作为前置条件；不一致返回 `ORDER_REQUEST_MISMATCH`。
3. 新订单必须先写 `TicketOrder`，再写完整 `TicketOrderItem`，然后通过条件 UPDATE 占用全部 `ShowtimeSeat`、扣减 `available_seats`。
4. 取消、超时、支付、出票、退款和核销等既有订单写操作先锁 `TicketOrder`，再按稳定顺序处理 `TicketOrderItem`。所有条件 UPDATE 必须严格校验 `affectedRows`，任一步失败都整体回滚。
5. 查询、取消、超时、支付、出票、退款和核销不得新增 Redis 状态；不要提前引入 Redis Stream、MQ、第三方支付、部分退款或核销撤销。

## 阶段 11～13 冻结边界

### Activity Nearby

- Redis Venue GEO 只提供候选，MySQL 保存最终业务事实。
- 候选窗口为 `min(page × pageSize × 5, 200)`，完成过滤后再分页。
- Activity 去重后选择最近场馆及该场馆下一场次；无 N+1、无 Redis/MySQL 写入。

### Activity 收藏

- 收藏最终事实只在 `tb_collection`。
- 普通列表、详情、Nearby、收藏列表和状态检查中的 `collected` 必须一致。
- 不存在、草稿、下线 Activity 不可新收藏；已有关系仍允许取消，不在读取时清理。

### Activity Review

- 只使用通用 `/api/reviews`，不新增 Activity 嵌套评价接口。
- 创建或恢复要求 Activity `status=1`；Activity 下线后，已有有效评价仍可由原作者修改、删除。
- Activity 评价列表要求目标当前公开有效；单条 Review 详情只要求 Review 本身有效。
- 不读取 `TicketOrder`，不根据购票记录判断评价资格。
- `avg_score`、`score_count` 只能通过 MySQL 原子 SQL 更新；Review 写入与聚合更新必须在同一事务内成功或回滚；禁止 Java 读平均分后计算回写。
- 普通列表、详情、Nearby 和 Activity 收藏列表统一展示 `avgScore`、`scoreCount`，不得改变原排序、过滤、分页、`collected` 或批量查询次数。
- Review Like 使用既有 Redis ZSet，MySQL `like_count` 保存聚合；软删除恢复时重置旧点赞计数，并在提交后清理旧 ZSet。
- Activity Review 与 Activity 收藏完全独立，不得修改 `Showtime`、`ShowtimeSeat`、`TicketOrder`、`TicketOrderItem`、`available_seats`、Venue GEO、`seat:lock:*` 或 `seat:lock:token:*`。

## 下一阶段启动规则

- 下一阶段尚未指定。
- 只选择一个明确且独立的方向，例如 Venue Review、Activity 管理端、非固定座位活动票务或一个单一 AI 能力，不混合多个阶段。
- 先给出精简总体规划，再一次推进一个小 Step。
- 每个 Step 按以下节奏完成并冻结：

```text
设计 → 实现 → 自动化测试 → 真实 MySQL / Redis / HTTP 联调
→ mvn clean package BUILD SUCCESS → Git commit → 冻结
```

开始工作前，请先检查当前工作树和最新提交，再根据我指定的下一阶段给出第一个小 Step；不要提前实现后续 Step。
