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

物理座位属于影厅，但销售状态属于具体场次。`tb_showtime_seat` 负责记录某场次下的可售、待支付订单占用和已售状态；用户选座期间的临时锁单独保存在 Redis，避免把临时锁混入数据库业务事实，也避免把座位状态错误地设计成影厅级别。

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

Hall 没有有效 Seat 时正常返回空列表；Hall 有有效 Seat 但缺少对应 ShowtimeSeat 时视为初始化或数据一致性异常，不默认成可售，也不在 GET 请求中自动补数据。当前座位图只读取 MySQL，不合并 Redis 临时锁状态、不增加缓存；数据库状态与 Redis 临时锁的合并展示留待后续锁座阶段。

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

## 4. 阶段 7：锁座设计

### 4.1 Step 7.1：锁座职责边界

锁座采用 Redis 临时锁与 MySQL 最终业务事实分层的设计。Redis 临时锁表示“用户正在选座，座位被短暂占用”，具有 TTL，可以自动过期，不属于最终业务事实。`tb_showtime_seat.status` 只保存数据库中的销售状态：

```text
0 = AVAILABLE
1 = HELD_BY_UNPAID_ORDER
2 = SOLD
```

必须明确：

```text
Redis 锁成功
≠
tb_showtime_seat.status = 1
```

`status=1` 只允许在后续创建待支付订单成功时写入。`TEMP_LOCKED` 是 Redis 临时锁存在时的逻辑状态，不是 `tb_showtime_seat.status` 的新增取值。当前暂定的完整状态转换为：

```text
AVAILABLE
   ↓ Redis 临时锁
TEMP_LOCKED
   ↓ 创建订单成功
HELD_BY_UNPAID_ORDER
   ↓ 支付成功
SOLD
```

在未创建订单的前提下，Redis 临时锁超时后的状态恢复为：

```text
TEMP_LOCKED
   ↓ TTL 到期
AVAILABLE
```

因此，锁座成功时禁止执行以下操作：

- 不修改 `tb_showtime_seat.status`。
- 不创建 `TicketOrder`。
- 不扣减库存。
- 不执行支付逻辑。

`SeatLockService` 的职责严格限定为：接收 `showtimeId + seatIds + 当前 userId`，在 Redis 中完成原子锁定，并返回 `lockToken + expireTime`。Redis Key、`lockToken` 数据结构、重复请求幂等、过期释放和异常恢复将在后续 Step 单独设计，本 Step 不实现 Lua。

锁座 API 冻结为：

```http
POST /api/showtimes/{id}/seats/lock
```

请求体：

```json
{
  "seatIds": [101, 102]
}
```

成功响应的核心字段：

```json
{
  "lockToken": "xxx",
  "expireTime": "..."
}
```

这一职责边界延续阶段 6 的冻结结论：阶段 6 已存在的 `status=1` 只是数据库既有状态及查询展示语义，不表示 Redis 锁座流程已经实现。Redis 负责临时状态和并发协调，MySQL 负责订单占用、售出等最终业务事实。

### 4.2 Step 7.2：Redis 锁座数据结构

每个场次座位使用独立的临时锁 Key：

```text
seat:lock:{showtimeId}:{seatId}
```

座位锁的 Value 统一保存服务端签发的 `lockToken`，不只保存 `userId`。同一次多座位锁定中的所有座位必须绑定同一个 `lockToken`。例如：

```text
seat:lock:1001:101 = 8f3a...
seat:lock:1001:102 = 8f3a...
```

座位锁必须设置 TTL，阶段 7 暂定为 5 分钟。该 TTL 只表达用户选座期间的临时占用期限，不改变 `tb_showtime_seat.status`，也不形成订单事实。

每次锁座还必须写入对应的 `lockToken` 元数据 Key：

```text
seat:lock:token:{lockToken}
```

其 Value 保存本次完整锁座上下文，至少包含：

```json
{
  "userId": 1,
  "showtimeId": 1001,
  "seatIds": [101, 102],
  "expireTime": "..."
}
```

`expireTime` 是服务端计算的锁座绝对过期时间，必须与 Redis 实际过期时刻一致。通过元数据 Key，可以由 `lockToken` 找回锁的用户、场次和完整座位集合：

```text
lockToken
  ↓
userId
showtimeId
完整 seatIds
```

后续释放锁时，客户端只提交 `lockToken`，禁止重新提交 `seatIds`。服务端必须通过 `seat:lock:token:{lockToken}` 找回完整锁座上下文，再校验锁的用户归属以及各 seatKey 当前绑定的 token，不能依据客户端提供的任意座位集合释放资源。

当前阶段不增加以下用户维度索引 Key：

```text
seat:lock:user:{userId}:{showtimeId}
```

现有 `lockToken` 元数据已经能够反向定位完整锁座信息，无需同时维护“座位锁 + 用户锁集合 + token 元数据”三份状态，从而降低多份 Redis 状态同步和异常清理的复杂度。

`lockToken` 必须由服务端生成随机、不可预测的 Token。阶段 7 可采用：

```java
UUID.randomUUID().toString().replace("-", "")
```

生成规则冻结为：

- 客户端不能自行生成 `lockToken`。
- 一次成功锁座只对应一个 `lockToken`。
- 同一次锁定的所有 seatKey 必须绑定同一个 `lockToken`。
- `lockToken` 不能直接使用 `userId`、`showtimeId` 或二者拼接生成。

一次锁座成功后的 Redis 状态示例为：

```text
seat:lock:1001:101
    → lockToken=A

seat:lock:1001:102
    → lockToken=A

seat:lock:token:A
    → userId=1
    → showtimeId=1001
    → seatIds=[101,102]
    → expireTime=...
```

所有 `seat:lock:{showtimeId}:{seatId}` Key 与 `seat:lock:token:{lockToken}` Key 必须具有相同的锁座生命周期。一次锁座应以同一个服务端过期时刻为基准设置全部 Key，不能让某个 seatKey 或 token 元数据单独续期；如何保证多 Key 原子写入与并发冲突处理留待 Step 7.3 设计。

本 Step 只冻结 Redis 数据结构，不实现以下内容：

- Lua。
- 重复锁座请求处理。
- 主动 release。
- `TicketOrder`。
- `tb_showtime_seat.status` 修改。

### 4.3 Step 7.3：锁座前置校验与原子性规则

锁座请求以 `showtimeId + seatIds + 当前 userId` 为输入。在进入 Redis 锁定逻辑之前，必须按以下顺序完成前置校验：

1. 当前用户已登录。
2. `showtimeId` 是合法正整数。
3. `seatIds` 非空。
4. `seatIds` 中不存在重复值。
5. 单次锁座数量不超过 6 个。
6. Showtime 是未来、启用的 `MOVIE` 场次。
7. Showtime 关联的 Venue、Hall、Movie 均有效，并满足既有归属关系。
8. `seatIds` 全部属于该 Showtime 对应的 Hall。
9. 对应 `tb_seat.base_status` 全部为 `1`。
10. 对应 `tb_showtime_seat` 记录全部存在，且查询结果数量与 `seatIds` 数量一致。
11. 所有 `tb_showtime_seat.status` 均为 `AVAILABLE`。

第 6～7 项直接复用阶段 6 已冻结的 Showtime 有效性规则：`biz_type=MOVIE`、`status=1`、`start_time > NOW()`，并校验 Venue、Hall、Movie 关联有效，不为锁座重新定义另一套场次有效口径。

第 8～11 项必须使用批量查询完成，禁止对 `seatIds` 逐个循环查询数据库。数据库校验流程冻结为：

```text
seatIds 去重校验
      ↓
一次查询 Hall 下且 id IN seatIds 的 Seat
      ↓
确认查询数量 == seatIds 数量
      ↓
确认全部 tb_seat.base_status = 1
      ↓
一次查询 showtimeId + seatIds 对应的 ShowtimeSeat
      ↓
确认查询数量 == seatIds 数量
      ↓
确认全部 status = AVAILABLE
```

任意一个座位缺失、不属于目标 Hall、物理座位未启用、缺少 ShowtimeSeat 或数据库状态不可售时，整次锁座请求失败，不能跳过失败座位后继续锁定其余座位。

数据库前置校验通过后，多座位 Redis 锁定必须保证“要么全部成功，要么全部失败”。例如本次请求为：

```text
seatIds = [101, 102, 103]
```

对应的 Redis Key 为：

```text
seat:lock:1001:101
seat:lock:1001:102
seat:lock:1001:103
```

禁止出现以下部分成功状态：

```text
101 锁成功
102 已被其他用户锁定
103 未执行

最终 Redis 遗留 seat:lock:1001:101
```

多座位锁定必须由一次 Lua 执行完成。Lua 的处理逻辑冻结为两轮：

```text
第一轮：只校验
遍历全部 seatKey
若任意 seatKey 已存在
  → 整次失败
  → 不修改任何 Redis 数据

第二轮：统一写入
确认全部 seatKey 均不存在后
  → 写入所有 seatKey = lockToken
  → 按同一过期时刻设置全部 seatKey 的 TTL
  → 写入 seat:lock:token:{lockToken} 元数据
  → 为 token 元数据设置同一生命周期
  → 返回成功
```

第一轮不得写入任何 Key；只有全部 seatKey 检查通过后才能进入第二轮。座位锁 Key 和 token 元数据 Key 必须在同一次 Lua 执行中完成写入，确保不会产生部分座位锁或缺失 token 元数据的中间状态。本 Step 只冻结 Lua 必须满足的行为，不提交 Lua 脚本实现。

必须明确：

```text
MySQL AVAILABLE
≠
当前一定可锁
```

MySQL 只记录订单占用和售出等最终业务事实，真正获取临时锁时还必须检查 Redis。一个座位只有同时满足以下两个条件才可以成功获得临时锁：

```text
MySQL:
tb_showtime_seat.status = AVAILABLE

并且

Redis:
seat:lock:{showtimeId}:{seatId} 不存在

两者同时满足
  → 才能成功获得临时锁
```

并发冲突结果冻结为：Lua 检查时只要发现任意 seatKey 已存在，整次锁座失败，不写入任何新锁，并返回：

```text
HTTP 409 Conflict
code = SEAT_ALREADY_LOCKED
```

如果数据库批量校验发现任意 `tb_showtime_seat.status != AVAILABLE`，则在执行 Lua 之前直接终止整次请求。两类座位不可锁定原因在设计上区分为：

```text
Redis 临时锁冲突
  → SEAT_ALREADY_LOCKED

MySQL 已被待支付订单占用或已经售出
  → SEAT_NOT_AVAILABLE
```

`SEAT_ALREADY_LOCKED` 沿用 API 文档已冻结的 `409 Conflict` 契约。`SEAT_NOT_AVAILABLE` 当前作为设计预留错误码记录，本 Step 不修改全局错误码实现；其正式 HTTP 映射在进入实现步骤时随接口错误契约一并冻结。

本 Step 暂不设计以下内容，统一留待 Step 7.4：重复请求与锁座幂等策略：

- 相同请求重复提交的幂等。
- 用户自己重复锁同一批座位。
- `lockToken` 重用。
- release Lua。
- TTL 到期后的恢复流程。
- `TicketOrder`。

### 4.4 Step 7.4：锁座幂等与重复请求处理

锁座幂等判断的核心对象不是“用户是否已经锁过座位”，而是“本次请求是否与当前仍然有效的锁座完全一致”。同一用户可以持有多次彼此独立的有效锁座，例如先锁定 `[101,102]`，之后再锁定 `[103,104]`；当前阶段不采用“一人一锁”或“一人一单”的限制。

#### 4.4.1 相同用户、相同 Showtime、完全相同 seatIds

如果本次请求对应的全部 `seat:lock:{showtimeId}:{seatId}` 均存在，并且全部绑定同一个 `lockToken`，则继续读取 `seat:lock:token:{lockToken}`，校验以下条件：

```text
userId == 当前用户
showtimeId == 当前 showtimeId
seatIds 集合完全一致
锁仍未过期
```

只有以上条件全部满足时，才将请求视为同一次有效锁座的重复提交。此时处理规则为：

```text
不生成新的 lockToken
不重新写入 Redis
不刷新任何 Key 的 TTL
不延长 expireTime
直接返回原 lockToken + 原 expireTime
```

重复请求不能导致锁座时间不断续期，否则客户端可以通过持续重试永久占用座位。

#### 4.4.2 当前用户已锁定部分或全部座位，但请求集合不同

假设当前已有锁座：

```text
lockToken=A
seatIds=[101,102]
```

当前用户再次请求 `[101,102,103]` 或 `[101]` 时，因为座位集合不完全一致，不能视为同一次请求，统一按新锁座请求处理。只要新请求的 `seatIds` 中存在任意已经被锁定的 seatKey，即使该锁也属于当前用户，整次请求仍然失败，暂定返回：

```text
HTTP 409 Conflict
code = SEAT_ALREADY_LOCKED
```

本阶段不支持扩展已有锁、减少已有锁、合并两个 `lockToken` 或修改既有锁座集合。

#### 4.4.3 座位被其他用户锁定

只要本次请求中的任意 seatKey 已被其他用户的有效锁占用，整次请求直接失败：

```text
HTTP 409 Conflict
code = SEAT_ALREADY_LOCKED
```

响应不得返回其他用户的 `userId`、`lockToken` 或剩余 TTL，避免泄露内部锁信息。

#### 4.4.4 seatIds 规范化与集合比较

`seatIds` 按座位集合比较，请求 `[101,102]` 与 `[102,101]` 必须视为相同集合。在进入幂等判断和最终锁定逻辑之前，统一执行：

```text
seatIds
  → 校验重复值
  → 按 Long 升序排序
  → 使用规范化后的 seatIds
```

例如：

```text
[102,101]
  ↓
[101,102]
```

`seat:lock:token:{lockToken}` 元数据中的 `seatIds` 也必须按相同的 Long 升序保存。幂等校验比较规范化后的完整列表，从而实现与客户端传入顺序无关的集合一致性判断；原始请求中存在重复值时仍按参数错误处理，不能通过去重后继续锁座。

#### 4.4.5 锁座主流程

锁座主流程的业务语义冻结为：

```text
请求进入
  ↓
参数 / Showtime / Seat / ShowtimeSeat 前置校验
  ↓
seatIds 规范化
  ↓
检查 Redis 当前锁状态
  ↓
若完全匹配当前用户已有有效锁
  → 返回原 lockToken + 原 expireTime

否则
  ↓
生成新 lockToken
  ↓
执行多座位原子锁定 Lua
  ↓
成功 → 返回新 lockToken + expireTime
失败 → SEAT_ALREADY_LOCKED
```

“查询已有锁 → 判断幂等 → 新建锁”之间存在并发窗口，因此当前 Step 只冻结业务语义。真正实现时，不能仅依靠 Java 中的以下流程保证幂等和并发安全：

```text
先 GET
再判断
再 SET
```

哪些幂等判断和状态校验必须放入 Lua，将在后续实现 Step 中结合原子锁定脚本继续冻结。

#### 4.4.6 lockToken 含义不可变

`lockToken` 一旦签发，不允许复用于另一批座位。一个 token 在整个生命周期内只能对应：

```text
一个 userId
一个 showtimeId
一组固定且规范化的 seatIds
一个固定 expireTime
```

上述任一字段均不得在 token 生命周期内被修改。后续业务必须通过 `lockToken` 找回并校验用户、场次和完整座位集合，不能信任客户端重新提交的座位信息。

本 Step 仍然不实现或设计以下内容，统一留待 Step 7.5：主动释放、TTL 过期与异常恢复规则：

- Lua 脚本。
- release 接口逻辑。
- 主动释放规则。
- TTL 到期清理。
- Redis 异常恢复。
- `TicketOrder`。
- `tb_showtime_seat.status` 修改。

### 4.5 Step 7.5：锁座释放与过期恢复规则

主动释放接口冻结为：

```http
POST /api/showtimes/{id}/seats/release
```

请求体只允许提交 `lockToken`：

```json
{
  "lockToken": "xxx"
}
```

禁止客户端提交 `seatIds`；释放范围只能由服务端根据 token 元数据恢复。

#### 4.5.1 主动释放前校验

服务端根据 `seat:lock:token:{lockToken}` 读取完整锁座上下文：

```text
userId
showtimeId
seatIds
expireTime
```

主动释放按以下规则校验：

1. `lockToken` 非空。
2. 查询 token 元数据是否存在；不存在时直接进入幂等完成分支。
3. token 存在时，校验 `token.userId == 当前用户`。
4. 校验 `token.showtimeId == URL 中的 showtimeId`。
5. 校验 token 尚未过期；已过期时按锁已自然释放处理，进入幂等完成分支。

有效 token 的用户不匹配时返回：

```text
HTTP 403 Forbidden
code = LOCK_NOT_OWNED
```

有效 token 的 `showtimeId` 与 URL 不匹配时返回：

```text
HTTP 400 Bad Request
code = INVALID_LOCK_TOKEN
```

`LOCK_NOT_OWNED` 和 `INVALID_LOCK_TOKEN` 在本 Step 中只作为设计错误码冻结，是否加入全局错误码及其最终响应文案留待实现 Step 统一处理。

#### 4.5.2 释放范围必须来自 token 元数据

释放链路固定为：

```text
lockToken
   ↓
token 元数据
   ↓
取得完整 seatIds
   ↓
校验每个 seatKey 当前 Value
```

禁止采用以下不可信流程：

```text
客户端提交 seatIds
   ↓
服务端直接 DEL
```

每个座位锁只有在以下条件成立时才能删除：

```text
seat:lock:{showtimeId}:{seatId} 的 Value == 当前 lockToken
```

如果 seatKey 的 Value 已变成其他 token，绝对不能删除，避免旧 token 误删后来获得的有效锁。

#### 4.5.3 主动释放的原子性

释放一组座位时，以下操作必须在同一次 Lua 中完成：

```text
读取并校验 token 元数据
校验全部 seatKey 当前 Value
删除 Value == 当前 lockToken 的全部 seatKey
删除 seat:lock:token:{lockToken}
```

禁止在 Java 中逐个执行以下操作：

```text
GET
DEL
GET
DEL
```

Lua 对全部 Key 的检查与删除必须是一个不可分割的操作，避免释放过程中发生并发覆盖或只释放部分仍属于当前 token 的座位。本 Step 仅冻结 release Lua 必须满足的行为，不提交 Lua 脚本。

#### 4.5.4 主动释放的幂等规则

如果 `seat:lock:token:{lockToken}` 已不存在，可能表示锁已因 TTL 过期、已经主动释放，或后续已经成功转换为订单。此时 release 必须保持幂等：

```text
直接视为释放完成
不报错
不创建任何新状态
不修改 MySQL
```

因此：

```text
第一次 release → 成功
第二次 release → 仍然成功
```

token 已超过 `expireTime` 时同样视为临时锁生命周期已经结束，不续期、不重建锁，按幂等完成处理。

