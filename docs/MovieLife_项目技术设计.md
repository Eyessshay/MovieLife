# MovieLife 项目技术设计

## 1. 项目定位

MovieLife 是一个以电影为核心、扩展本地文娱活动发现的社区与购票平台，形成“发现内容—查看评价—选择场次—锁定座位—支付出票”的闭环。

## 2. 业务边界

- 内容：电影、活动、场馆、场次。
- 社区：用户资料、关注、动态、评价、点赞、评论和收藏。
- 交易：场次座位、锁座、订单、支付、退款和票券。
- 智能化：基于真实行为的推荐，以及可解释的 AI 推荐理由。

项目不追求一次性复刻大型平台的全部能力，优先保证电影购票和本地文娱发现的业务自洽。

## 3. 核心设计原则

### 3.1 真实的场次与座位模型

物理座位属于影厅，但可售状态属于具体场次。`tb_showtime_seat` 负责记录某场次下的可用、锁定和已售状态，避免把“锁座”错误地设计成影厅级别。

### 3.2 订单状态机

选座和订单采用两阶段职责：`SeatLockService` 先在 Redis 中原子锁定座位并返回有期限的 `lockToken`；`TicketOrderService` 校验锁的用户归属、场次、座位集合和有效期，再持久化待支付订单，并把 Redis 临时锁转换为数据库中的订单占用状态。订单围绕 `UNPAID → PAID`、`UNPAID → CANCELED`、`PAID → REFUNDED` 流转。锁座过期、订单创建、支付回调、取消和退款均需幂等处理，并以服务端状态为准。

### 3.3 Redis 使用边界

- Redis GEO：附近场馆和活动的候选召回；结合场次表过滤没有目标电影/活动排片的场馆。
- Redis：登录态、热点数据、锁座短期状态和限流。
- Redis ZSet：评价点赞关系，Key 为 `review:like:{reviewId}`，member 为 `userId`，score 为点赞时间戳；点赞总数持久化在 `tb_review.like_count`。
- Lua：多座位批量校验与原子锁定。
- Redis Stream：异步处理行为采集、订单超时释放、出票和推荐特征更新。

Redis 用于提升并发性能，最终订单、座位售出结果和评价内容仍以 MySQL 持久化结果为准。

评价点赞在当前 MVP 中采用“Redis ZSet 保存用户关系、MySQL 保存聚合数量”的双存储方案。点赞和取消点赞先以 Redis 原子新增/移除结果判断是否需要修改计数，数据库写入失败时执行反向补偿并记录错误。该方案不等同于跨存储强一致事务：如果 Redis 被清空，`like_count` 仍在，但具体点赞用户关系会丢失。当前环境需开启可靠的 Redis 持久化；生产级演进方案是增加点赞关系持久化表，或通过可靠消息异步落库与对账修复。

### 3.4 场馆与 Redis GEO

场馆附近查询采用“Redis 负责空间召回、MySQL 负责业务事实”的边界：

```text
MySQL tb_venue
        ↓
初始化 Redis GEO
        ↓
venue:geo:{city}:{TYPE}
        ↓
按经纬度召回附近 venueId + distance
        ↓
MySQL 批量补齐启用的 Venue
        ↓
可选查询 tb_showtime
过滤指定电影未来可售场次
        ↓
过滤后分页并按 GEO 顺序恢复
        ↓
返回附近场馆
```

GEO 初始化只处理 `status=1` 且经纬度完整的场馆，按 `city + type` 分组批量写入，member 使用 `venueId`。普通 nearby 查询直接按距离分页；电影过滤模式先校验电影存在，再查询 `tb_showtime` 中 `start_time > NOW()` 且 `status=1` 的场馆，并在排片过滤后分页。同一场馆的多个有效场次通过 `DISTINCT venue_id` 去重。

当前 MVP 明确保留以下边界：

