# MovieLife 下一对话上下文（Stage 14 已冻结）

请在开始下一阶段前完整继承以下上下文，并以当前仓库的实际代码和最新设计文档为准。

## 当前状态

- 阶段 1～14 均已完成并冻结，不重新设计已经冻结的模块。
- 阶段 14 最终冻结提交：`0586698 feat: freeze stage 14 review AI summary`。
- 当前权威文件：
  - `sql/movielife.sql`
  - `docs/API接口设计.md`
  - `docs/MovieLife_项目技术设计.md`
  - `docs/handoff_updated_stage14.docx`
  - `docs/next_conversation_context_stage14.md`
- Stage 14 专项测试 64 项、全量测试 552 项全部通过，0 failure、0 error、0 skipped；不跳过测试的 `mvn clean package` 为 `BUILD SUCCESS`。
- 已使用隔离 MySQL 8.0.17、Redis 7、随机 HTTP 端口和真实 DeepSeek 模型完成 Movie/Activity 闭环；两类目标均发布 Summary 版本 1、任务 `SUCCEEDED`、匿名查询 `READY`。测试数据、Stream、缓存、服务和容器已清理，Redis 最终 `DBSIZE=0`。

## 必须保持的既有业务边界

1. Redis `TEMP_LOCKED` 只表示创建订单前的临时资格；订单后的最终事实保存在 MySQL 的 `TicketOrder`、`TicketOrderItem`、`ShowtimeSeat` 和 `available_seats`。
2. `lockToken` 只能成功消费一次。已有订单与用户、场次和规范化 `seatIds` 一致时返回原订单；不一致返回 `ORDER_REQUEST_MISMATCH`。
3. 订单写路径中的条件 UPDATE 必须严格检查 `affectedRows`；任一步失败都必须在同一事务中整体回滚。
4. Activity Nearby 继续遵守 Venue GEO 候选、MySQL 最终事实、候选窗口、过滤后分页、Activity 去重、最近场馆和下一场次规则。
5. Activity 收藏最终事实只在 `tb_collection`，不得新增收藏 Redis 状态。
6. Activity Review 只使用通用 `/api/reviews`；创建或恢复要求 Activity `status=1`，下线后已有评价仍允许原作者修改或删除。
7. Activity 评价列表要求目标当前公开有效；单条 Review 详情只要求 Review 本身有效；不读取 `TicketOrder`，不根据票务记录判断评价资格。
8. `avg_score` 和 `score_count` 只能通过 MySQL 原子 SQL 更新；Review 写入与聚合更新必须在同一事务内完成。
9. 普通列表、详情、Nearby 和 Activity 收藏列表统一返回 `avgScore`、`scoreCount`，不得改变原排序、分页、`collected` 或批量查询次数。
10. Review Like 继续使用既有 Redis ZSet，并与 Activity 收藏、票务、Venue GEO 和 Review AI Summary 完全独立。

## Stage 14 Review AI Summary 冻结边界

- 能力只支持严格大写的 `MOVIE`、`ACTIVITY`；不支持 Venue、Post、个性化推荐、推荐理由、内容审核、RAG 或购票决策。
- 匿名只读入口为 `GET /api/ai/summaries/{targetType}/{targetId}`；公开状态只有 `NOT_ENOUGH_REVIEWS`、`GENERATING`、`READY`、`REFRESHING`、`UNAVAILABLE`。GET 不创建任务、不同步调用模型。
- `tb_movie.summary` 始终表示电影剧情简介。AI 评价总结只保存在 `tb_ai_review_summary`，不得回写 Movie、Activity 或 Review 正文。
- `tb_ai_summary_task` 是任务、待处理版本、输入指纹、租约、重试状态和当前 Summary 指针的最终事实；Summary 历史版本不可变，并按 `targetType + targetId` 隔离。
- Redis Stream 只提供至少一次通知，不是任务事实；Redis 结果缓存只保存不可变公共结果。消息丢失由 MySQL 对账补偿，重复消息由条件领取消解，缓存故障回源 MySQL。
- Movie 与 Activity 共用一套生成服务；差异只由 `MovieSummaryTargetPolicy`、`ActivitySummaryTargetPolicy` 提供，禁止复制两套生成主链路。
- 只有有效 Review 的创建、恢复、修改和首次删除会在事务提交后标记任务。点赞、收藏、Nearby、票务、支付、出票和核销变化不得触发。
- Activity 只有 `status=1` 时允许生成或公开查询；下线后保留历史 Summary，但不得公开返回。
- 模型限流、连接失败、超时和供应商 5xx 按策略进入 `RETRY_WAIT`；不可重试或达到上限进入 `DEAD`。评价不足 3 条进入 `INSUFFICIENT_REVIEWS`。
- 模型地址、模型名、超时和 API Key 只能来自服务端配置；密钥、完整 Prompt、完整评价正文和供应商原始响应不得进入仓库、数据库、Redis 消息、公开响应或普通日志。

## 下一阶段启动规则

- 下一阶段尚未指定。
- 只能选择一个明确且独立的方向，例如 Venue Review、Activity 管理端、非固定座位活动票务，或另一个与 Review AI Summary 不重叠且范围明确的能力。
- 开始前先只读检查当前分支、提交和工作树，确认冻结状态和工作树干净。
- 先给出精简总体规划，再一次只推进一个小 Step。
- 每个 Step 必须按以下节奏完成并冻结：

```text
设计 → 实现 → 自动化测试 → 真实 MySQL / Redis / HTTP 联调
→ mvn clean package BUILD SUCCESS → Git commit → 冻结
```

不要提前实现后续 Step，不要顺带修改阶段 1～14 已冻结的业务语义。