#### 4.5.5 TTL 自动过期规则

正常未创建订单的锁座状态变化为：

```text
TEMP_LOCKED
   ↓ 5 分钟 TTL
Redis Key 自动过期
   ↓
逻辑恢复 AVAILABLE
```

这里的 `AVAILABLE` 是“数据库状态仍为 AVAILABLE 且 Redis 临时锁已不存在”时的逻辑可锁状态，不执行以下数据库操作：

```sql
UPDATE tb_showtime_seat
```

锁座阶段从未将 `tb_showtime_seat.status` 修改为 `1`，因此普通临时锁超时不需要定时任务恢复 ShowtimeSeat，不需要消息队列恢复数据库，也不需要主动扫描 Redis。Redis TTL 本身就是普通未下单锁座的释放机制。

#### 4.5.6 多 Key 过期时间差异的容错

虽然同一次锁座的所有 seatKey 和 token 元数据使用同一绝对过期时刻，但不能假设以下条件永远成立：

```text
token Key 存在
⇔
全部 seatKey 一定存在
```

Redis 对过期 Key 的实际清理时机可能存在轻微差异，异常情况下也可能出现 token 元数据存在但部分 seatKey 已不存在。release 和幂等逻辑必须按以下原则容错：

```text
seatKey 不存在
  → 视为该座位已经释放
  → 不重新创建

seatKey Value == 当前 lockToken
  → 删除

seatKey Value != 当前 lockToken
  → 不删除

完成座位处理后
  → 删除当前 token 元数据
```

以上判断和删除必须在 release Lua 中原子完成，不能因为部分 seatKey 缺失而恢复旧锁，也不能删除已经属于其他 token 的锁。

#### 4.5.7 Redis 异常恢复边界

异常恢复继续遵循以下事实边界：

```text
Redis 临时锁不是最终业务事实
MySQL 才是最终订单和售出状态来源
```

Redis 重启或临时锁数据丢失后，不能根据 `tb_showtime_seat.status=AVAILABLE` 自动重新生成旧锁。丢失的 `TEMP_LOCKED` 直接视为失效，旧 `lockToken` 不允许继续创建订单。

后续创建订单时必须再次同时校验：

```text
lockToken 元数据存在且未过期
全部 Redis seatKey 仍绑定该 lockToken
MySQL ShowtimeSeat 状态仍可转换
```

任一条件不满足均不得创建订单。Redis 暂时不可用时，锁座、释放及后续订单转换不能通过读取 MySQL 冒充 Redis 校验成功，也不能重建旧锁；无法确认锁状态的操作应失败并允许客户端重试，未下单的临时锁最终仍由其既有 TTL 释放。

#### 4.5.8 当前阶段不做锁恢复

本阶段禁止实现：

- 将 Redis 临时锁持久化到 MySQL。
- Redis 重启后恢复 `TEMP_LOCKED`。
- 定时扫描并重建 `seat:lock`。
- 使用 Redis Stream 记录每次普通锁座以供恢复。

临时锁不能被设计成第二套数据库。

#### 4.5.9 冻结状态变化

未创建订单时的完整状态变化为：

```text
AVAILABLE
   ↓ 锁座成功
TEMP_LOCKED
   ↓ release / TTL
AVAILABLE
```

主动释放和 TTL 过期都只删除或等待过期 Redis 临时状态，不修改 `tb_showtime_seat`。

本 Step 仍然不实现以下内容：

- Lua。
- Controller / Service。
- `TicketOrder`。
- 创建订单后的锁转换。
- `status=1` 写入。
- 支付。
- 订单超时取消。

上述订单转换边界留待 Step 7.6：lockToken 与 TicketOrder 的生命周期边界设计。

### 4.6 Step 7.6：lockToken 与 TicketOrder 生命周期边界

`lockToken` 与 `TicketOrder` 分属两个不同阶段的业务凭证，职责冻结为：

```text
lockToken
  → 只表示一份仍有效的 Redis 临时锁座资格

TicketOrder
  → 表示已经持久化到 MySQL 的待支付订单事实
```

二者不能混用。`SeatLockService` 只负责先在 Redis 中锁座并签发 `lockToken`；`TicketOrderService` 后续校验锁的用户归属、场次、座位集合、完整性和有效期，再创建 `UNPAID` 订单。

#### 4.6.1 创建订单必须携带 lockToken

后续订单创建接口冻结为：

```http
POST /api/orders
```

请求体：

```json
{
  "showtimeId": 1001,
  "seatIds": [101, 102],
  "lockToken": "xxx"
}
```

服务端不能直接信任客户端提交的 `seatIds`：

```text
客户端 seatIds
  → 只用于与服务端锁座上下文做一致性校验

真正锁定的座位集合
  → 以 lockToken 元数据中的规范化 seatIds 为准
```

订单创建时必须校验 `lockToken` 的用户归属、场次、座位集合和有效期，不能根据客户端请求体自行扩大、缩小或替换锁座范围。

#### 4.6.2 创建订单前重新校验完整锁状态

对于尚未消费、准备首次创建订单的 `lockToken`，创建订单前必须同时满足：

1. `seat:lock:token:{lockToken}` 存在。
2. token 未过期。
3. `token.userId == 当前用户`。
4. `token.showtimeId == 请求 showtimeId`。
5. `token.seatIds == 请求 seatIds`，两者均规范化后比较。
6. 每个 `seat:lock:{showtimeId}:{seatId}` 均存在。
7. 每个 seatKey 的 Value 均等于当前 `lockToken`。
8. Showtime 仍满足阶段 6 冻结的有效性规则。
9. 对应的 ShowtimeSeat 全部存在且数量完整。
10. 所有 `ShowtimeSeat.status == AVAILABLE`。

任意条件失败都不得创建新订单。特别是：

```text
token 元数据存在
但任意一个 seatKey 已丢失或不再绑定当前 lockToken
  → 锁已经不完整
  → 不得创建订单
```

为兼容同一 token 的订单重试，请求进入 `TicketOrderService` 后必须先查询持久化的 `lockToken → TicketOrder` 消费关系：如果该 token 已经成功创建订单，并且订单记录的用户、场次和规范化座位集合与本次请求一致，则直接返回原订单；只有确认 token 尚未消费时，才执行上述十项“首次创建订单”校验。否则，订单已创建后的重试会因为 MySQL 座位已经变成 `HELD_BY_UNPAID_ORDER` 而被错误拒绝。

#### 4.6.3 订单成功时才转换数据库状态

只有订单数据库事务成功时，临时锁才转换为待支付订单占用：

```text
Redis:
TEMP_LOCKED

        ↓ 创建订单事务成功

MySQL:
TicketOrder = UNPAID
ShowtimeSeat.status = HELD_BY_UNPAID_ORDER
```

完整状态变化为：

```text
AVAILABLE
  → TEMP_LOCKED
  → HELD_BY_UNPAID_ORDER
```

只有第二个箭头会修改 MySQL。Redis 锁座成功本身仍不得写入 `tb_showtime_seat.status=1`。

#### 4.6.4 多座位数据库转换的事务原子性

一个订单包含 `[101,102,103]` 等多个座位时，以下操作必须在同一个 MySQL 事务中完成：

```text
创建 TicketOrder
+
创建全部订单座位明细
+
将全部对应 ShowtimeSeat
AVAILABLE → HELD_BY_UNPAID_ORDER
+
持久化 lockToken → TicketOrder 的一次性消费关系
```

禁止出现以下部分成功状态：

```text
订单创建成功
101 status=1
102 status=1
103 更新失败
```

任意订单记录、座位明细、状态转换或 token 消费关系写入失败，整个数据库事务必须回滚。

#### 4.6.5 ShowtimeSeat 条件更新与数量校验

后续实现不能使用不带原状态约束的更新：

```sql
UPDATE tb_showtime_seat
SET status = 1
WHERE showtime_id = ?
  AND seat_id IN (...)
```

更新条件必须包含原状态 `AVAILABLE`：

```text
WHERE showtime_id = ?
  AND seat_id IN (...)
  AND status = AVAILABLE
```

执行后必须校验：

```text
实际更新数量 == seatIds 数量
```

数量不一致说明某些数据库座位不存在或状态已经变化，整个订单事务必须失败并回滚。这是 Redis 临时锁之外，由 MySQL 最终事实层提供的并发兜底。

#### 4.6.6 Redis 锁的结束时机

Redis 临时锁不能在数据库事务提交前删除，禁止：

```text
先删除 Redis 临时锁
  → 再创建订单
```

否则数据库事务失败时，座位会提前重新暴露为可锁。生命周期顺序固定为：

```text
Redis 临时锁保持有效
  ↓
执行订单数据库事务
  ↓
事务成功提交
  ↓
TicketOrder = UNPAID
ShowtimeSeat = HELD_BY_UNPAID_ORDER
lockToken → TicketOrder 消费关系已持久化
  ↓
再以 lockToken 为条件结束 Redis 临时锁生命周期
```

MySQL 与 Redis 之间不构成一个本地原子事务。数据库提交后的 Redis 锁消费必须是条件化、幂等的：只删除仍绑定当前 `lockToken` 的 seatKey 及其 token 元数据。如果数据库已经提交但 Redis 清理失败，不能回滚或否认已成立的订单事实；遗留临时锁允许通过重试清理或原 TTL 最终收敛，而 MySQL 的 `HELD_BY_UNPAID_ORDER` 状态会阻止座位被再次成功转换为订单。

#### 4.6.7 订单成功后普通 release 的权限边界

订单创建成功后：

```text
lockToken
  → 已消费
```

此后即使再次收到相同 token 的普通释放请求，也只能按幂等完成处理：

```text
不得把 status=1 的订单座位恢复为 AVAILABLE
不得取消 TicketOrder
不得删除其他 token 的锁
```

订单创建成功后，座位释放职责正式从 `SeatLockService` 转移到 `TicketOrderService`。后续只有订单取消或订单超时流程有权在受控数据库事务中执行：

```text
HELD_BY_UNPAID_ORDER
  → AVAILABLE
```

普通 `/seats/release` 接口永远无权修改订单或 MySQL 座位状态。

#### 4.6.8 lockToken 的一次性消费与订单幂等

`lockToken` 是一次性消费凭证：

```text
一个 lockToken
最多成功创建一个 TicketOrder
```

相同 token 重复请求创建订单时不能产生第二个订单。业务语义冻结为：

```text
同一个已签发 lockToken 重试
  → 返回同一个已经创建的 TicketOrder
  → 不新建订单
```

为保证 Redis token 元数据删除或过期后仍能识别订单重试，`lockToken → TicketOrder` 的消费关系必须随订单持久化在 MySQL 中，并具备防止一个 token 对应多个订单的唯一性保障，不能只依赖 Redis。具体表字段、唯一约束和幂等实现留到订单阶段设计，本 Step 不修改现有订单表。

#### 4.6.9 最终状态职责

锁座、订单和支付的状态职责最终冻结为：

```text
Redis 无锁
+
MySQL AVAILABLE
  → 可锁

Redis TEMP_LOCKED
+
MySQL AVAILABLE
  → 用户选座中

Redis 临时锁结束
+
MySQL HELD_BY_UNPAID_ORDER
  → 已形成待支付订单

MySQL SOLD
  → 已支付售出
```

各阶段只负责自己的状态转换：

```text
Redis 临时锁：
AVAILABLE → TEMP_LOCKED

TicketOrder 创建：
TEMP_LOCKED → HELD_BY_UNPAID_ORDER

支付阶段：
HELD_BY_UNPAID_ORDER → SOLD
```

本 Step 不实现：

- `TicketOrder` Entity、Mapper 或 Service。
- 新建或修改订单表。
- 订单座位明细表。
- `ShowtimeSeat.status` 更新 SQL。
- 订单幂等代码。
- Redis 锁消费 Lua。
- 支付。
- 订单超时取消。

阶段 7 的整体方案检查与实现前冻结留待 Step 7.7。

### Step 7.7：阶段 7 锁座方案冻结

本 Step 对阶段 7 的职责、Redis 数据结构、TTL、前置校验、原子性、幂等、释放以及订单边界进行实现前检查。检查结果如下。

#### 1. 职责边界检查：通过

状态职责已统一为：

```text
Redis
  → TEMP_LOCKED
  → 只负责临时占座

MySQL tb_showtime_seat.status
  0 = AVAILABLE
  1 = HELD_BY_UNPAID_ORDER
  2 = SOLD
```

阶段 7 不存在“Redis 锁成功后立即修改 `ShowtimeSeat.status=1`”的设计。`status=1` 只允许在后续 `TicketOrder` 创建事务成功时写入；阶段 6 中的 `status=1` 只是既有数据库状态语义，不表示锁座已经实现。

#### 2. Redis Key 检查：通过

阶段 7 有效锁座数据只保留两类 Redis Key：

```text
seat:lock:{showtimeId}:{seatId}
  → lockToken

seat:lock:token:{lockToken}
  → userId
  → showtimeId
  → 规范化 seatIds
  → expireTime
```

`seat:lock:user:{userId}:{showtimeId}` 仅作为明确禁止采用的反例保留在文档中，不属于有效 Key 设计，不维护用户锁集合或其他重复索引。

#### 3. TTL 检查：通过

锁座 TTL 统一冻结为 5 分钟，并满足：

- 全部 seatKey 与 token 元数据 Key 使用同一服务端绝对过期时刻。
- 相同有效锁座的重复请求返回原 token，不刷新 TTL，不延长 `expireTime`。
- release 只执行条件删除，不续期、不重建任何锁。
- Redis 重启或临时锁丢失后，不重建旧 `TEMP_LOCKED`，旧 token 直接失去订单创建资格。

#### 4. 锁座前置校验检查：通过

进入 Redis 前必须完成以下校验和规范化：

```text
当前用户已登录
  ↓
Showtime 满足阶段 6 的有效性与关联完整性规则
  ↓
seatIds 数量为 1～6 个
  ↓
不允许重复 seatId
  ↓
按 Long 升序规范化 seatIds
  ↓
批量查询 Seat，确认全部属于目标 Hall
  ↓
确认全部 tb_seat.base_status = 1
  ↓
批量查询 ShowtimeSeat，确认数量完整
  ↓
确认全部 ShowtimeSeat.status = AVAILABLE
  ↓
进入 Redis 锁定判断
```

参数格式和重复值必须先完成低成本校验，Showtime 直接复用阶段 6 的 `MOVIE + status=1 + start_time > NOW()` 及 Venue、Hall、Movie 关联有效规则。Seat 与 ShowtimeSeat 都必须通过批量查询和结果数量比较验证完整性，禁止逐座位查询造成 N+1。

#### 5. 多座位原子性检查：通过

多座位 Redis 锁定冻结为：

```text
任意一个 seatKey 已存在
  → 整次失败
  → 不写入任何 Redis 数据

全部 seatKey 均可锁
  → 一次性写入全部 seatKey = lockToken
  → 一次性写入 token 元数据
  → 全部 Key 使用同一锁座生命周期
```

不能出现部分座位成功、部分座位失败或缺失 token 元数据的状态。Redis 内的全部检查和写入必须由一次 Lua 原子执行。

#### 6. 锁座幂等检查：通过

只有以下条件全部满足时才属于同一次有效锁座的重复请求：

```text
同一 userId
+ 同一 showtimeId
+ 完全相同的规范化 seatIds
+ 全部 seatKey 绑定同一有效 lockToken
+ token 元数据完整且未过期
```

命中后直接返回原 `lockToken + expireTime`，不生成新 token、不写 Redis、不刷新 TTL。其他请求只要包含任意已锁 seatKey，即使锁的持有者是当前用户，也返回 `SEAT_ALREADY_LOCKED`。

当前不支持扩座、减座、合并锁或修改已有锁座集合。

#### 7. release 边界检查：通过

主动释放接口保持为：

```http
POST /api/showtimes/{id}/seats/release
```

客户端请求体只允许：

```json
{
  "lockToken": "xxx"
}
```

禁止客户端提交 `seatIds`。释放范围必须从 token 元数据恢复，并且只能删除满足以下条件的锁：

```text
seatKey.value == 当前 lockToken
```

不存在的 seatKey 视为已释放；Value 已属于其他 token 时绝对不能删除。检查、条件删除 seatKey 和删除 token 元数据必须由一次 release Lua 原子完成。该 token-only 释放约束与 API 文档一致。

#### 8. TTL 与主动释放语义检查：通过

未创建订单时的状态变化固定为：

```text
AVAILABLE
  → TEMP_LOCKED
  → TTL / release
  → AVAILABLE
```

TTL 到期和主动释放都只结束 Redis 临时状态，不执行 `UPDATE tb_showtime_seat`，不需要定时任务恢复 MySQL，不需要 Redis Stream，也不扫描或重建普通临时锁。

#### 9. 订单边界检查：通过

锁座阶段只为后续订单提供一份含义不可变的凭证：

```text
lockToken
  → 唯一 userId
  → 唯一 showtimeId
  → 一组固定且规范化的 seatIds
  → 一个固定 expireTime
```

后续订单创建流程必须先查询持久化的 token 消费关系：

```text
先查询 lockToken 是否已经消费

已消费
  → 校验请求与原订单归属一致
  → 返回原 TicketOrder

未消费
  → 校验 Redis token 元数据和全部 seatKey 完整有效
  → 再校验 Showtime 与 MySQL ShowtimeSeat
  → 在数据库事务中创建 UNPAID 订单
```

`SeatLockService` 负责临时锁座，`TicketOrderService` 负责 token 消费、订单持久化和订单形成后的座位释放，两阶段职责不混用。

#### 10. 阶段 7 实现范围检查：通过

阶段 7 后续实现范围严格限制为：

- `POST /api/showtimes/{id}/seats/lock`。
- `POST /api/showtimes/{id}/seats/release`。
- `SeatLockService`。
- 锁座 Lua。
- 释放 Lua。
- Redis Key 与 TTL 常量。
- 锁座、释放所需 DTO / VO。
- 本阶段锁座相关错误码。
- 自动化测试。
- 真实 Redis、MySQL 与 HTTP 联调。

阶段 7 暂不实现：

- `TicketOrder`。
- 新建或修改订单表。
- 订单座位明细业务。
- `ShowtimeSeat.status=1` 写入。
- 支付。
- 订单超时取消。
- 出票。
- Redis Stream 订单任务。

该范围符合阶段 6 交接边界：阶段 7 先完成并冻结锁座设计，在锁座实现阶段不得提前实现订单、支付、超时取消或出票能力。

#### 阶段 7 冻结结论

阶段 7 锁座设计已完成冻结。

锁座采用 Redis 临时状态与 MySQL 最终事实的两阶段模型。多座位锁定和释放均要求 Redis Lua 原子执行。`lockToken` 是服务端签发、不可预测、含义不可变且有期限的一次锁座凭证。普通锁座阶段不修改 `tb_showtime_seat.status`，不创建订单。