- GEO 初始化只新增或更新 member，不主动清理 Key 中的历史 member；读取时会忽略 MySQL 中不存在或已停用的场馆。
- Venue 信息修改后的 GEO 自动同步留待管理端阶段处理。
- nearby 的业务过滤目前只支持 `MOVIE`，暂不支持 `ACTIVITY`。
- Redis GEO 故障直接返回统一服务异常，暂不使用 MySQL 经纬度计算降级。
- 电影过滤模式的 GEO 候选窗口为 `min(page × pageSize × 5, 200)`；200 条上限是当前数据规模下控制 Redis 召回和 MySQL 查询成本的工程折中，不代表完整深分页能力。

### 3.5 电影场次查询

阶段 6 的第一条查询链路为：

```text
Movie
  ↓
附近有该电影排片的 Venue
  ↓
venueId + movieId + date
  ↓
查询当天未来且可售的 tb_showtime
  ↓
批量查询 Hall 并补齐 hallName
  ↓
按 startTime 升序返回 ShowtimeVO
```

日期条件在 Java 中计算为 `[date.atStartOfDay(), date.plusDays(1).atStartOfDay())`，SQL 不对 `start_time` 使用 `DATE()` 函数。有效场次口径与 nearby 电影过滤保持一致，即 `biz_type=MOVIE`、`status=1` 且 `start_time > NOW()`。场次 VO 使用 `basePrice` 表达场次基础价，后续座位查询再叠加具体座位价格。

场次列表是 `venue + movie + date + 当前时间` 的动态组合查询，当前直接读取 MySQL，不增加 Redis 列表缓存，避免引入排片修改、停售和时间流逝导致的复杂失效逻辑。本阶段只提供查询，不涉及座位锁定、订单、支付、超时取消或出票。

### 3.6 场次详情查询

场次详情以独立资源 `GET /api/showtimes/{id}` 提供，查询链路为：

```text
showtimeId
  ↓
校验未来、启用的 MOVIE Showtime
  ↓
分别查询 Venue、Hall、Movie
  ↓
校验 Hall 与 Venue 归属关系
  ↓
组装 ShowtimeDetailVO
```

公开接口将不存在、停用、过期和非 `MOVIE` 场次统一视为 `SHOWTIME_NOT_FOUND`。关联 Venue 已停用时不公开该场次；关联记录缺失或 Hall 不属于场次 Venue 时按内部数据不一致处理，避免返回半残详情。详情固定只读取一条场次及三条关联记录，不存在 N+1，因此当前不增加复杂 JOIN。

`ShowtimeDetailVO` 返回场馆、影厅、电影的展示名称以及起止时间和 `basePrice`。该接口直接读取 MySQL，不缓存时间敏感的场次状态。本 Step 仍不查询 Seat、ShowtimeSeat，不涉及锁座、订单或支付。

### 3.7 场次座位图查询

`tb_seat` 表示 Hall 的固定物理座位模板，`base_status` 只表达座位本身是否启用；`tb_showtime_seat` 表示 `Showtime × Seat` 的场次级销售事实，`status` 表达该场次中的可售、待支付订单占用或已售状态。两层状态不能混用。

```text
有效 MOVIE Showtime 上下文
  ↓
按 hallId 批量查询 base_status=1 的 Seat
  ↓
按 showtimeId + seatIds 一次查询 ShowtimeSeat
  ↓
以 seatId 建立状态映射并校验完整性
  ↓
按 rowNo + colNo 组装座位图
```

响应保持扁平列表，同时返回数据库已有的 `colNo` 和场次座位 `price`：`colNo` 用于可靠表达物理列位置，避免按字符串 `seatNo` 排序产生 `1、10、2` 的问题；`price` 表达座位在当前场次中的实际价格，场次 `basePrice` 仍作为基础价保留。

Hall 没有有效 Seat 时正常返回空列表；Hall 有有效 Seat 但缺少对应 ShowtimeSeat 时视为初始化或数据一致性异常，不默认成可售，也不在 GET 请求中自动补数据。当前座位图只读取 MySQL，不合并 Redis 锁座状态、不增加缓存；实时 `AVAILABLE / LOCKED / SOLD` 合并留待后续锁座阶段。

### 3.8 阶段 6 查询链路冻结

阶段 6 最终形成以下只读链路：

```text
Movie
  ↓
Redis GEO Nearby Venue + MOVIE Showtime 过滤
  ↓
Venue Movie Showtime List
  ↓
Showtime Detail
  ↓
Hall Seat Template
  ↓
ShowtimeSeat Status + Price
  ↓
Seat Map
```

三层数据职责固定为：`Showtime` 保存场次基础信息和 `basePrice`；`Seat` 保存 Hall 的物理座位、行列位置、类型和基础启用状态；`ShowtimeSeat` 保存特定场次下的销售状态和座位级 `price`。场次开始后不再作为可售场次公开，统一以 `start_time > NOW()` 判断，不改为 `end_time > NOW()`。

本阶段所有能力均为查询：不缓存 Showtime 列表、详情或 Seat Map，不在 GET 请求中插入或更新 ShowtimeSeat，不写入座位锁 Redis Key。文档中保留的 `SeatLockService`、`lockToken`、Redis Lua 和 TicketOrder 内容仅是后续阶段设计，不代表阶段 6 已实现。

### 3.9 推荐与 AI

推荐链路暂定为：行为采集 → 用户/内容标签打分 → 候选召回 → 规则排序 → AI 生成推荐理由，推荐理由随推荐结果一起返回。评价总结由评论变化触发异步生成，持久化并缓存后由只读接口提供，禁止在详情页请求中同步调用模型。AI 不直接决定座位库存、价格、支付结果或订单状态。

## 4. 服务端模块建议

`movielife-server/` 预留为后端工程，建议按以下领域拆分：

```text
auth       用户认证与登录态
user       用户资料、关注关系
movie      电影与推荐
activity   活动与场次
venue      场馆、影厅、座位模板
review     评价、点赞、评论
post       社区动态与 Feed
collection 电影、活动、帖子和评价收藏
showtime   排片、场次座位和锁座
order      订单、支付、退款、票券
recommend  行为采集、召回、排序和推荐理由
ai         评价总结的异步生成、持久化和读取
common     统一响应、异常、鉴权、限流、幂等
```

## 5. 关键非功能要求

- 所有写接口校验当前用户和资源归属。
- 订单、支付、锁座操作必须支持幂等。
- 释放锁座只接受服务端签发的 `lockToken`，不能依据客户端任意传入的座位 ID 释放资源。
- 列表接口必须分页，并限制单页最大数量。
- 关键链路记录 `requestId`，支付和锁座记录业务日志。
- 对手机号、支付信息和内部回调进行脱敏和验签。

电影详情和活动详情优先在 VO 中返回当前用户的收藏、点赞等附加状态，通用状态查询接口只作为兜底，避免详情页产生不必要的额外请求。

阶段 4 社区链路采用以下实现边界：收藏关系和关注关系以 MySQL 为唯一事实来源；帖子点赞关系使用 Redis ZSet、计数持久化在 `tb_post.liked`；关注 Feed 使用 Redis ZSet 推模式，帖子事务提交后再推送给粉丝。Feed 查询使用 `maxTime + offset` 滚动分页，批量查询帖子和用户后按 Redis 返回顺序恢复。删除帖子不遍历所有粉丝清理 Feed，而是在读取时惰性移除失效 postId。

电影表的 `actors` 第一版继续使用 `TEXT` 保存逗号分隔字符串，由 Service/Converter 拆分为 `MovieDetailVO.actors: List<String>`，不在数据库层提前引入 JSON 改造。

详细接口以 [API接口设计.md](API接口设计.md) 为准；当前 API 文档为 v0.2，Venue + Redis GEO 查询契约已在阶段 5 冻结，其余尚未实现模块的字段级契约将在后续阶段继续补齐。