该冻结范围已用于阶段 7 实现：实现内容严格限制为 `SeatLockService`、lock/release API、Redis Lua 及其 DTO、VO、常量、错误码、测试和联调，未提前实现 `TicketOrder`、支付或订单超时流程。

### Step 7.16：阶段 7 实现结果

阶段 7 已按冻结设计完成 Redis 临时锁座实现：

- Redis Seat Lock TTL 固定为 5 分钟。
- `seat:lock:{showtimeId}:{seatId}` 保存 `lockToken`。
- `seat:lock:token:{lockToken}` 保存用户、场次、规范化座位集合和过期时间 Metadata。
- 多座位 lock 使用单次 Lua 原子执行，Redis 冲突由 Lua 作最终并发兜底，确保全部成功或全部失败。
- release 使用 Lua 按 `seatKey.value == lockToken` 条件删除，不删除其他 token 的锁。
- 同一用户、同一场次、完全相同规范化座位集合的有效锁支持幂等返回，重试不刷新 TTL。
- Token 不存在、已释放或已过期时 release 保持幂等完成。
- Seat 与 ShowtimeSeat 前置校验均使用批量查询，不产生逐座位 N+1。
- 普通 lock、release 和 TTL 过期均不修改 `tb_showtime_seat.status`；MySQL 在整个临时锁生命周期内仍为 `AVAILABLE`。
- 当前 Seat Map 只读取 MySQL，不合并 Redis `TEMP_LOCKED`，因此锁座期间仍可能展示 `AVAILABLE`。这是当前阶段的明确边界，后续如需合并展示必须单独设计。

阶段 7 已通过自动化测试以及真实 HTTP、Redis、MySQL 联调，包括锁座、相同请求幂等、主动释放、重复释放和双用户并发冲突验证。阶段 7 不包含 `TicketOrder`、支付、订单超时取消或出票。

## 5. 阶段 8：订单数据模型设计

### 5.1 Step 8.1：订单数据模型与 lockToken 一次性消费关系

本 Step 只冻结订单数据模型、事务边界和 Redis 锁消费关系，不创建 Entity、Mapper、Service、Controller，也不实现 `POST /api/orders`。

#### 5.1.1 lockToken 的持久化与唯一消费

`tb_ticket_order` 增加 `lock_token VARCHAR(64) NOT NULL`，并建立唯一索引 `uk_order_lock_token(lock_token)`：

```text
一个 lockToken
→ 数据库最多对应一个 TicketOrder
```

当前服务端签发的是 32 位无连字符 UUID 字符串，字段保留至 64 位以便后续扩展，并使用 ASCII 二进制比较。`lock_token` 是订单的长期幂等事实，订单支付、取消、超时或退款后均不得清空或复用。本阶段不增加独立的 token 消费表，避免在 Redis 锁、订单和消费记录之间维护无必要的第三份状态。

订单请求进入后必须先按 `lockToken` 查询 MySQL。若订单已存在，必须校验订单 `userId`、`showtimeId` 以及订单明细中的规范化 `seatIds` 与当前请求完全一致；一致时直接返回原订单，不一致时拒绝请求。只有确认 token 尚未消费时，才读取 Redis Metadata 并执行首次创建订单校验。因此即使订单创建成功后 Redis Key 已删除，相同 token 重试仍可返回原订单。

若两个请求并发首次创建订单并命中 `uk_order_lock_token`，失败请求必须遵循：

```text
INSERT 命中唯一约束
→ 当前事务回滚
→ 在事务外或新事务中按 lockToken 查询既有订单
→ 校验 userId、showtimeId、规范化 seatIds
→ 返回原订单
```

不得在已经触发唯一键异常、可能被 Spring 标记为 rollback-only 的事务中继续查询并返回。

#### 5.1.2 Redis 锁期限与订单支付期限

锁座与订单使用两个不同的期限：

```text
lockExpireTime
→ Redis 临时锁截止时间
→ 决定 token 是否仍有资格首次创建订单

orderExpireTime
→ tb_ticket_order.expire_time
→ 决定 UNPAID 订单何时超时取消
```

订单支付窗口冻结为 15 分钟。首次创建订单时只获取一次创建基准时间，并计算：

```text
orderExpireTime = min(创建基准时间 + 15 分钟, Showtime.startTime)
```

`orderExpireTime` 必须严格晚于创建基准时间；如果事务执行边界已经跨过开场时间，导致计算结果 `<=` 创建基准时间，则首次创建失败，禁止生成创建即过期的 `UNPAID` 订单。`tb_ticket_order.expire_time` 因此为非空字段。订单支付期限不能使用 Redis 剩余 TTL 代替，订单创建成功后不再依赖临时锁继续生存。

#### 5.1.3 订单、明细与票券归属

当前订单继续限定为一个订单只属于一个 Showtime：

```text
TicketOrder.showtimeId
+
TicketOrderItem.seatId
→ 通过 (showtimeId, seatId) 唯一定位 ShowtimeSeat
```

`tb_showtime_seat` 已具有 `(showtime_id, seat_id)` 唯一约束，因此 `tb_ticket_order_item` 不增加 `showtime_seat_id`，避免同时保存可以互相矛盾的冗余关联。`uk_order_item_seat(order_id, seat_id)` 继续防止同一订单重复记录相同座位；不同历史订单可以包含同一物理座位，当前占用归属由 `ShowtimeSeat` 状态和 `lock_order_id` 决定。

“一座一票”继续作为现行模型：`ticket_code` 和 `ticket_status` 保存在 `tb_ticket_order_item`，一个订单的每个座位明细分别对应一张电子票。订单创建时 `ticket_code` 允许为空，支付成功后的出票阶段再生成，并继续由 `uk_ticket_code` 保证唯一。

#### 5.1.4 金额快照

订单价格只能由服务端读取数据库计算：

```text
ShowtimeSeat.price
→ TicketOrderItem.price

全部 item.price 求和
→ TicketOrder.total_amount
```

客户端不得提交或决定订单价格。`TicketOrderItem.price` 是下单时的座位价格快照，`TicketOrder.total_amount` 必须等于全部明细金额之和，并与订单、明细及座位占用在同一数据库事务中落库。

#### 5.1.5 创建订单的数据库事务

首次消费有效 token 时，以下操作必须在同一个 MySQL 事务中完成：

```text
1. 创建 TicketOrder
   status = UNPAID
   lock_token = 当前 lockToken
   expire_time = orderExpireTime

2. 批量创建全部 TicketOrderItem

3. 批量条件更新全部 ShowtimeSeat
   AVAILABLE → HELD_BY_UNPAID_ORDER
   lock_order_id = 当前 orderId

4. 同步减少 Showtime.available_seats

5. 校验全部影响行数

6. 任一步失败 → 整个事务回滚
```

ShowtimeSeat 必须一次批量更新，条件至少包含：

```text
showtime_id = 当前 showtimeId
seat_id IN (规范化 seatIds)
status = AVAILABLE
lock_order_id IS NULL
```

更新时写入 `status=HELD_BY_UNPAID_ORDER`、`lock_order_id=当前 orderId`，并递增 `version`。实际更新数量必须等于 `seatIds.size()`；禁止逐座位更新、无条件更新或接受部分成功。

#### 5.1.6 available_seats 的职责

`tb_showtime.available_seats` 只是便于查询的派生余票计数，不能提升为售票判断依据。座位是否可售仍以 `tb_seat.base_status` 和 `tb_showtime_seat.status` 为最终事实。

创建 `UNPAID` 订单时，在同一事务中执行等价于以下条件更新的操作：

```sql
UPDATE tb_showtime
SET available_seats = available_seats - :seatCount
WHERE id = :showtimeId
  AND available_seats >= :seatCount;
```

影响行数必须为 1；否则整个订单事务回滚，并按内部数据一致性异常处理，不能包装成普通“库存不足”。订单取消或超时时在其受控事务中增加相同数量；`UNPAID → PAID` 不再改变计数。阶段 7 的 Redis lock、release 和 TTL 到期始终不修改 `available_seats`。

#### 5.1.7 lock_order_id 生命周期

逻辑归属固定为：

```text
AVAILABLE
status = 0
lock_order_id = NULL

创建订单
status = 1 (HELD_BY_UNPAID_ORDER)
lock_order_id = 当前 orderId

支付成功
status = 2 (SOLD)
lock_order_id 继续保留当前 orderId

取消或超时
status = 0 (AVAILABLE)
lock_order_id = NULL
```

后续支付、取消和超时操作不能只检查 `status=1`，还必须要求 `lock_order_id=当前 orderId`，确保订单 A 不能售出或释放订单 B 的座位。当前 SQL 已为 `lock_order_id` 建立索引；本 Step 不强制增加物理外键，关系完整性由条件更新、事务和 Service 校验保证，是否增加外键留待后续数据库约束专项调整。

#### 5.1.8 数据库提交后的 Redis 条件清理

数据库事务提交前禁止删除 Redis 临时锁。固定顺序为：

```text
完整校验 Redis
→ 执行订单数据库事务
→ COMMIT
→ 按 lockToken 条件清理 Redis
```

提交后的清理继续沿用阶段 7 的所有权语义：只有 `seatKey.value == 当前 lockToken` 才允许删除对应 seatKey，禁止无条件 `DEL`，token 元数据清理也必须幂等。如果数据库已经提交但 Redis 清理失败，不得回滚或否认已经成立的订单事实；残留临时锁只能通过重试清理或原 TTL 最终收敛，MySQL 中的订单和 `HELD_BY_UNPAID_ORDER` 状态仍是最终事实。

#### 5.1.9 Step 8.1 冻结结论

```text
Redis Metadata
= 首次创建订单前的临时资格证明

tb_ticket_order.lock_token UNIQUE
= Token 已消费后的长期幂等事实

tb_showtime_seat.status + lock_order_id
= 座位订单归属的最终事实

Redis 清理
= 必须发生在数据库 COMMIT 之后
```

阶段 8 订单数据模型采用 Redis 临时资格、MySQL token 唯一消费关系和 MySQL 场次座位最终状态的分层职责。普通锁座仍不修改 MySQL；只有订单事务成功时才将全部座位从 `AVAILABLE` 转换为 `HELD_BY_UNPAID_ORDER`。本 Step 只同步 SQL 草案和设计文档，不实现订单 Java 代码、支付、取消、超时任务、出票或 Redis Stream。

### 5.2 Step 8.2：订单基础持久化模型

本 Step 只建立 Step 8.1 已冻结表结构对应的 Java 持久化层，不实现创建订单业务。`TicketOrder` 与 `TicketOrderItem` 逐字段映射 `tb_ticket_order` 和 `tb_ticket_order_item`，主键沿用 MyBatis-Plus 自增策略；金额使用 `BigDecimal`，数据库 `DATETIME` 使用 `LocalDateTime`。

订单状态以 `TicketOrderStatus` 定义 `UNPAID`、`PAID`、`CANCELED`、`REFUNDED`，票状态以 `TicketStatus` 定义 `PENDING(0)`、`VALID(1)`、`USED(2)`、`REFUNDED(3)`。Entity 仍保存数据库原始的 `String` 或 `Integer` 值，避免在本 Step 提前引入类型处理器或状态流转逻辑。

`TicketOrderMapper` 与 `TicketOrderItemMapper` 仅继承 MyBatis-Plus `BaseMapper`。真实 MySQL 映射测试覆盖订单和明细的插入、自动生成主键、全部字段回读、`pay_time` 与 `ticket_code` 可空，以及数据库生成的 `create_time`。本 Step 不新增 DTO、VO、Service、Controller，不读取或清理 Redis，不更新 `ShowtimeSeat` 或 `available_seats`。

Step 8.2 已完成真实数据库验证：本地 `tb_ticket_order` 已同步 Step 8.1 冻结的 `lock_token`、`expire_time NOT NULL` 和 `uk_order_lock_token`；订单及明细映射测试在事务中完成插入和回读后自动回滚。全量自动化测试共 189 项，0 failure、0 error，Maven `clean package` 构建成功。本 Step 至此冻结，后续创建订单事务仍由新的独立 Step 实现。

### 5.3 Step 8.3：订单创建前置校验

本 Step 冻结 `CreateTicketOrderDTO`、`CreateTicketOrderVO` 和 `TicketOrderService.createOrder(userId, dto)` 入口，但不提供 Controller，也不创建订单。为了避免对“校验通过但尚无订单”的请求伪造 VO，校验逻辑独立为 `TicketOrderCreationValidator`，返回可由 Step 8.4 复用的内部 `TicketOrderCreationContext`。

校验顺序固定为：服务端再次校验参数并规范化座位 ID；按 `lockToken` 查询 MySQL；已存在订单时校验用户、场次及一次批量查询得到的订单明细座位集合，完全一致则返回已有订单上下文，全程不访问 Redis，也不限制原订单当前状态。仅在 MySQL 不存在对应订单时读取 `seat:lock:token:{lockToken}`，校验 Metadata 的用户、场次、已规范化座位集合和绝对过期时间，再对全部 `seat:lock:{showtimeId}:{seatId}` 执行一次 `MGET`，要求每个值都严格等于当前 token。

本 Step 的 Redis 行为严格只读，不执行 `SET`、`DEL`、`EXPIRE` 或 Lua，不刷新 TTL。校验器不检查 Showtime 有效性、ShowtimeSeat 状态或价格，不计算金额，不插入订单或明细，不修改 `ShowtimeSeat`、`available_seats`，也不清理 Redis；这些数据库交易职责留给 Step 8.4。

Step 8.3 已完成 22 项新增自动化测试和真实 MySQL、Redis 联调。未消费 token 场景验证了 Metadata 与全部 seatKey 后仍保留原 Key、未刷新 TTL，且订单表、ShowtimeSeat 和 `available_seats` 均未变化；已消费 token 场景在 Redis Key 不存在且订单状态为 `PAID` 时仍以 MySQL 订单事实完成幂等命中。全量自动化测试共 211 项，0 failure、0 error，Maven `clean package` 构建成功。本 Step 至此冻结，不开放 `POST /api/orders`。

### 5.4 Step 8.4：创建订单数据库事务

本 Step 在 `TicketOrderServiceImpl` 中完成创建编排，并将数据库写入隔离到独立 Spring Bean `TicketOrderTransactionService`，避免同类方法调用导致 `@Transactional` 失效。外层先复用 Step 8.3 校验器：已消费 token 直接返回原订单；未消费 token 才进入数据库事务。

事务内实际顺序冻结为：复用阶段 6 规则确认 Showtime 有效；一次批量查询目标 `ShowtimeSeat` 并读取各座位价格；计算 `orderExpireTime = min(now + 15 分钟, Showtime.startTime)`；插入 `UNPAID` 的 `TicketOrder`；一次批量插入全部 `TicketOrderItem`；一次条件 UPDATE 将全部座位更新为 `HELD_BY_UNPAID_ORDER` 并绑定 `lock_order_id`、递增 `version`；最后条件扣减 `Showtime.available_seats`。每个写入步骤均严格检查影响行数，任何不一致都会抛出异常并回滚整个事务。

金额完全来自 `ShowtimeSeat.price`：每条订单明细保存对应座位的价格快照，订单总金额为全部明细价格之和，不使用客户端金额，也不以 `Showtime.base_price × 座位数` 代替。当前项目没有独立 `RedisIdWorker`，因此订单 ID 继续沿用 Step 8.2 已冻结的 MySQL 自增主键策略，没有引入第二套 ID 生成器。

`ShowtimeSeat` 最终占座 SQL 同时要求 `showtime_id`、完整 `seat_id IN (...)`、`status=AVAILABLE` 和 `lock_order_id IS NULL`，实际影响行数必须等于请求座位数；`available_seats` 使用 `available_seats >= seatCount` 条件原子扣减且影响行数必须为 1。Java 查询仅用于给出清晰错误，数据库条件更新及事务回滚才是最终并发兜底。

并发线程同时通过“token 尚未消费”校验时，`uk_order_lock_token` 仍是最终一次性消费约束。唯一键冲突会先结束并回滚失败事务，再由事务外层重新执行已消费订单一致性校验；请求用户、场次和座位完全一致时返回赢家订单，否则继续拒绝或抛出原异常，不在失败事务内继续查询。

本 Step 的订单业务代码不执行任何 Redis 写操作。数据库提交后，token Metadata 和全部 seatKey 仍保持原值及原 TTL；条件清理留给 Step 8.5。`POST /api/orders` Controller 同样尚未开放，支付、取消、超时、出票、退款和 Redis Stream 均不属于本 Step。

Step 8.4 新增 14 项自动化测试。真实 MySQL/Redis 联调覆盖两种座位价格形成订单与明细快照、批量占座及余票扣减、近场次支付截止时间、提交后 Redis Key 及 TTL 不变；还覆盖 `available_seats` 条件更新失败时订单、明细和座位更新整体回滚，并在修复计数后使用同一仍有效 token 重试成功。全量自动化测试共 225 项，0 failure、0 error，Maven `clean package` 构建成功；测试订单、明细及联调基础数据均已删除，Redis `seat:lock:*` 无残留。本 Step 至此冻结。

### 5.5 Step 8.5：提交后的 Redis 条件清理与完整创建编排

完整 Service 编排冻结为：`TicketOrderCreationValidator` 先识别 token 是否已消费；未消费时调用独立的 `TicketOrderTransactionService` 完成 MySQL 事务；只有事务代理正常返回、即数据库已经 COMMIT 后，`TicketOrderServiceImpl` 才执行 Redis 清理。事务服务不持有 Redis 依赖，Redis 异常不会把已提交的订单标记为回滚。

订单清理直接复用阶段 7 已验证的 `seat_release.lua`，并为同一脚本增加显式 seatKey 的兼容入口；阶段 7 原有 release 模式和条件删除规则保持不变。`SeatLockService.cleanupLock(showtimeId, seatIds, lockToken)` 使用已校验的订单上下文构造 tokenKey 和全部 seatKey，不重复执行用户鉴权，也不修改任何 MySQL 状态。Lua 仅删除 `seatKey.value == lockToken` 的 seatKey，随后删除 token Metadata；即使 Metadata 已不存在，仍能清理上下文中属于该 token 的残留 seatKey。相关 Key 已不存在时按幂等成功处理，已被其他 token 重新拥有的 seatKey 必须保留。

清理采用最多两次同步尝试。第一次 Redis 异常后立即重试一次；仍失败时记录包含 `orderId`、`lockToken`、`showtimeId` 和 `seatIds` 的错误日志，但 `CreateTicketOrderVO` 仍正常返回。残留临时锁由相同 token 的后续幂等请求再次 best-effort 清理，或由阶段 7 的原 TTL 最终收敛，不引入 Redis Stream、补偿队列或定时任务。

MySQL 已存在订单时仍以持久化 token 消费事实为先：请求一致即返回原订单，不要求 Redis Metadata 存在。返回前会顺便执行一次相同的条件清理，因此既能收敛上一次 Redis 故障，也不会无条件删除后来产生的新锁。唯一 token 竞态的赢家订单在失败事务回滚并重新查询成功后同样进入该清理路径。

本 Step 仅完成 Service 级闭环，未新增或开放 `POST /api/orders` Controller，也不实现支付、取消、超时、出票、退款或 Redis Stream。

Step 8.5 新增 5 项自动化测试，全量共 230 项，0 failure、0 error，Maven `clean package` 构建成功。真实 MySQL/Redis 联调确认正常创建后订单、明细、座位归属和余票扣减均已提交，随后全部临时锁 Key 被清理；相同 token 在 Metadata 已删除后仍返回原订单且不重复扣减。条件清理重复执行保持成功，seatKey 已改为其他 token 时不会被误删；模拟 Redis 连续异常时订单结果仍正常返回，并覆盖下一次幂等请求再次清理。测试数据和 Redis Key 已清理，本 Step 至此冻结。

### 5.6 Step 8.6：开放订单创建 HTTP 接口

新增 `TicketOrderController` 并正式开放 `POST /api/orders`。Controller 使用 `@LoginRequired` 接入现有认证拦截器，通过 `UserHolder.getUserId()` 获取当前用户，复用 `CreateTicketOrderDTO` 的 Bean Validation 和 `CreateTicketOrderVO`，不读取 Redis、不调用 Mapper、不参与事务，也不捕获业务异常。参数和业务异常统一交由 `GlobalExceptionHandler` 映射为稳定的 HTTP 状态与 `Result<T>` 结构。

HTTP 创建链路为：登录用户请求 Controller；`TicketOrderService` 执行 token 校验和 MySQL 已消费判断；未消费时进入独立订单事务；事务 COMMIT 后条件清理 Redis；最后返回 `orderId`、`status`、`totalAmount` 和 `expireTime`。相同 token 在 Redis Metadata 和 seatKey 已清理后再次请求，会直接命中 MySQL 原订单，不重复创建明细、占座或扣减 `available_seats`。

本 Step 的真实 HTTP 联调完整经过登录、场次详情、Seat Map、Redis lock 和订单创建，随后直接核对订单、明细价格快照、ShowtimeSeat 状态与 `lock_order_id`、余票扣减及 Redis Key 清理；还验证了相同 token HTTP 重试返回相同 orderId、不同座位请求返回 `409 ORDER_REQUEST_MISMATCH`、其他登录用户返回 `403 ORDER_NOT_OWNED`，且数据库状态均不再变化。

本 Step 只开放创建接口，未新增 `GET /api/orders`、`GET /api/orders/{id}`，也不实现支付、取消、订单超时、出票、退款或 Redis Stream。

Step 8.6 新增 10 项 HTTP 自动化测试，全量共 240 项，0 failure、0 error，Maven `clean package` 构建成功。Controller 切片测试覆盖登录接入、请求 DTO 校验及订单业务错误到 HTTP 状态的统一映射；真实 MySQL/Redis HTTP 联调覆盖登录、查询场次与 Seat Map、锁座、创建订单、提交后清理临时锁、相同 token 幂等重试、请求不一致和非订单持有者访问。联调结束后订单、明细、测试场次座位和 Redis 临时锁均已清理，本 Step 至此冻结。

### 5.7 Step 8.7：订单列表与详情只读接口

正式开放 `GET /api/orders` 和 `GET /api/orders/{id}`，两者均通过 `@LoginRequired` 使用服务端登录用户 ID。`TicketOrderService` 增加当前用户分页列表和订单详情入口，具体读取逻辑隔离在不依赖 Redis 的 `TicketOrderQueryService`；查询过程不修改订单、明细、ShowtimeSeat、`available_seats` 或任何 Redis Key，也不根据过期时间改变订单状态。

订单列表沿用项目 `PageVO` 和页大小 1～100 的统一约束，固定按 `create_time DESC, id DESC` 排序。每页只执行订单分页、全部订单明细、Showtime、Movie、Venue、Hall 六类查询；后五类均按本页 ID 集合批量读取，座位数由明细在内存中按 `orderId` 分组计算，避免逐订单查询产生 N+1。空结果正常返回空列表。

订单详情先按主键读取订单，并将不存在和非当前用户所有统一映射为 `404 ORDER_NOT_FOUND`。合法订单的 Showtime、Movie、Venue、Hall 分别读取，全部订单明细一次查询，所有 Seat 再按 seatId 集合一次批量查询；最终明细按 `rowNo ASC, colNo ASC` 排序，价格、`ticketCode` 和 `ticketStatus` 均直接返回订单明细快照。任一必要关联或明细缺失统一视为 `INTERNAL_ERROR`，不静默省略不完整数据。

真实 HTTP 验证链路扩展为登录、Seat Map、锁座、创建订单、当前用户列表和订单详情；列表同时使用同用户历史订单及其他用户订单验证倒序与用户隔离，详情验证价格快照、座位顺序和其他用户访问返回 404。GET 请求前后对订单、明细、ShowtimeSeat、Showtime 和相关 Redis 值做快照比对，确认查询没有产生状态变化。

本 Step 不实现支付、取消、超时状态处理、出票、退款或 Redis Stream。

Step 8.7 新增 16 项自动化测试，全量共 256 项，0 failure、0 error，Maven `clean package` 构建成功。Controller 测试覆盖登录、分页、空列表、非法参数、本人详情、隐藏他人订单和不存在订单；Service 测试覆盖批量关联查询次数、座位计数、详情快照与排序、分页边界和关联数据异常。真实 MySQL/Redis HTTP 联调从创建订单继续执行列表与详情查询，验证当前用户隔离、稳定倒序和 404 防泄露语义，并确认查询前后数据库及 Redis 状态不变。测试订单、明细、场次座位和 Redis 临时锁均已清理，本 Step 至此冻结。

### 5.8 Step 8.8：未支付订单主动取消与座位释放

正式开放 `POST /api/orders/{id}/cancel`。Controller 仅传递当前登录用户 ID 和订单 ID；订单不存在或不属于当前用户统一返回 `ORDER_NOT_FOUND`，不泄露资源存在性。`CANCELED` 重复请求直接幂等成功，只有 `UNPAID` 可以进入取消事务，`PAID`、`REFUNDED` 等状态返回 `ORDER_STATUS_CONFLICT`。主动取消不判断 `expireTime`，已过支付截止时间但数据库状态仍为 `UNPAID` 时依然允许用户取消；自动超时处理留给 Step 8.9。

取消事务隔离在 `TicketOrderCancellationService`。事务先以 `SELECT ... FOR UPDATE` 锁定订单行，再一次查询全部订单明细；随后使用一个条件 UPDATE 将全部 `ShowtimeSeat` 从 `HELD_BY_UNPAID_ORDER` 更新为 `AVAILABLE`、清空 `lock_order_id` 并递增 `version`，条件严格包含当前 showtime、完整 seatId 集合、`status=1` 和 `lock_order_id=当前订单`。实际释放行数必须等于订单明细数，否则整体回滚，不能部分释放或释放其他订单的座位。

座位全部释放后，同一事务原子增加 `Showtime.available_seats`，影响行数必须为 1，并额外要求恢复后不超过 `total_seats`；最后以 `id + user_id + status=UNPAID` 条件将订单更新为 `CANCELED`。任一行数不一致或 SQL 异常都会回滚此前的座位、余票和订单变化，订单明细始终保留不变。

订单行锁使并发取消在事务入口串行化：首个请求完成状态转换和资源恢复，后续请求读取到 `CANCELED` 后幂等返回，因此余票只增加一次。未来支付若同样遵守订单行锁或 `status=UNPAID` 条件更新，就只能与取消有一个状态转换成功。取消链路不依赖 `SeatLockService`，不读取、不创建也不删除 Redis 临时锁。

真实事务测试覆盖正常及重复取消、过期 UNPAID 主动取消、PAID 状态冲突、部分座位异常、`lock_order_id` 错误归属、余票上界异常整体回滚和双线程并发取消；真实 HTTP 链路覆盖创建、详情、取消、取消后详情和 Seat Map，并验证其他用户返回 404、PAID 返回 409、重复取消返回 200，以及取消前后 Redis 不产生临时锁。

本 Step 不实现自动超时取消、支付、出票、退款或 Redis Stream。

Step 8.8 新增 19 项自动化测试，全量共 275 项，0 failure、0 error，Maven `clean package` 构建成功。正常取消、重复取消、资源隔离、非法状态、全部回滚路径和双线程并发均通过；真实 HTTP 验证完成创建订单、查询详情、主动取消、取消后详情及 Seat Map 闭环。取消后订单明细保持不变，全部座位恢复可用、`lock_order_id` 清空、余票仅恢复一次，Redis 未产生任何订单取消状态。联调数据和 Redis 临时锁均已清理，本 Step 至此冻结。

### 5.9 Step 8.9：UNPAID 订单自动超时取消

应用启用 Spring Scheduling，并新增 `TicketOrderTimeoutJob`。任务默认初始延迟和固定延迟均为 60 秒，每次只查询最多 100 个 `status=UNPAID AND expire_time<=扫描时间` 的订单 ID，按 `expire_time ASC, id ASC` 稳定排序。Job 不开启整批事务，仅负责扫描和逐个调用取消服务；单个订单抛出异常时记录 `orderId` 并继续处理本批其他订单。

超时取消入口在独立 MySQL 事务中以 `SELECT ... FOR UPDATE` 锁定订单行，再以事务内当前时间重新确认订单仍为 `UNPAID` 且 `expire_time<=now`。订单已经 `CANCELED`、已经 `PAID`、尚未到期、缺少过期时间或已不存在时直接跳过，不释放座位、不恢复余票，也不抛出影响整批任务的业务异常。

满足超时条件时，复用 Step 8.8 的取消核心：一次读取全部订单明细，按 `showtime_id + seatIds + status=HELD_BY_UNPAID_ORDER + lock_order_id=当前订单` 批量释放座位并严格校验释放行数，同一事务恢复 `available_seats` 且不允许超过 `total_seats`，最后以 `id + status=UNPAID + expire_time<=now` 条件将订单更新为 `CANCELED`。任一步失败均回滚该订单的全部变化。订单行锁保证超时任务与用户主动取消并发时只有一方真正释放资源，另一方读取最终状态后跳过或幂等成功。

自动超时不增加 `EXPIRED` 状态、不新增 HTTP 接口，也不依赖或操作 Redis 临时锁；支付、出票、退款和 Redis Stream 仍不属于本 Step。

Step 8.9 新增 10 项自动化测试，全量共 285 项，0 failure、0 error，Maven `clean package` 构建成功。测试覆盖正常超时取消、尚未到期跳过、已取消及已支付跳过、部分座位异常整体回滚、批量扫描筛选、单订单异常隔离，以及主动取消与超时任务并发时座位和余票只恢复一次；真实 MySQL/Redis 集成验证确认订单、座位和余票状态正确，扫描前后 Redis 临时锁集合保持不变。测试订单、明细、场次座位和 Redis 临时锁均已清理，本 Step 至此冻结。

### 5.10 Step 8.10：模拟支付与座位售出

正式开放 `POST /api/orders/{id}/pay`，不接收请求体或支付参数，当前只模拟支付成功。Controller 仅传递登录用户和订单 ID，返回包含 `orderId`、`status`、`payTime` 的 `PayTicketOrderVO`；订单不存在或不属于当前用户统一返回 `ORDER_NOT_FOUND`。`PAID` 重复支付直接返回原始 `payTime`，`CANCELED`、`REFUNDED` 等状态返回 `ORDER_STATUS_CONFLICT`，`UNPAID` 订单在锁行后的事务内时间已经到期时返回 `ORDER_EXPIRED`，不在支付事务中顺手取消订单。

支付逻辑隔离在 `TicketOrderPaymentService` 的独立 MySQL 事务中。事务先以 `SELECT ... FOR UPDATE` 锁定订单行并重新校验归属、状态与 `expire_time>payTime`；随后一次查询全部订单明细，使用单条条件 UPDATE 按当前 showtime、完整 seatId 集合、`status=HELD_BY_UNPAID_ORDER` 和 `lock_order_id=当前订单` 将全部座位更新为 `SOLD`，递增 `version` 并保留 `lock_order_id`。影响行数必须等于明细数，否则订单与全部座位变化整体回滚。

座位全部售出后，以 `id + status=UNPAID + expire_time>payTime` 条件将订单更新为 `PAID` 并写入秒精度 `pay_time`，影响行数必须为 1。创建 UNPAID 订单时已经扣减余票，因此支付事务不依赖 `ShowtimeMapper`，不修改 `available_seats`；同时不生成 `ticketCode`、不改变 `ticket_status`，也不依赖或操作 Redis。

支付、主动取消和超时取消使用同一个订单行锁作为串行化边界：双支付只有首个请求推进状态，后续请求幂等读取首次支付时间；支付与取消竞争后只可能得到 `PAID + SOLD + lock_order_id保留` 或 `CANCELED + AVAILABLE + lock_order_id清空`。支付与超时取消在真正获得锁后再次判断状态和截止时间，过期订单不会被支付覆盖。

本 Step 不接入第三方支付平台，不生成票码，不实现出票、退款或 Redis Stream。

Step 8.10 新增 21 项自动化测试，全量共 306 项，0 failure、0 error，Maven `clean package` 构建成功。测试覆盖正常支付、重复支付保留首次 `payTime`、过期与非法状态拒绝、座位异常整体回滚、双支付并发、支付与主动取消并发、支付与超时取消并发，以及真实 HTTP 登录、锁座、创建、支付、详情和 Seat Map 闭环；支付前后 `available_seats` 与 Redis 临时锁集合均保持不变，订单明细仍未生成票码。测试订单、明细、场次座位和 Redis 临时锁均已清理，本 Step 至此冻结。

### 5.11 Step 8.11：PAID 订单出票

`POST /api/orders/{id}/issue` 通过登录上下文获取用户，委托独立 `TicketOrderIssuanceService` 事务。使用现有订单 `SELECT ... FOR UPDATE` 串行化双出票请求，校验本人及 PAID 状态，一次查询全部明细并检查票码与状态一致性。冻结票状态沿用 `TicketStatus.PENDING(0)` 和 `VALID(1)`；VALID 即已出票可用。

未出票明细使用 `UUID.randomUUID()` 去掉连字符生成 32 位不可预测票码，一座一票，`uk_ticket_code` 作为全局唯一性兜底。批量 CASE UPDATE 同时限制 `order_id`、待出票明细 ID、`ticket_code IS NULL`、初始状态，严格要求影响行数等于待出票数量。异常或唯一约束冲突传播至事务边界并整体回滚，不吞掉异常。

已有有效票码保持不变，支持部分出票补齐；全部已出票直接幂等返回。异常组合（如票码非空但状态 PENDING）统一拒绝，返回 INTERNAL_ERROR。返回 `List<IssueTicketVO>`，含 seatId、ticketCode、ticketStatus；详情结构直接复用既有字段。出票服务仅依赖订单和明细 Mapper，订单 PAID、ShowtimeSeat SOLD、lock_order_id、available_seats 均不变化，Redis 不参与。

本 Step 不实现真实支付、验票核销、退款或 Redis Stream。

Step 8.11 新增 9 项自动化测试，并扩展真实 HTTP 支付闭环为 create → pay → issue → detail。全量共 315 项，0 failure、0 error、0 skipped，Maven `clean package` 构建成功。验证正常及重复出票、部分补齐、非 PAID 拒绝、用户隔离、异常明细、并发一致性、批量行数不足异常传播和真实 MySQL 唯一冲突回滚。出票前后订单、座位及余票快照一致，Redis 临时锁集合不变，测试数据已清理，本 Step 至此冻结。

### 5.12 Step 8.12：整单退款与已售座位释放

正式开放 `POST /api/orders/{id}/refund`。Controller 仅传递登录用户与订单 ID；不存在或非本人统一返回 `404 ORDER_NOT_FOUND`。退款服务在独立事务中使用 `SELECT ... FOR UPDATE` 锁订单行：只有 `PAID` 可以推进为 `REFUNDED`，已经 `REFUNDED` 直接幂等成功，`UNPAID` 与 `CANCELED` 返回 `409 ORDER_STATUS_CONFLICT`。锁行后读取 Showtime 并严格要求 `start_time > now`；已开场返回 `409 REFUND_NOT_ALLOWED`，不改写任何数据。

事务一次查询全部订单明细，按 `showtime_id + 完整 seatIds + status=SOLD + lock_order_id=当前订单` 批量执行 `SOLD → AVAILABLE`、清空 `lock_order_id` 并递增座位版本；影响行数必须等于明细数。随后使用既有上界保护恢复 `available_seats`（不得超过 `total_seats`），条件更新 `PAID → REFUNDED`，并批量将 PENDING 或 VALID 明细更新为 `TicketStatus.REFUNDED(3)`。任一条件更新数量异常都使整个事务回滚，因此不会出现部分释放或重复增加余票。

退款保留 `pay_time`、`lock_token`、所有订单明细与既有 `ticketCode`，仅使票状态失效；不读取或写入 Redis。订单行锁和状态条件更新保证两个并发退款中仅一个实际释放资源，后到请求读取 `REFUNDED` 后幂等成功。本 Step 不实现真实支付平台退款、退款审核、部分退款、部分座位退款、验票核销或 Redis Stream。

Step 8.12 新增 13 项自动化测试，并将真实 HTTP 闭环扩展为 create → pay → issue → refund → detail → Seat Map。全量共 328 项，0 failure、0 error、0 skipped，Maven `clean package` 构建成功。验证正常与重复退款、用户隔离、非 PAID 与已开场拒绝、座位条件异常整体回滚、余票上界保护、并发退款和票码保留；测试结束后订单、明细、座位与 Redis 临时锁均无残留，本 Step 至此冻结。

### 5.13 Step 8.13：阶段 8 订单模块最终冻结

阶段 8 的已完成 HTTP 接口为 `POST /api/orders`、`GET /api/orders`、`GET /api/orders/{id}`、`POST /api/orders/{id}/cancel`、`POST /api/orders/{id}/pay`、`POST /api/orders/{id}/issue` 与 `POST /api/orders/{id}/refund`。订单状态机冻结为：`UNPAID → CANCELED`（主动或超时取消）、`UNPAID → PAID → REFUNDED`，其中已 CANCELED 的支付和退款、已 REFUNDED 的支付均为冲突；已 REFUNDED 的重复退款保持幂等成功。

座位状态与余票规则冻结为：创建订单 `AVAILABLE → HELD_BY_UNPAID_ORDER` 并扣减 `available_seats`；支付 `HELD_BY_UNPAID_ORDER → SOLD`，余票不变；主动取消或超时取消 `HELD_BY_UNPAID_ORDER → AVAILABLE` 并恢复余票；退款 `SOLD → AVAILABLE`、清空 `lock_order_id` 并恢复余票。所有座位批量条件更新均以完整影响行数作为成功条件，递增 `version`；余票更新始终要求 `0 <= available_seats <= total_seats`，任一步异常则回滚整个订单事务。

票券规则冻结为：创建时 `ticketCode=null`、`ticket_status=PENDING(0)`；出票为每张明细生成全局唯一的 32 位 UUID 票码并更新至 `VALID(1)`，重复出票保持票码不变；退款保留票码历史并更新为 `REFUNDED(3)`。`uk_ticket_code`、`uk_order_lock_token`、`uk_order_item_seat(order_id, seat_id)`、非空 `lock_token` 与 `expire_time` 是当前 SQL 和真实数据库共同遵守的关键约束。

Redis 仅承担订单创建前的 5 分钟临时锁资格及数据库提交后的条件清理；订单 `lock_token` 插入成功后成为长期幂等事实，同 token 重试不依赖 Redis。订单查询、主动取消、超时取消、支付、出票和退款均不读取或写入 Redis。订单行锁及状态条件更新串行化并发创建、取消、超时、支付、出票和退款，回归覆盖确认每次竞争仅形成一种合法终态。

本阶段最终不包含第三方支付或回调、Redis Stream/MQ、部分取消或部分退款、退款审核、验票核销及活动票务。阶段 8 的代码与文档在提交 `23fb9a2` 后进入最终验收；本 Step 仅追加冻结记录，不再新增业务能力。

## 6. 阶段 9：验票核销设计

### 6.1 Step 9.1：核销职责与数据契约

阶段 9 只扩展票明细生命周期，不重新设计阶段 8 订单交易。单张已出票电影票的状态变化冻结为 `VALID(1) → USED(2)`；TicketOrder 仍为 `PAID`，对应 ShowtimeSeat 仍为 `SOLD` 并保留原 `lock_order_id`，Showtime.available_seats 不增不减。核销处理的是 MySQL 中已经成立的票券事实，不读取或写入 `seat:lock:*`、token Metadata 或其他 Redis 状态。

管理端核销入口规划为 `POST /api/admin/tickets/verify`，Step 9.1 只冻结契约，尚不开放接口。请求只提交 `ticketCode`，不得提交用户、场馆、订单、状态或核销时间。Venue 必须由服务端沿 `TicketOrderItem → TicketOrder → Showtime` 关系推导，不能信任客户端场馆参数。票码作为核销凭证放在 JSON 请求体中，禁止放入 URL、Query 参数或普通访问日志；业务日志只记录掩码或不可逆摘要。

普通登录态不代表核销权限。新增 `tb_venue_staff` 作为场馆工作人员授权事实，字段为 `user_id`、`venue_id`、`status` 及创建更新时间，使用 `uk_venue_staff_user_venue(user_id, venue_id)` 防止重复关系，并使用 `idx_venue_staff_venue_status(venue_id, status)` 支持按场馆和有效状态校验。只有关系存在且 `status=1` 时才允许核销该 Venue 下 Showtime 的票；停用关系不能继续核销。工作人员关系删除或停用不应抹除既有核销记录。

`tb_ticket_order_item` 在现有 `ticket_code`、`ticket_status` 基础上增加 `used_time` 和 `used_by_user_id`。二者在 PENDING、VALID 和 REFUNDED 状态下均为 NULL；首次成功核销时与 `ticket_status=USED(2)` 同一事务写入，之后不可覆盖。`used_by_user_id` 外键指向 `tb_user.id`，不直接外键到可停用的工作人员关系；`idx_order_item_used_by_time(used_by_user_id, used_time)` 支持按核销人员和时间审计。`chk_order_item_ticket_status` 将票状态限制在 0～3，`chk_order_item_used_evidence` 保证 USED 必须同时具有核销时间和人员，而其他状态两项证据均为空。当前 SQL 文件冻结目标结构，Step 9.1 不执行真实数据库 ALTER，也不新增 Entity 或 Mapper，实际迁移与持久化验证属于后续独立 Step。

### 6.2 Step 9.1：状态、权限与错误语义

核销成功必须同时满足：关联 TicketOrder 为 `PAID`；目标 TicketOrderItem 为 `VALID(1)`；票码存在且唯一定位一条明细；当前登录用户与 Showtime 所属 Venue 存在有效工作人员关系。多座位订单允许逐张核销，每次只改变一个票码对应的明细，不批量改变同订单其他票。

PENDING 票返回 `TICKET_NOT_ISSUED`，USED 票返回 `TICKET_ALREADY_USED`，REFUNDED 票返回 `TICKET_REFUNDED`，未知票码返回 `TICKET_NOT_FOUND`，非 PAID 订单返回 `ORDER_STATUS_CONFLICT`，无目标场馆权限返回 `TICKET_VERIFY_FORBIDDEN`。重复扫描 USED 票不算成功，不重复写入核销时间和人员。请求校验失败使用 `INVALID_PARAM`，未登录使用 `UNAUTHORIZED`，关联数据残缺或状态与核销证据矛盾时使用不暴露内部结构的 `INTERNAL_ERROR`。

退款继续是整单操作，不支持部分退款。只要订单任一 TicketOrderItem 已为 USED，退款必须在任何座位释放、余票恢复、订单更新或票状态更新之前返回 `REFUND_NOT_ALLOWED`。这属于阶段 9 对既有退款前置校验的明确依赖，不改变阶段 8 的订单状态机，也不能把 USED 明细造成的批量更新行数不足当作普通 `INTERNAL_ERROR`。

Step 9.1 不增加基于 Showtime 开始或结束时间的额外核销窗口；实现阶段不得自行发明时间限制。如后续确需提前入场窗口、迟到截止或散场后核销规则，必须另开独立设计 Step。当前也不实现二维码签名、离线核销、批量核销、核销撤销、设备管理、Activity 票务或 Redis 防重。

### 6.3 核销与退款并发边界

核销和退款必须共用 TicketOrder 行锁作为串行化边界，并固定采用“先订单、后明细”的锁顺序。核销入口可先按 `uk_ticket_code` 做一次不加锁的定位查询取得 orderId，但进入事务后必须先 `SELECT TicketOrder ... FOR UPDATE`，随后重新读取并锁定目标 TicketOrderItem，再校验 Showtime、工作人员关系、订单状态和票状态。不能先锁票明细再等待订单锁，否则会与现有退款的订单优先顺序形成反向锁序。

首次核销使用单条条件更新，将目标明细从 VALID 改为 USED 并同时写入 `used_time`、`used_by_user_id`。WHERE 条件至少包含 `id`、`order_id`、`ticket_status=VALID`、`used_time IS NULL` 和 `used_by_user_id IS NULL`，影响行数必须严格为 1。影响行数为 0 时重新读取最终状态并映射成明确业务结果，禁止接受部分成功或覆盖首次核销证据。

由于两条路径先竞争同一 TicketOrder 行锁，核销与退款并发只能形成两种终态：退款先获得锁时，整单完成 REFUNDED，随后核销看到退款票并拒绝；核销先获得锁时，目标票变为 USED，随后退款在写操作前检测到 USED 并返回 REFUND_NOT_ALLOWED。两种终态下都不修改 Redis，也不会出现座位已经释放但票仍 USED 的部分成功。

### 6.4 Step 9.1 冻结范围

本 Step 只修改 `sql/movielife.sql`、`docs/API接口设计.md` 和本文档，冻结 `tb_venue_staff`、TicketOrderItem 核销证据字段、管理端接口、DTO/VO、业务错误码、权限来源、状态变化和并发锁序。不创建或修改 Java Controller、Service、Mapper、Entity、DTO、VO、枚举或测试代码，不开放真实 HTTP 接口，不提前实现后续 Step。

### 6.5 Step 9.2 持久化与退款保护

增量迁移 `V9_2__ticket_verification_persistence.sql` 已安全应用到现有 MySQL，并验证可重复执行。迁移创建 `tb_venue_staff`，为 TicketOrderItem 增加 `used_time`、`used_by_user_id`、审计索引、外键和两项 CHECK 约束；迁移前先拒绝未知票状态或不一致的核销证据。Java 侧增加 VenueStaff 实体与 Mapper 映射，并补齐 TicketOrderItem 的核销证据字段。

退款事务已改为先锁 TicketOrder，再按 ID 顺序锁定订单全部 TicketOrderItem。服务在释放座位、恢复余票、更新订单或票状态之前检查 USED 明细；存在任一 USED 时直接返回 `REFUND_NOT_ALLOWED`。数据层和事务测试确认拒绝退款时订单、票、座位与余票全部不变。

### 6.6 Step 9.3 核销事务服务

TicketVerificationService 已实现单票核销。服务先按唯一票码做不加锁定位，再锁 TicketOrder 和目标 TicketOrderItem，随后读取 Showtime、Seat，并使用 `tb_venue_staff.status=1` 校验工作人员对目标 Venue 的权限。只有 PAID 订单中的 VALID 票可以通过条件更新写为 USED；同一更新同时写入秒级 `used_time` 和 `used_by_user_id`，并严格要求 `affectedRows=1`。

服务明确映射未知票码、未出票、已核销、已退款、订单状态冲突、场馆权限不足和数据不一致。重复扫描不会覆盖首次核销证据。核销与退款共同竞争订单行锁，因此并发结果只会收敛为“退款成功、核销拒绝”或“核销成功、退款拒绝”。核销不增加时间窗，也不改变订单、座位、余票或 Redis。

### 6.7 Step 9.4 HTTP 接口

`POST /api/admin/tickets/verify` 已开放并要求登录。VerifyTicketDTO 只接收 `ticketCode`，去除首尾空白后校验非空且最长 64 位；票码不进入 URL 或 Query 参数。Controller 从登录上下文取得核销人员，不接受客户端提交的用户、场馆、订单、状态或核销时间。

成功响应使用 `Result<VerifyTicketVO>`，返回票码、USED 状态、首次核销时间、订单、场次、场馆、影厅、座位 ID 和座位号。Controller 测试与真实 HTTP 测试覆盖登录、参数校验、成功响应和各业务错误的 HTTP 映射。

### 6.8 阶段 9 最终冻结

阶段 9 共完成 9.1 契约、9.2 持久化与退款保护、9.3 核销事务服务、9.4 HTTP 接口以及 9.5 最终验收。全量 345 项自动化测试通过，0 failure、0 error、0 skipped；真实 MySQL、Redis 与 HTTP 联调通过，测试结束后订单、票明细、HELD 或 SOLD 座位、工作人员授权测试数据和 `seat:lock:*` 均无残留。Maven `clean package` 为 `BUILD SUCCESS`，阶段 9 至此冻结。

本阶段不包含二维码签名、离线核销、批量核销、核销撤销、设备管理、工作人员管理接口、Activity 票务或 Redis 防重。后续阶段不得改变阶段 8 订单事实边界及本阶段“先订单、后明细”的核销与退款锁顺序。

## 7. 阶段 10：固定座位活动票务

### 7.1 Step 10.1：业务范围与兼容契约

阶段 10 只把现有固定座位票务能力扩展到 `ACTIVITY`，不重新设计阶段 1～9。适用活动必须落在既有 `Activity → Showtime → Venue/Hall → Seat/ShowtimeSeat` 关系中，并按具体物理座位售票。站票、通票、票档库存、多日票、套票和不选座活动需要不同库存模型，本阶段明确排除，不允许通过虚拟 Seat 套用现有交易流程。

现有 `tb_activity`、`tb_showtime.biz_type/biz_id`、`tb_showtime_seat`、`tb_ticket_order.showtime_id` 和 TicketOrderItem 已足以表达固定座位活动订单，Step 10.1 不新增活动订单表、不增加 `ticket_mode` 字段，也不复制锁座、订单、支付、出票、退款或核销状态机。Step 10.1 设计时 SQL 中 `total_seats=0` 的 Activity 示例场次只属于历史预留数据，在建立有效 Hall、启用 Seat 和完整 ShowtimeSeat 前不得进入固定座位购票链路；该数据缺口已由下述 Step 10.2 修正。

公开活动查询冻结为 `GET /api/activities`、`GET /api/activities/{id}` 和 `GET /api/activities/{id}/showtimes`。列表与详情只暴露 `status=1` 的 Activity；`priceMin/priceMax` 只是发现页展示范围，不能参与订单计价。场次查询同时要求 Activity 有效、`biz_type=ACTIVITY`、`biz_id` 匹配、Showtime 有效且未开场、Venue 有效、Hall 归属正确，并按开始时间和 ID 稳定排序；跨场馆结果必须批量补齐 Venue 与 Hall，禁止 N+1。无匹配场次返回空列表；活动不存在、草稿或下线统一返回 `ACTIVITY_NOT_FOUND`。本 Step 不顺带开放 Activity 收藏、评价、附近活动或管理端写接口。

场次详情、Seat Map、Redis 锁座及订单写链路继续使用已有通用 URL。实现阶段需要把当前 `ShowtimeServiceImpl` 的 MOVIE 专用上下文扩展为受控的 MOVIE/ACTIVITY 分支，但必须保持阶段 6 的电影查询契约不变：电影仍校验 Movie，活动改为校验 Activity；两类场次都严格校验 Venue、Hall 关系、未来有效场次以及完整 ShowtimeSeat。固定座位 Activity 必须具有至少一个启用 Seat，`total_seats` 与启用 Seat 数量一致；任何关联或数量不一致都返回不暴露内部细节的 `INTERNAL_ERROR`，不能按空座位、默认 AVAILABLE 或普通无票状态处理。

为避免破坏已冻结客户端契约，ShowtimeDetailVO、TicketOrderListItemVO 和 TicketOrderDetailVO 采用只增不删的兼容扩展：保留 `movieId/movieName`，新增 `bizType/activityId/activityName`。MOVIE 返回原电影字段且活动字段为 null；ACTIVITY 返回活动字段且电影字段为 null。订单查询必须根据一页订单关联 Showtime 的 `biz_type` 分组，分别批量读取 Movie 与 Activity 后恢复原订单顺序，禁止引入 N+1。CreateTicketOrderVO、PayTicketOrderVO、IssueTicketVO、VerifyTicketVO 和 Seat Map 当前不依赖电影标题，无需为 Activity 复制响应类型。

固定座位 Activity 首次创建订单继续从服务端读取 ShowtimeSeat.price，使用相同的 1～6 座限制、Redis Lua 原子锁、5 分钟 lockToken 资格和 15 分钟订单支付窗口。MySQL 已存在相同 lockToken 订单时必须优先校验并返回原订单，不再要求 Redis Metadata 或 seatKey 存在。首次创建仍在单个 MySQL 事务内依次插入 TicketOrder、批量插入 TicketOrderItem、条件占用全部 ShowtimeSeat 并扣减 `available_seats`；所有 affectedRows 必须完整匹配，COMMIT 后才按 token 条件清理 Redis。

主动取消、自动超时、模拟支付、出票、整单退款和单票核销不新增 Activity 分支状态，只根据订单关联的 Showtime 和现有 MySQL 事实工作。订单与明细继续采用“先订单、后明细”的锁顺序，条件 UPDATE 和严格 affectedRows 校验不变；查询、取消、超时、支付、出票、退款和核销不读取或写入新的 Redis 状态。活动核销权限仍由 Showtime.venue_id 与 `tb_venue_staff.status=1` 推导，不接受客户端提交 Venue。

Step 10.1 只修改 API 与技术设计文档并冻结上述契约，不新增或修改 Java、测试和数据库结构，不开放真实 HTTP 接口，不提前实现后续 Step。其指定的下一小步是单独处理 Activity 持久化映射、合法固定座位测试数据及基础查询；只有该 Step 完成自动化测试、真实 MySQL/HTTP 联调、Maven BUILD SUCCESS、独立 commit 并冻结后，才能扩展通用场次、锁座和订单链路。该小步的完成状态记录如下。

### 7.2 Step 10.2：Activity 持久化与基础查询

本 Step 已增加 `Activity` Entity、Mapper、Service、Controller、查询 DTO/VO 和 `ACTIVITY_NOT_FOUND` 错误码，开放 `GET /api/activities`、`GET /api/activities/{id}`、`GET /api/activities/{id}/showtimes` 三项无需登录的只读接口。列表只查询 `status=1`，支持城市、类型、标题关键字与受限分页；详情将不存在、草稿和下线统一隐藏。活动场次只查询未来有效的 `ACTIVITY` Showtime，并按 `start_time ASC, id ASC` 稳定排序。

场次补充信息继续沿 `Activity → Showtime → Venue/Hall` 关系读取，不向 Activity 增加场馆字段。实现先取得一批 Showtime，再批量查询 Venue 和 Hall，避免 N+1；场次主键、Venue/Hall 外键、时间范围、价格、座位计数及 Hall 归属不完整时统一失败关闭为 `INTERNAL_ERROR`，停用 Venue 的场次不对外展示。三项查询不读取或写入 Redis，也不修改 TicketOrder、TicketOrderItem、ShowtimeSeat 或 `available_seats`。

`sql/movielife.sql` 已将初始化活动场次补成 Hall 3 下 6 个启用物理 Seat 和完整 ShowtimeSeat；增量脚本 `V10_2__seated_activity_seed.sql` 可重复执行，并按 ShowtimeSeat 现有状态重算 `total_seats` 与 `available_seats`，不会在冲突更新中把已售座位恢复为 AVAILABLE。该数据只建立固定座位活动的合法查询前提，不改变阶段 7～9 的 Redis 临时资格和 MySQL 最终事实边界。

自动化测试覆盖 Controller 参数与错误映射、Service 过滤排序/批量关联/异常数据、真实持久化以及随机端口 HTTP 列表 → 详情 → 场次链路。迁移在隔离 MySQL 8.0.17 连续执行两次后仍保持 1 个有效活动场次、6 个启用 Seat、6 条 ShowtimeSeat 和一致余票；真实 HTTP 测试同时确认 MySQL 记录与 Redis `seat:lock:*` 前后不变。全量 359 项测试通过，0 failure、0 error、0 skipped，Maven 构建成功，Step 10.2 至此冻结。

本 Step 不扩展通用 Showtime 详情或 Seat Map，不接入 Activity 锁座、订单、支付、出票、退款、核销，也不实现附近活动、收藏、评价、管理端、非固定座位库存、Redis Stream、MQ、第三方支付、部分退款、核销撤销或 AI。后续只能另开一个小 Step，并继续执行设计、实现、自动化测试、真实联调、BUILD SUCCESS、commit、冻结。

### 7.3 Step 10.3：通用场次详情与 Seat Map 兼容

本 Step 将 `ShowtimeServiceImpl` 的 MOVIE 专用上下文收敛为受控的 MOVIE/ACTIVITY 上下文，既有 `GET /api/showtimes/{id}` 和 `GET /api/showtimes/{id}/seats` 无需复制 Activity 专用入口。公共校验继续要求场次有效、尚未开场、Venue 有效且 Hall 归属正确；MOVIE 读取 Movie，ACTIVITY 读取 Activity。业务资源或结构关系缺失返回 `INTERNAL_ERROR`，Venue 或 Activity 停用以及未知业务类型对外返回 `SHOWTIME_NOT_FOUND`。

`ShowtimeDetailVO` 采用只增不删的兼容扩展，新增 `bizType`、`activityId`、`activityName`。MOVIE 继续返回原 `movieId/movieName`，活动字段为 null；ACTIVITY 返回活动字段，电影字段为 null。Seat Map 的 URL、VO 和座位排序保持不变，没有增加 Activity 专用 DTO、Controller 或 Redis 状态。

Activity Seat Map 在阶段 6 的批量座位读取上增加固定座位完整性校验：必须至少存在一个启用物理 Seat，`total_seats` 必须等于启用 Seat 数，每个启用 Seat 必须恰好关联一条同场次 ShowtimeSeat，状态只允许 AVAILABLE、HELD、SOLD，价格不得为空或为负，`available_seats` 必须等于启用座位中 AVAILABLE 的数量。任何缺失、重复、跨场次状态或计数不一致都失败关闭为 `INTERNAL_ERROR`。MOVIE 分支不改变阶段 6 行为，尤其保留“没有启用物理座位返回空列表”。

实现没有数据库结构或迁移变更，复用 Step 10.2 的 6 座活动数据。自动化测试覆盖 MOVIE 响应兼容、ACTIVITY 详情、停用 Activity、未知业务类型、合法活动 Seat Map、空座位和计数不一致。真实 MySQL 8.0.17、Redis 与随机端口 HTTP 联调验证动态 2 座 Activity 及初始化 6 座 Activity 均可查询，并确认 Activity、Showtime 和 `seat:lock:*` 前后不变。全量 370 项测试通过，0 failure、0 error、0 skipped，Maven 构建成功，Step 10.3 至此冻结。

本 Step 不修改 SeatLockService、TicketOrder、支付、出票、退款或核销，也不让 Activity 进入交易写链路。后续若继续，应另开小 Step 处理 Activity 锁座与首次创建订单兼容，并继续遵守 Redis 只提供订单前临时资格、MySQL 保存订单后最终事实以及单事务全成全败边界。

### 7.4 Step 10.4：Activity 锁座与首次创建订单兼容

本 Step 复用既有锁座、释放和创建订单接口，没有复制 Activity 专用 Controller、DTO、Lua、订单表或状态机。SeatLockService 仍先取得通用 Showtime 详情；仅对 `bizType=ACTIVITY` 增加 Step 10.3 完整 Seat Map 校验，并在通过后继续执行原有的所选 Seat/ShowtimeSeat 批量校验与 Redis Lua 原子锁定。这样，Activity 整体座位数据不完整、状态/价格非法或 `total_seats`、`available_seats` 不一致时，不会签发可用于创建订单的临时资格；MOVIE 分支保持原锁座查询链路。主动释放仍只消费 token 元数据并通过既有 Lua 按 value 匹配删除，不写 MySQL。

订单创建的幂等入口没有改变：TicketOrderCreationValidator 先按唯一 `lock_token` 查询 MySQL，已有订单时校验用户、场次和完整明细座位集合后直接返回；只有不存在订单时才校验 Redis Metadata、过期时间及全部 seatKey。因此 Activity 订单提交成功且 Redis 已清理后，同 token 重试仍返回原订单，不能二次消费，也不会错误返回 `INVALID_LOCK_TOKEN`。

Activity 首次创建订单在既有 `TicketOrderTransactionService` 单事务内、任何写操作之前再次执行完整 Seat Map 校验，防止锁座后至下单前出现整体数据损坏。随后完全复用阶段 8 的事务顺序：插入 TicketOrder、批量插入 TicketOrderItem、按 AVAILABLE 且 `lock_order_id IS NULL` 条件批量占座、条件扣减 `available_seats`；订单和明细写入数量、占座数量及场次更新均要求精确 affectedRows，任一步异常整体回滚。价格快照逐座读取 ShowtimeSeat.price，Activity.priceMin/priceMax 不参与结算。事务成功返回后才调用原 token 条件清理；Redis 清理失败不否定已经提交的 MySQL 订单事实。

测试新增 Activity 完整 Seat Map 门禁、MOVIE 路径不增加 Seat Map 查询、门禁失败零写入，以及真实 HTTP 锁座 → 释放 → 再锁座 → 创建订单 → Redis 清理 → 无 Redis 幂等重试闭环。真实联调使用隔离 MySQL 8.0.17、当前 `movielife.sql`、10.2 可重复活动种子和本机 Redis；两张活动票按 120.00 与 180.00 形成 300.00 订单，MySQL 中形成唯一 TicketOrder、两条 TicketOrderItem、两条 HELD ShowtimeSeat 和 `available_seats=0`，Redis token 与 seatKey 在提交后删除。全量 375 项测试通过，0 failure、0 error、0 skipped，Maven 构建成功，Step 10.4 至此冻结。

本 Step 不修改 SQL，不实现 Activity 订单列表/详情展示兼容，不为取消、超时、支付、出票、退款或核销增加 Activity 分支验证，也不新增 Redis 状态、Redis Stream、MQ、第三方支付、部分退款、核销撤销、非固定座位库存、活动收藏/评价或 AI。下一步尚未指定，必须另行设计并拆成独立小 Step。

### 7.5 Step 10.5：Activity 订单列表与详情展示兼容

本 Step 只扩展既有 `GET /api/orders` 和 `GET /api/orders/{id}`。`TicketOrderListItemVO`、`TicketOrderDetailVO` 采用与 ShowtimeDetailVO 一致的只增不删兼容结构，新增 `bizType`、`activityId`、`activityName`，保留 `movieId/movieName`。MOVIE 订单继续返回电影字段且活动字段为 null；ACTIVITY 订单返回活动字段且电影字段为 null。订单、明细、票、座位和余票的持久化结构均未变化。

TicketOrderQueryService 在列表分页取得当前页 TicketOrder 后，一次批量读取明细并统计 seatCount，一次批量读取 Showtime，再按 `bizType` 将 `biz_id` 分成 Movie 和 Activity 两组，各执行至多一次批量查询；Venue 与 Hall 仍各批量查询一次，最终按数据库分页记录的原顺序组装。空业务 ID 集合不发起无意义查询，未知业务类型、非法关联 ID、业务资源或其他上下文缺失均失败关闭为 `INTERNAL_ERROR`。详情只针对单个 Showtime 读取对应 Movie 或 Activity，继续一次批量读取全部 Seat 并按物理行列排序明细。

订单查询展示的是已经成立的 MySQL 历史事实，因此 Activity 查询直接按主键读取，不复用只允许 `status=1` 的公开 ActivityService。活动后续下线时，用户仍能查看原订单；只有 Activity 记录物理缺失才视为数据不一致。两个接口不读取或写入 Redis，不根据过期时间修改订单状态，也不触发取消、支付、出票、退款或核销。

自动化测试覆盖 MOVIE/ACTIVITY 混合分页、两类业务资源分别批量读取、分页顺序保持、Activity 与 Movie 字段互斥、下线 Activity 历史详情、Activity 缺失、未知业务类型和原 Movie 详情兼容。真实 MySQL 8.0.17、Redis 与随机端口 HTTP 联调完成 Activity 锁座 → 创建订单 → 列表 → 详情，确认活动名称、座位和 120.00/180.00 价格快照正确，并确认查询前后 TicketOrder、TicketOrderItem、ShowtimeSeat、`available_seats` 及 Redis 临时锁集合不变。全量 378 项测试通过，0 failure、0 error、0 skipped，Maven 构建成功，Step 10.5 至此冻结。

本 Step 不修改 SQL，不为取消、超时、支付、出票、退款或核销新增 Activity 分支验证，也不新增 Redis 状态、Redis Stream、MQ、第三方支付、部分退款、核销撤销、非固定座位库存、活动收藏/评价或 AI。下一步尚未指定，必须另开独立设计 Step。

### 7.6 Step 10.6：Activity 主动取消与超时取消兼容

本 Step 复用阶段 8 已冻结的 `POST /api/orders/{id}/cancel`、`TicketOrderTimeoutJob` 和 `TicketOrderCancellationService`，不复制 Activity 专用入口或状态机。取消以已经成立的 TicketOrder、TicketOrderItem、ShowtimeSeat 和 `available_seats` 为事实，不查询 Movie/Activity 作为释放前置条件；因此 Activity 后续下线也不妨碍用户取消历史 UNPAID 订单。现有生产实现没有 Movie 专属分支，经兼容验证后无需修改 Java 业务代码或 SQL。

主动取消与超时取消继续先以 `SELECT ... FOR UPDATE` 锁定 TicketOrder，再读取完整订单明细并以 `showtime_id + seatIds + status=HELD_BY_UNPAID_ORDER + lock_order_id=当前订单` 条件批量释放 ShowtimeSeat。释放行数必须等于明细数，随后恢复不超过 `total_seats` 的 `available_seats`，最后条件更新订单 `UNPAID → CANCELED`；任一步影响行数异常都抛出 `INTERNAL_ERROR` 或状态冲突，并由单个 MySQL 事务整体回滚。两种取消使用同一订单行锁串行竞争，重复主动取消、重复超时执行以及主动与超时并发只允许一次真实资源释放。

自动化和真实联调在随机端口 HTTP、隔离 MySQL 8.0.17、当前 `movielife.sql`、10.2 Activity 固定座位种子和本机 Redis 上完成。新增 4 项 Activity 场景：下线 Activity 的主动取消及重复幂等、到期订单任务取消及重复扫描、主动取消与超时任务并发竞争、部分座位状态异常时整体回滚；同时复用原 Movie 取消、任务批处理、单订单失败隔离、余票上界和并发事务回归。成功路径中订单变为 CANCELED，两条 TicketOrderItem 完整保留，两条 ShowtimeSeat 恢复 AVAILABLE、清空 `lock_order_id` 且版本只递增一次，`available_seats` 精确恢复；失败路径无部分释放。取消全程不读取或写入 Redis，联调前后 `seat:lock:*` 集合保持不变。

全量 382 项自动化测试通过，0 failure、0 error、0 skipped，Maven `clean package` 为 `BUILD SUCCESS`，Step 10.6 至此冻结。本 Step 不修改生产 Java、SQL、Redis Lua、DTO、VO 或公开接口，不实现 Activity 支付、出票、退款、核销，也不引入 Redis Stream、MQ、第三方支付、部分退款或核销撤销。下一步尚未指定，必须另开独立设计 Step。

### 7.7 Step 10.7：Activity 模拟支付兼容

本 Step 复用阶段 8 已冻结的 `POST /api/orders/{id}/pay`、`PayTicketOrderVO` 和 `TicketOrderPaymentService`，不增加 Activity 专用入口、请求参数或状态机。支付依据已经成立的 TicketOrder、TicketOrderItem 与 ShowtimeSeat 事实，不查询 Movie/Activity 作为支付前置条件，因此 Activity 后续下线不会使尚在支付窗口内的本人 UNPAID 订单失效。现有生产实现没有 Movie 专属分支，经真实兼容验证后无需修改 Java 业务代码或 SQL。

支付事务继续先以 `SELECT ... FOR UPDATE` 锁定 TicketOrder，并在锁内校验用户、状态和 `expire_time>payTime`；随后一次读取完整订单明细，使用单条条件 UPDATE 将当前场次、完整 seatId 集合、`status=HELD_BY_UNPAID_ORDER` 且 `lock_order_id=当前订单` 的全部 ShowtimeSeat 更新为 SOLD，递增版本并保留归属。座位影响行数必须等于明细数，订单再以 `id + status=UNPAID + expire_time>payTime` 条件更新为 PAID 并写入 payTime；任一步异常由单个 MySQL 事务整体回滚。支付不修改创建订单时已扣减的 `available_seats`，不生成票码，也不改变 TicketOrderItem 的 PENDING 状态。

新增 6 项 Activity 随机端口 HTTP 场景：下线 Activity 的正常支付及重复幂等、过期与取消状态拒绝、双支付竞争、支付与主动取消竞争、过期支付与超时任务竞争、部分座位状态异常时整体回滚。测试同时复用原 Movie 支付服务、Controller、事务和并发回归。双支付只允许一次座位 `HELD → SOLD` 和唯一首次 payTime；支付与取消竞争只允许 `PAID + SOLD` 或 `CANCELED + AVAILABLE`；过期支付与超时竞争最终为 `CANCELED + AVAILABLE`；异常路径不接受部分成功。所有支付场景中明细价格保持不变，不生成 ticketCode，Redis `seat:lock:*` 集合与 `available_seats` 均保持不变。

真实联调使用隔离 MySQL 8.0.17、当前 `movielife.sql`、10.2 Activity 固定座位种子和本机 Redis。全量 388 项自动化测试通过，0 failure、0 error、0 skipped，Maven `clean package` 为 `BUILD SUCCESS`，Step 10.7 至此冻结。本 Step 不修改生产 Java、SQL、Redis Lua、DTO、VO 或公开接口，不实现 Activity 出票、退款、核销，也不引入 Redis Stream、MQ、第三方支付、支付回调、部分退款或核销撤销。下一步尚未指定，必须另开独立设计 Step。

### 7.8 Step 10.8：Activity 出票兼容

本 Step 复用阶段 8 已冻结的 `POST /api/orders/{id}/issue`、`IssueTicketVO` 和 `TicketOrderIssuanceService`，不增加 Activity 专用入口、参数、票状态或票码格式。出票依据已经成立的 TicketOrder 与 TicketOrderItem，不读取 Movie/Activity 作为资格前置条件，因此 Activity 下线后，本人 PAID 历史订单仍可出票。现有生产实现没有 Movie 专属分支，经兼容验证后无需修改 Java 业务代码或 SQL。

出票事务继续先以 `SELECT ... FOR UPDATE` 锁定 TicketOrder 并校验本人及 PAID 状态，再按 seatId 排序读取完整 TicketOrderItem。写入前严格校验明细 ID、orderId、seatId 唯一性，以及 `PENDING + ticketCode=null` 或 `VALID + 非空唯一 ticketCode` 两种合法组合。仅为 PENDING 明细生成去连字符 UUID 票码，通过单条带 `order_id + itemIds + ticket_code IS NULL + ticket_status=PENDING` 条件的批量 UPDATE 写为 VALID；affectedRows 必须等于待出票明细数。全部已出票直接返回原票码，部分已出票只补齐待出票明细；唯一键冲突、行数不足或异常状态均由同一事务整体回滚。

新增 5 项 Activity 随机端口 HTTP 场景：正常出票与重复请求返回原票码、下线 Activity 历史订单出票、部分出票恢复并保留既有票码、双出票并发返回同一组票码、票码与状态不一致时拒绝且无部分写入。同时复用原出票 Service、Controller 和事务回归，继续覆盖非本人/非 PAID 拒绝、批量 affectedRows 严格校验以及唯一索引冲突的整批回滚。成功后每个座位明细只有一个全局唯一 32 位票码，订单仍为 PAID 且 payTime 不变；ShowtimeSeat 仍为 SOLD 并保留 `lock_order_id`，`available_seats`、明细价格及 Redis `seat:lock:*` 集合均保持不变。

真实联调使用隔离 MySQL 8.0.17、当前 `movielife.sql`、10.2 Activity 固定座位种子和本机 Redis。全量 393 项自动化测试通过，0 failure、0 error、0 skipped，Maven `clean package` 为 `BUILD SUCCESS`，Step 10.8 至此冻结。本 Step 不修改生产 Java、SQL、Redis Lua、DTO、VO 或公开接口，不实现 Activity 退款和核销，也不引入 Redis Stream、MQ、第三方支付、部分退款或核销撤销。下一步尚未指定，必须另开独立设计 Step。

### 7.9 Step 10.9：Activity 整单退款兼容

本 Step 复用阶段 8 已冻结的 `POST /api/orders/{id}/refund` 和 `TicketOrderRefundService`，不增加 Activity 专用入口、请求参数、退款状态或返回结构。退款依据已经成立的 TicketOrder、TicketOrderItem、ShowtimeSeat 与 `available_seats`，不读取 Movie/Activity 作为资格前置条件，因此 Activity 下线后，本人尚未开场的 PAID 历史订单仍可退款。现有生产实现没有 Movie 专属分支，经兼容验证后无需修改 Java 业务代码或 SQL。

退款事务继续先以 `SELECT ... FOR UPDATE` 锁定 TicketOrder，再按明细 ID 顺序锁定全部 TicketOrderItem。REFUNDED 订单直接幂等返回；只有 PAID 且 Showtime 尚未开始的订单可以推进。写入前校验全部明细 ID、seatId 唯一且状态只能为 PENDING 或 VALID，任一 USED 明细立即返回 `REFUND_NOT_ALLOWED`。随后按 `showtime_id + 完整 seatIds + status=SOLD + lock_order_id=当前订单` 条件批量执行 `SOLD → AVAILABLE`、清空归属并递增版本，再恢复有总座位上界保护的 `available_seats`、条件更新订单 `PAID → REFUNDED`，最后把全部明细一起更新为 REFUNDED。座位数、余票、订单和票明细 affectedRows 均严格校验，任一步失败由同一 MySQL 事务整体回滚。

新增 6 项 Activity 随机端口 HTTP 测试：未出票 PAID 订单整单退款并恢复座位、已出票退款保留票码且重复请求幂等、下线 Activity 历史订单退款、已开场和存在 USED 明细时拒绝且零写入、双退款并发只释放一次、部分座位状态异常时整体回滚。同时复用原退款 Service、Controller 和事务回归，继续覆盖非本人、非法状态、余票上界、明细行数异常和通用退款并发。退款保留 payTime、lockToken、价格、全部订单明细和既有 ticketCode；成功后全部票状态为 REFUNDED，全部座位为 AVAILABLE，`lock_order_id` 清空，余票精确恢复。

真实联调使用隔离 MySQL 8.0.17、当前 `movielife.sql`、10.2 Activity 固定座位种子和本机 Redis。全量 399 项自动化测试通过，0 failure、0 error、0 skipped，Maven `clean package` 为 `BUILD SUCCESS`，Step 10.9 至此冻结。本 Step 不修改生产 Java、SQL、Redis Lua、DTO、VO 或公开接口，不实现 Activity 核销，也不引入 Redis Stream、MQ、第三方支付退款、退款回调、部分退款或核销撤销。下一步尚未指定，必须另开独立设计 Step。

### 7.10 Step 10.10：Activity 场馆核销兼容

本 Step 复用阶段 9 已冻结的 `POST /api/admin/tickets/verify`、`VerifyTicketDTO/VO`、`TicketVerificationService` 和 `tb_venue_staff` 场馆权限事实，不增加 Activity 专用核销入口、票状态或权限模型。服务根据 ticketCode 定位 TicketOrderItem，经 TicketOrder 找到 Showtime，并使用 Showtime.venueId 校验当前用户存在同场馆启用的 VenueStaff 关系；因此工作人员只有目标活动实际 Venue 的权限才可核销，跨场馆、停用或无关系均严格返回 `TICKET_VERIFY_FORBIDDEN`。核销不查询 Activity 上下架状态，下线活动的已成立有效票仍以 MySQL 票务事实处理。

事务先通过唯一 ticketCode 定位明细，再以 `SELECT ... FOR UPDATE` 锁定 TicketOrder，随后锁定目标 TicketOrderItem，保持订单后明细的统一锁顺序。写入前校验订单 PAID、票状态 VALID、核销证据为空、Seat 属于 Showtime.hallId 且物理座号有效。首次核销使用单条条件 UPDATE 执行 `VALID → USED`，同时写入秒级 usedTime 与 usedByUserId，affectedRows 必须为 1；条件竞争失败后重新读取最终状态并映射为明确业务错误。重复或并发重复核销返回 `TICKET_ALREADY_USED`，首次证据不可覆盖。

新增 4 项 Activity 随机端口 HTTP 测试：下线 Activity 的本场馆工作人员成功核销并验证重复请求不覆盖证据、跨场馆工作人员核销严格拒绝且所有事实不变、双核销并发只有一个 HTTP 200、核销与整单退款并发只形成两种合法终态。同时复用阶段 9 原 Controller、Service 和事务测试覆盖空票码、未出票、退款票、非 PAID 订单、停用权限、异常核销证据和物理座位关联缺失。成功核销只修改目标 TicketOrderItem，订单保持 PAID，其他票仍为 VALID，ShowtimeSeat 保持 SOLD，`lock_order_id`、`available_seats` 与 Redis 均不变。

真实联调使用隔离 MySQL 8.0.17、当前 `movielife.sql`、10.2 Activity 固定座位种子和兼容 GEO 的本机 Redis。全量 403 项自动化测试通过，0 failure、0 error、0 skipped，Maven `clean package` 为 `BUILD SUCCESS`，Step 10.10 至此冻结。本 Step 不修改生产 Java、SQL、Redis Lua、DTO、VO 或公开接口，不实现核销撤销、批量核销、电子闸机、Redis Stream 或 MQ。下一步尚未指定，必须另开独立设计 Step。

### 7.11 Step 10.11：Activity 完整闭环与阶段冻结

本 Step 不新增业务实现，而是以一条随机端口真实 HTTP 主链路把阶段 10.2～10.10 已冻结能力串联验收：活动列表与详情 → 活动场次 → 通用 Showtime 详情 → Seat Map → Redis 临时锁座 → 创建 UNPAID 订单 → 订单详情 → 模拟支付 → 出票 → 对应 Venue 工作人员核销。回归逐节点验证公开响应与持久化事实，确认 Activity 始终复用通用 Showtime、TicketOrder、TicketOrderItem 和 ShowtimeSeat 模型，没有 Activity 专用订单或状态分支。

主链路验证 Redis TEMP_LOCKED 仅存在于创建订单之前；订单提交后 tokenKey 和全部 seatKey 已清理，后续支付、出票和核销均不创建票务 Redis 状态。最终 MySQL 事实为 TicketOrder 保持 PAID 且保留首次 payTime，全部 ShowtimeSeat 为 SOLD 并继续归属该订单，目标 TicketOrderItem 为 USED 且写入首次核销证据，其余明细为 VALID，`available_seats` 保持 0。订单后的最终事实完全由 MySQL 承担。

阶段冻结测试集继续保留并统一验收主动取消、超时取消、支付与取消竞争、过期支付与超时竞争、未出票及已出票整单退款、核销与退款竞争、异常数据全事务回滚，以及下单、支付、出票、退款的幂等和重复核销严格拒绝。合法分支终态只允许 `CANCELED + AVAILABLE`、`REFUNDED + 全明细 REFUNDED + AVAILABLE` 或 `PAID + SOLD + VALID/USED`；任何中间写入或部分成功都由锁顺序、条件 UPDATE、affectedRows 校验和单个 MySQL 事务阻止。

真实联调使用隔离 MySQL 8.0.17、当前 `movielife.sql` 和兼容 GEO 的 Redis。Activity 定向 29 项测试通过；全量 404 项自动化测试通过，0 failure、0 error、0 skipped，Maven `clean package` 为 `BUILD SUCCESS`。Step 10.11 不修改生产 Java、SQL、Redis Lua、DTO、VO 或公开接口，不实现非固定座位票务、第三方支付、部分退款、核销撤销、Redis Stream 或 MQ。阶段 10 至此完成并冻结；下一阶段尚未指定，必须先从活动票务或 AI 能力中明确独立阶段，再拆分单个小 Step。

## 8. 阶段 11：Activity Nearby

### 8.1 Step 11.1：详细契约设计与冻结

阶段 11 独立实现 Activity Nearby，只覆盖“浏览附近活动”和“查询指定活动的附近场馆”两个互补的只读场景。前者新增 `GET /api/activities/nearby`，后者在既有 `GET /api/venues/nearby` 上增加 `bizType=ACTIVITY&bizId={activityId}`；两者共用 Venue GEO 和 MySQL 场次事实，不复制 Activity GEO、Venue、Showtime 或票务模型。Step 11.1 只冻结设计，不修改生产 Java、SQL、Redis Key 或当前公开接口实现。

#### 8.1.1 数据来源与职责边界

Activity 不直接持有可用于附近搜索的稳定位置，同一活动可通过 Showtime 在多个城市和 Venue 巡演，因此不得建立 `activity:geo:*`。附近活动统一从 `venue:geo:{city}:{VenueType}` 召回 `venueId + distance`，再由 MySQL 批量确认 Venue、Activity、Showtime 和 Hall 的业务事实：

```text
city + x/y + distance
  ↓
一个或全部 VenueType 的 venue:geo Key
  ↓
合并 venueId + 最小 distance
  ↓
MySQL 批量校验启用 Venue
  ↓
批量查询未来有效 ACTIVITY Showtime
  ↓
批量补齐 Activity 与 Hall 并校验关系
  ↓
按 Activity 去重，选择最近 Venue 与该 Venue 下一场
  ↓
稳定排序，过滤后分页
```

Redis 只负责空间候选召回，不保存活动可见性、场次有效性、余票或订单事实。MySQL 决定 Activity 是否上架、Showtime 是否未来有效、Venue 是否启用以及 Hall 是否属于 Venue。查询全程不新增或修改 Redis member，不创建活动缓存，不触碰 `seat:lock:*`，也不修改 Activity、Showtime、ShowtimeSeat、`available_seats`、TicketOrder 或 TicketOrderItem。

#### 8.1.2 两个查询场景

`GET /api/venues/nearby?bizType=ACTIVITY&bizId={activityId}` 用于活动详情页查找附近承办场馆。它复用现有 `VenueNearbyQueryDTO` 和 `VenueNearbyVO`，继续要求单个 `VenueType`，先校验 Activity 为 `status=1`，再从对应 GEO Key 召回并通过 `tb_showtime` 的 `biz_type=ACTIVITY`、`biz_id`、`status=1`、`start_time>NOW()` 过滤。MOVIE 分支的参数、返回、排序和错误行为保持不变。

`GET /api/activities/nearby` 用于发现页浏览附近活动。它使用独立 `ActivityNearbyQueryDTO`，要求 `city/x/y`，支持 `distance/page/pageSize` 默认值、可选活动 `type` 和可选 `venueType`；未指定 VenueType 时并行逻辑等价地查询 `CINEMA/THEATER/LIVEHOUSE/EXHIBITION` 四个现有 GEO Key。响应为 `List<ActivityNearbyVO>`，提供活动展示字段、最近场馆、距离、下一场次和下一场余票，不返回无法在有界 GEO 召回下精确计算的 total。城市资格以所选 Venue.city 为准，不依赖 `tb_activity.city`，避免破坏 Activity 经 Showtime 跨城市巡演的关系。

#### 8.1.3 候选窗口、去重与稳定顺序

两个场景统一采用 `min(page × pageSize × 5, 200)` 的 GEO 候选窗口，并在 MySQL 业务过滤后分页。附近活动对每个被查询的 VenueType Key 使用同一上限，合并时按 venueId 保留最小距离；MySQL 重新校验 Venue 的启用状态、城市、类型和经纬度，忽略删除、停用、跨城市或错误 Key 的历史 member。Key 不存在返回空列表，Redis 调用异常或无法解析的 member 返回 `INTERNAL_ERROR`，不增加 MySQL 经纬度计算降级。

同一 Activity 只返回一次。先按 `distance ASC, venueId ASC` 选择最近有效 Venue，再按 `start_time ASC, showtimeId ASC` 选择该 Venue 的下一场；最终按 `distance ASC, nextStartTime ASC, activityId ASC` 排序并截取当前页。有效发现场次要求 Activity 上架、Showtime 为未来在售 ACTIVITY 场次、Venue 启用、Hall 归属正确，且场次核心时间、座位总数和余票范围合法。`available_seats=0` 只表示下一场售罄，不隐藏活动；真正锁座和下单仍由阶段 7～10 的完整资格校验决定。

#### 8.1.4 异常与实现约束

请求字段缺失、越界或参数组合错误映射为 `INVALID_PARAM`，非法 VenueType 映射为 `INVALID_VENUE_TYPE`，不支持的 bizType 映射为 `INVALID_BIZ_TYPE`，指定 Activity 不存在、草稿或下线映射为 `ACTIVITY_NOT_FOUND`。普通附近活动发现会过滤已下线 Activity；Showtime 指向物理缺失的 Activity、Hall 缺失或不属于 Venue、场次核心字段损坏等结构异常统一失败关闭为 `INTERNAL_ERROR`。实现必须批量读取 Venue、Showtime、Activity 和 Hall，禁止 N+1，不引入表、字段、索引、新 Redis Key、Redis Stream、MQ 或事务写入。

#### 8.1.5 后续 Step 与验收矩阵

| Step | 独立范围 | 完成门槛 |
| --- | --- | --- |
| 11.2 | 只扩展指定 Activity 的附近场馆 | DTO/Service/Mapper、自动化测试、真实 Redis/MySQL/HTTP、全量回归、BUILD SUCCESS、commit、冻结 |
| 11.3 | 只实现 `GET /api/activities/nearby` | 多 VenueType 合并、批量关联、去重排序、过滤后分页及对应完整验证 |
| 11.4 | 只加固异常数据、候选窗口、分页与只读边界 | 历史 member、停售/过期场次、跨城市/错误类型、售罄活动、N+1 与零写入证据 |
| 11.5 | 完整主链路与阶段冻结 | Activity Nearby → Detail → Showtimes → Showtime Detail → Seat Map，定向/全量测试、真实联调、BUILD SUCCESS、文档、commit、冻结 |

真实联调必须同时覆盖：单 VenueType 与全 VenueType、同一 Activity 多场馆和多场次、跨类型距离合并、指定 Activity 的场馆过滤、GEO 空 Key、Redis 异常、历史 member、停用 Activity/Venue、过期或停售 Showtime、Hall/Venue 关系损坏、售罄但仍展示、过滤后分页和稳定排序。联调前后比较 MySQL 核心表、GEO member、`seat:lock:*` 和订单事实，证明阶段 11 全链路只读。

Step 11.1 至此冻结：本 Step 只更新 `API接口设计.md` 和 `MovieLife_项目技术设计.md`，不实施 Step 11.2～11.5，不包含非固定座位活动票务、Activity 收藏、Venue/Activity Review、Activity 管理端、AI、前端或 GEO 自动同步。

### 8.2 Step 11.2：指定 Activity 的附近场馆实现与冻结

Step 11.2 只落地 `GET /api/venues/nearby?bizType=ACTIVITY&bizId={activityId}`，不实现附近活动主接口。`VenueNearbyQueryDTO` 将合法业务类型扩展为 `MOVIE/ACTIVITY`；Service 在访问 Redis 前校验指定 Activity 存在且 `status=1`，不存在、草稿或下线统一返回 `ACTIVITY_NOT_FOUND`。原 MOVIE 分支仍使用既有 Movie 校验及 Mapper 查询，不改变其公开契约和运行路径。

ACTIVITY 分支复用单 VenueType 的 `venue:geo:{city}:{VenueType}`，按照现有 `min(page × pageSize × 5, 200)` 候选窗口取得距离升序结果。Service 一次批量装载启用 Venue，再由 `ShowtimeMapper.selectVenueIdsWithFutureActivityShowtimes` 使用 `SELECT DISTINCT venue_id` 筛选 `biz_type='ACTIVITY' AND biz_id=? AND venue_id IN (...) AND start_time>NOW() AND status=1`。返回的场馆 ID 转为集合只用于资格判断，最终遍历仍以 Redis GEO 结果为准，因此同一场馆多场次只出现一次、距离顺序不被 MySQL 返回顺序打乱，并在完成业务过滤后按 `page/pageSize` 截取。

本 Step 没有 MySQL 或 Redis 写路径：Activity、Venue 和 Showtime 只读，GEO 只召回；不新增 Redis Key，不创建缓存，不读写临时锁座状态，不修改 ShowtimeSeat、`available_seats`、TicketOrder 或 TicketOrderItem。`sql/movielife.sql`、数据库表结构、现有 Lua、DTO/VO 响应字段以及阶段 1～10 的状态机均保持不变。

测试分为四层：DTO/Controller 校验 `ACTIVITY` 合法且未知类型被拒绝；Service 单元测试验证 Activity 状态、GEO 顺序、过滤后分页以及 MOVIE Mapper 分支不串用；真实 Service 集成测试覆盖未来/过期/停售场次、场馆去重和下线 Activity；随机端口 HTTP 测试使用真实 MySQL 与兼容 GEO 的 Redis，并在请求前后比较 Activity、Showtime 与 GEO member 快照证明查询零写入。Step 11.2 定向真实联调 15 项通过；全量 411 项测试通过，0 failure、0 error、0 skipped，Maven `clean package` 为 `BUILD SUCCESS`。

Step 11.2 至此冻结。下一步仍为独立的 Step 11.3，仅实现 `GET /api/activities/nearby`；本 Step 未提前实现多 VenueType GEO 合并、Activity 去重、最近场馆/下一场次选择、Step 11.4/11.5、非固定座位票务、Activity 收藏、Venue/Activity Review、Activity 管理端、AI、前端或 GEO 自动同步。

### 8.3 Step 11.3：附近活动主接口实现与冻结

Step 11.3 新增 `ActivityNearbyQueryDTO`、`ActivityNearbyVO` 和匿名 `GET /api/activities/nearby`，并在 `ActivityService` 中增加独立查询入口。DTO 固定 `city/x/y`、距离、分页、Activity type 与可选 VenueType 边界；VO 返回 Activity 展示字段、最近有效 Venue、距离及该 Venue 的下一场次上下文，不返回有界 GEO 召回无法精确计算的 total。Activity 自身的 city 不参与巡演城市资格判断，响应 `venueCity` 取选中 Venue 的城市。

候选流水线保持严格有界和只读：对一个指定 VenueType 或全部四类 VenueType，逐 Key 执行带距离、升序和 `min(page × pageSize × 5, 200)` 上限的 GEO 查询；按 venueId 合并后批量读取 Venue，并以 `status=1`、city、实际 type、经纬度过滤历史 member。若同一 venueId 出现在多个类型 Key，只接受其 MySQL 实际类型对应 Key 的距离，错误类型 Key 不能污染最终排序。

有效 Venue ID 集合交给 `ShowtimeMapper.selectFutureActivityShowtimesByVenueIds`，用一条 `IN (...)` 查询取得全部未来在售 ACTIVITY Showtime。Service 从结果一次提取 activityIds 和 hallIds，再分别使用一次 `selectBatchIds` 补齐 Activity 与 Hall；循环只做内存关联和选择，不执行 Mapper 查询。Showtime 核心字段、Activity 物理存在性以及 Hall 对 Venue 的归属必须完整；损坏关系返回 `INTERNAL_ERROR`，下线 Activity 和不匹配的 Activity type 正常过滤，售罄场次仍保留发现资格。

每个 Showtime 形成 `Activity + Venue + Showtime + distance` 候选。同一 Activity 先按 `distance ASC, venueId ASC` 选择最近 Venue，再在该 Venue 内按 `startTime ASC, showtimeId ASC` 选择下一场；最终按 `distance ASC, nextStartTime ASC, activityId ASC` 排序并在所有过滤完成后分页。该顺序与 Step 11.1 契约一致，同时保证 Redis 或 MySQL 原始返回顺序不会改变公开结果。

验证分为四层：DTO 边界与默认值；Controller 匿名访问、响应字段和非法 VenueType；Service 多 GEO 合并、Activity 去重、最近 Venue/下一场选择、type 过滤后分页及 Mapper 批量调用次数；真实随机端口 HTTP 使用 MySQL 和兼容 GEO 的 Redis，覆盖 THEATER/LIVEHOUSE、跨场馆、多场次、售罄展示，并比较 Activity、Venue、Showtime 与 GEO member 的请求前后快照证明零写入。Step 11.3 定向 19 项测试通过；全量 420 项测试通过，0 failure、0 error、0 skipped，Maven `clean package` 为 `BUILD SUCCESS`。

Step 11.3 至此冻结：本 Step 未修改 SQL、表结构、Redis Key、锁座或订单状态，不实现 Step 11.4/11.5、非固定座位活动票务、Activity 收藏、Venue/Activity Review、Activity 管理端、AI、前端或 GEO 自动同步。下一步只能在明确指定后进入独立 Step。

### 8.4 Step 11.4：边界、异常与分页加固冻结

Step 11.4 对 Step 11.3 的实现进行验证加固，不新增或修改生产接口、DTO、VO、Mapper SQL、Redis Key 和业务状态。GEO Key 缺失按无候选返回空列表；Redis GEO 故障、非法 member 或缺失距离统一失败关闭为 `INTERNAL_ERROR`。历史 GEO member 对应的 Venue 物理缺失、停用、跨城市、经纬度损坏或来源 VenueType 错误时在批量装载后被过滤，不回写 Redis，也不使用 MySQL 经纬度查询降级。

场次资格继续由 `selectFutureActivityShowtimesByVenueIds` 一次批量限定 `biz_type='ACTIVITY' AND status=1 AND start_time>NOW()`，因此已结束、停售和错误业务类型场次不进入组装。Service 再批量校验 Activity、Hall 和核心场次字段：Activity 下线属于正常不可见并过滤，Activity 物理缺失、Hall 缺失或 Hall.Venue 与 Showtime.Venue 不一致属于结构损坏并返回 `INTERNAL_ERROR`。余票为 0 仍是合法场次，附近发现不承担锁座资格判断。

去重和顺序边界经独立测试固定：同一 Activity 可跨多个 VenueType、多个 Venue 和多个 Showtime，最近 Venue 以 `distance ASC, venueId ASC` 决定，该 Venue 下一场以 `startTime ASC, showtimeId ASC` 决定，最终列表以 `distance ASC, nextStartTime ASC, activityId ASC` 排序。分页只能发生在 Venue/Showtime/Activity/Hall 过滤和 Activity 去重之后。每个 GEO Key 的候选数继续限制为 `min(page × pageSize × 5, 200)`；超过该有界窗口的深页返回空列表，不扩大召回、不产生伪精确 total。

新增 5 项 Service 单元测试和 3 项真实 MySQL/Redis 边界集成测试，并扩展既有随机端口 HTTP 联调快照。验证覆盖 GEO 空 Key 与异常、非法 member、脏 Venue、下线 Activity、无效 Showtime、Hall/Venue 错配、跨类型去重、同距离稳定顺序、过滤后分页、200 条候选上限和 `available_seats=0`。HTTP 请求前后比较 Activity、Venue、Hall、Showtime、ShowtimeSeat、TicketOrder、TicketOrderItem、GEO member、`seat:lock:*` 与 `seat:lock:token:*`，证明全链路无 MySQL/Redis 写入。原 MOVIE Nearby 专项 61 项通过；全量 428 项测试通过，0 failure、0 error、0 skipped，Maven `clean package` 为 `BUILD SUCCESS`。

Step 11.4 至此冻结。本 Step 未修改生产 Java、SQL、表结构、Redis Lua 或 Key，不实现 Step 11.5、非固定座位活动票务、Activity 收藏、Venue/Activity Review、Activity 管理端、AI、前端或 GEO 自动同步；阶段 11 尚未整体冻结。

### 8.5 Step 11.5：完整主链路与阶段冻结

Step 11.5 不增加生产能力，只在现有真实 HTTP 集成测试中把 Venue GEO 接入阶段 10.11 已冻结的 Activity 票务闭环，形成 `Nearby → Detail → Showtimes → Showtime Detail → Seat Map → Lock → Order → Pay → Issue → Verify` 的跨阶段证据。测试使用隔离城市和独立 THEATER GEO member，由 Nearby 返回 Activity、最近 Venue 和下一 Showtime，再使用这些 MySQL 身份继续后续接口，避免用测试预知 ID 绕过阶段 11 的发现入口。

主链路在首次锁座前保存并复核 Activity、Venue、Hall、Showtime、全部 ShowtimeSeat、相关 TicketOrder 数量、全局 TicketOrderItem 数量、Venue GEO member、`seat:lock:*` 和 `seat:lock:token:*`。Nearby、Activity 详情、Activity 场次、Showtime 详情与 Seat Map 完成后所有快照保持不变，证明阶段 11 的空间召回和 MySQL 资格过滤不会写缓存、修复 GEO、修改余票或产生订单状态。

进入阶段 10 后继续验证既有职责分界：Redis token 与 seatKey 只保存创建订单前的 TEMP_LOCKED 资格；创建订单在单个 MySQL 事务中生成唯一 TicketOrder、两条完整 TicketOrderItem、条件占用全部 ShowtimeSeat，并将 `available_seats` 从 2 精确扣减到 0。事务提交后 Redis 临时锁被清理。随后以同一 lockToken、同一用户、同一 Showtime 和乱序 seatIds 重试，直接返回原 orderId，TicketOrder 仍为 1 条、TicketOrderItem 仍为 2 条，不依赖 Redis Key 存在。

后续支付、出票与核销仍执行阶段 10 已冻结的锁顺序、条件 UPDATE、affectedRows 和事务规则：订单成为 PAID，座位成为 SOLD 且保留 orderId，票码唯一，目标明细首次核销成为 USED 并保存首次证据，另一张票保持 VALID；`available_seats` 保持 0，Redis 临时锁集合恢复到测试前状态。取消、超时、退款和并发分支继续由同一套 29 项 Activity HTTP 票务测试及全量回归覆盖，没有新增 Activity 专用状态机。

Activity Nearby 与完整 Activity 票务定向 56 项通过；真实 MySQL、Redis、随机端口 HTTP 主链路通过；全量 428 项测试通过，0 failure、0 error、0 skipped，Maven `clean package` 为 `BUILD SUCCESS`。本 Step 只强化测试和文档，未修改生产 Java、SQL、表结构、Redis Lua、Redis Key、DTO、VO 或公开接口。

阶段 11 Activity Nearby 至此正式冻结。阶段成果包含指定 Activity 的附近场馆、附近活动主接口、多 VenueType 有界召回、批量 MySQL 资格过滤、Activity 去重与稳定排序、边界失败关闭、过滤后分页和完整跨阶段真实链路。非固定座位活动票务、Activity 收藏、Venue/Activity Review、Activity 管理端、AI、前端和 GEO 自动同步仍不属于本阶段；下一阶段必须重新明确单一能力范围后再拆分 Step。

## 9. 阶段 12 Activity 收藏

### 9.1 Step 12.1：Activity 收藏写路径与状态检查

Step 12.1 只把 `ACTIVITY` 接入阶段 4 已冻结的通用收藏写路径与关系检查，不新增 Activity 专用 Controller、Service 接口、DTO、VO、错误码、数据库表或 Redis Key。公开路径仍为 `PUT /api/collections/{targetType}/{targetId}`、`DELETE /api/collections/{targetType}/{targetId}` 和 `GET /api/collections/check`，且均要求登录；`targetType` 继续统一去除首尾空白并转为大写。

新增 Activity 收藏前必须读取 MySQL `tb_activity`，要求记录物理存在且 `status=1`；不存在、草稿或下线统一返回 `COLLECTION_TARGET_NOT_FOUND`。已存在相同关系时直接成功；并发首次收藏继续依靠 `tb_collection.uk_collection_user_target(user_id, target_type, target_id)` 唯一约束收敛，捕获重复键后按幂等成功处理，不新增额外分布式锁或 Redis 状态。

取消收藏保持既有关系删除语义：只校验登录态、目标类型和正数 ID，然后按用户、类型、目标 ID 删除；Activity 后续下线或物理删除不阻止用户清理已有关系，无关系和重复取消均成功。`GET /api/collections/check` 同样保持阶段 4 的关系检查语义，只判断当前用户对应 MySQL 关系是否存在，不重新校验 Activity 可见性。

本 Step 的自动化与真实联调必须覆盖正常、重复和并发收藏，正常与重复取消，未登录、非法 ID、非法类型，Activity 不存在、草稿、下线及用户隔离。联调前后除预期 `tb_collection` 关系外，Activity、Showtime、ShowtimeSeat、TicketOrder、TicketOrderItem、`available_seats`、Venue GEO、`seat:lock:*` 和 `seat:lock:token:*` 均保持不变。

Step 12.1 不实现 Activity 收藏列表，不修改 Activity 列表、详情或 Nearby VO，不增加 `collected`，不引入 Review、AI、Activity 管理端、非固定座位票务、Redis Stream、MQ、前端或 GEO 自动同步。

Step 12.1 定向验证由 8 项 Collection Service 单元测试和 5 项真实 MySQL/Redis/随机端口 HTTP 测试组成，共 13 项通过。真实 HTTP 覆盖正常、重复和并发收藏、重复取消、关系检查、用户隔离、未登录、非法 ID、非法类型、Activity 不存在/草稿/下线，以及收藏后下线仍可检查和取消；并发请求由唯一索引收敛为一条关系。联调快照证明除预期 `tb_collection` 关系外，Activity、Showtime、ShowtimeSeat、TicketOrder、TicketOrderItem、`available_seats`、Venue GEO、`seat:lock:*` 和 `seat:lock:token:*` 均未改变。全量 439 项测试通过，0 failure、0 error、0 skipped，Maven `clean package` 为 `BUILD SUCCESS`。

Step 12.1 至此正式冻结。下一步只能在明确指定后进入独立 Step 12.2 Activity 收藏列表；不得在本 Step 继续加入 Activity `collected` 字段或其他阶段 12 后续能力。

### 9.2 Step 12.2：Activity 收藏列表

Step 12.2 只开放既有通用列表接口 `GET /api/collections?targetType=ACTIVITY`，继续复用 `CollectionQueryDTO`、`PageVO<CollectionVO>` 和 `ActivityListItemVO`，不增加 Activity 专用 Controller、DTO、VO、表、字段或 Redis Key。接口必须登录，只能读取当前用户关系，并按收藏时间倒序、收藏记录 ID 倒序稳定分页。

ACTIVITY 不沿用阶段 4 的“关系先分页、目标后批量读取并过滤”路径。`CollectionMapper.selectVisibleActivityPage` 在 MySQL 中通过 `tb_collection INNER JOIN tb_activity`，先限定当前用户、`target_type='ACTIVITY'` 和 `activity.status=1`，再进行 count、排序与分页。因此物理缺失、草稿和下线目标不会占据页内名额，分页 `total` 表示可展示 Activity 收藏总数，第二页也不会因为事后过滤出现空洞。

Service 从有效关系页提取本页全部 Activity ID，只调用一次 `ActivityMapper.selectBatchIds`，转换为既有活动摘要并按收藏关系页顺序恢复结果；循环内不执行 Mapper 查询。第二次状态过滤只用于防御关系分页与批量读取之间目标状态变化，不承担正常分页过滤。MOVIE、POST、REVIEW 继续走原 `selectPage` 和各自批量组装分支，不改变阶段 4 行为。

列表读取不会删除失效 `tb_collection`，也不会写 Activity、Showtime、ShowtimeSeat、TicketOrder、TicketOrderItem、`available_seats` 或任何 Redis/GEO 状态。本 Step 不增加 Activity 列表、详情或 Nearby 的 `collected` 字段，不实现阶段 12 的后续用户态注入能力。

Step 12.2 定向验证由 9 项 Collection Service 单元测试和 6 项真实 MySQL/Redis/随机端口 HTTP 测试组成，共 15 项通过。单元测试证明 ACTIVITY 使用专用有效关系分页且每页只批量读取一次目标，MOVIE 保持原通用分页路径；真实 HTTP 覆盖相同收藏时间的 ID 稳定倒序、第一页与第二页、物理缺失/草稿/下线过滤、过滤后 `total`、用户隔离、未登录，以及查询前后收藏关系、Activity 与 Redis Key 集合不变。全量 441 项测试通过，0 failure、0 error、0 skipped，Maven `clean package` 为 `BUILD SUCCESS`。

Step 12.2 至此正式冻结。后续只有在明确指定新 Step 后，才能向 Activity 列表、详情或 Nearby 增加当前用户 `collected` 状态；不得在本 Step 扩入评价、管理端、AI 或票务能力。

### 9.3 Step 12.3：Activity 普通列表和详情补充 collected

Step 12.3 只兼容扩展 `GET /api/activities` 和 `GET /api/activities/{id}`，在既有 `ActivityListItemVO`、`ActivityDetailVO` 末尾增加 `Boolean collected`。接口继续允许匿名访问；没有有效登录用户时 Service 直接返回 `false`，不访问收藏 Mapper。有效登录态仍由现有通用拦截器写入 `UserHolder`，不为 Activity 新建鉴权路径。

普通列表先按阶段 10 规则完成 `status=1`、城市、类型、关键词、`create_time DESC, id DESC` 和分页，再从本页 Activity 提取 ID，以当前用户和 `target_type=ACTIVITY` 执行一次 `tb_collection` 批量查询。内存集合只参与响应组装，不改变查询条件、顺序、页内元素或 `total`。详情先完成有效 Activity 校验，再最多执行一次当前用户对应关系计数。两条路径均不把用户私有状态写入公共缓存。

Nearby、Activity 场次、Showtime、锁座和订单路径未注入收藏查询或新增响应字段；MOVIE 继续使用自身已冻结的收藏状态逻辑。Step 12.2 收藏列表复用 `ActivityListItemVO`，该路径中的关系本身已证明收藏成立，因此仅在原批量组装时写入 `collected=true`，没有增加 Mapper 调用。

Step 12.3 不修改 SQL 结构，不新增 Redis Key 或缓存，不写 `tb_collection`、Activity、Showtime、ShowtimeSeat、TicketOrder、TicketOrderItem、`available_seats` 或 Venue GEO。定向验证包含 Activity Service 14 项、Activity Controller 8 项、真实 HTTP 7 项、Collection Service 9 项和 Movie Service 4 项，共 42 项通过；覆盖匿名与登录状态、收藏前后、用户隔离、列表一次批量查询、详情一次查询、共享 VO 和 MOVIE 回归。全量 445 项测试通过，0 failure、0 error、0 skipped，Maven `clean package` 为 `BUILD SUCCESS`。

Step 12.3 至此正式冻结。Nearby 的 `collected` 或其他 Activity 用户态只能在后续明确 Step 中实现，不得在本 Step 扩入评价、管理端、AI 或票务能力。

### 9.4 Step 12.4：Activity Nearby 补充 collected

Step 12.4 只在 `ActivityNearbyVO` 末尾兼容增加 `Boolean collected`。`ActivityServiceImpl.queryNearbyActivities` 的阶段 11 主算法保持原顺序：多 VenueType GEO 候选及 `min(page × pageSize × 5, 200)` 有界窗口、MySQL 有效性过滤、Activity 去重、最近 Venue 与下一场次选择、稳定排序和过滤后分页均先独立完成。Service 随后才从最终页 VO 提取 Activity ID，批量读取当前用户收藏关系并填充字段。

收藏状态不进入 `NearbyActivityChoice`，不参与候选合并、Activity 去重、场馆或场次选择、排序、分页及售罄资格。匿名用户和空结果直接跳过收藏 Mapper；匿名非空结果明确填充 `false`。登录用户无论最终页包含多少 Activity，都只执行一次 `tb_collection` 的 `IN` 查询，不进行逐 Activity 查询。

本 Step 没有修改 Redis GEO 读法、候选窗口、Showtime/Activity/Hall 批量读取次数或任何阶段 11 失败关闭条件，不写或清理 GEO、Redis 与 MySQL 业务数据，不新增缓存、Key、表、字段或索引。有效 Bearer 请求触发的既有登录 TTL 刷新属于通用认证机制，不是 Nearby 收藏业务状态。

验证重新运行阶段 11 相关 DTO、Controller、Service、边界、真实 MySQL/Redis/随机端口 HTTP 及 Activity 主链路扩展定向测试，共 81 项通过。测试证明匿名与不同登录用户的 Activity 集合、顺序、最近 Venue、下一 Showtime 完全一致，过滤后分页和 200 候选上限不变，售罄 Activity 仍可发现；收藏用户仅对应 `collected=true`，其他用户为 false。请求前后 Activity、Venue、Hall、Showtime、ShowtimeSeat、TicketOrder、TicketOrderItem、收藏关系、余票、GEO member 与临时锁快照保持不变。全量 446 项测试通过，0 failure、0 error、0 skipped，Maven `clean package` 为 `BUILD SUCCESS`。

Step 12.4 至此正式冻结。阶段 12 Activity 收藏的写路径、关系检查、收藏列表、普通 Activity 列表/详情及 Nearby 用户态已经闭环；不得在本 Step 扩入评价、管理端、AI 或票务能力。

### 9.5 Step 12.5 边界加固与阶段冻结

Step 12.5 不修改生产代码、SQL、API 契约或 Redis 数据结构，只通过自动化测试把阶段 12 的跨接口一致性和边界证据闭合。真实 MySQL、Redis 和随机端口 HTTP 主链路从未收藏状态开始，依次验证 Activity 普通列表、详情、Nearby、收藏写入、重复写入、收藏列表、关系检查、取消及重复取消，最终所有读接口与 `tb_collection` 恢复一致的未收藏状态。

并发证据包含同一用户并发收藏由唯一索引收敛为一条关系，以及收藏与取消并发后以 MySQL 最终关系为准，关系检查和收藏列表不得维护第二份状态。用户隔离证据包含两个用户同时收藏同一 Activity，各自关系独立，任一用户取消不会影响另一用户。失效目标、非法类型和非法 ID 继续沿用 Step 12.1 规则；已收藏 Activity 下线后在收藏列表分页前被过滤，但关系不会在读取中删除，并仍可取消。

分页证据覆盖相同收藏时间下按记录 ID 倒序、第二页、重复查询稳定性、深分页空列表和过滤后准确 `total`。Activity 普通列表与 Nearby 对本页最终 Activity ID 各执行一次收藏关系批量查询，详情最多执行一次关系查询；匿名路径不查询收藏表。MOVIE、POST、REVIEW 的收藏、检查、列表和取消生命周期回归继续走阶段 4 通用路径。

真实联调在请求前后比较 Activity、Showtime、ShowtimeSeat、TicketOrder、TicketOrderItem、`available_seats`、Venue GEO member、临时锁 Key、token 锁 Key 和 Redis Key 集合；除链路中预期创建后又删除的 Activity 收藏关系外，所有业务事实保持不变。测试清理后 Step 12.5 用户、Activity、Venue、Hall、Showtime、收藏关系和 GEO Key 均无残留。

阶段 12 与阶段 11 交叉定向测试共 103 项通过；全量 452 项测试通过，0 failure、0 error、0 skipped；真实 MySQL、Redis、随机端口 HTTP 联调通过，Maven `clean package` 为 `BUILD SUCCESS`。Step 12.5 至此正式冻结，阶段 12 完成；后续不得在阶段 12 内追加 Activity Review、Venue Review、管理端、AI 或票务能力。

## 10. 服务端模块建议

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

## 11. 关键非功能要求

- 所有写接口校验当前用户和资源归属。
- 订单、支付、锁座操作必须支持幂等。
- 释放锁座只接受服务端签发的 `lockToken`，不能依据客户端任意传入的座位 ID 释放资源。
- 列表接口必须分页，并限制单页最大数量。
- 关键链路记录 `requestId`，支付和锁座记录业务日志。
- 对手机号、支付信息和内部回调进行脱敏和验签。

电影详情和活动详情优先在 VO 中返回当前用户的收藏、点赞等附加状态，通用状态查询接口只作为兜底，避免详情页产生不必要的额外请求。

阶段 4 社区链路采用以下实现边界：收藏关系和关注关系以 MySQL 为唯一事实来源；帖子点赞关系使用 Redis ZSet、计数持久化在 `tb_post.liked`；关注 Feed 使用 Redis ZSet 推模式，帖子事务提交后再推送给粉丝。Feed 查询使用 `maxTime + offset` 滚动分页，批量查询帖子和用户后按 Redis 返回顺序恢复。删除帖子不遍历所有粉丝清理 Feed，而是在读取时惰性移除失效 postId。

电影表的 `actors` 第一版继续使用 `TEXT` 保存逗号分隔字符串，由 Service/Converter 拆分为 `MovieDetailVO.actors: List<String>`，不在数据库层提前引入 JSON 改造。

详细接口以 [API接口设计.md](API接口设计.md) 为准；当前 API 文档为 v0.25，阶段 10 固定座位 Activity 票务与阶段 11 Activity Nearby 保持冻结，阶段 12 Step 12.1～12.5 Activity 收藏写路径、关系检查、收藏列表、普通/Nearby 查询 `collected`、边界与完整联调均已完成并最终冻结。
