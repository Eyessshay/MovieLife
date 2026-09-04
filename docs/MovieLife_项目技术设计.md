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

## 6. 服务端模块建议

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

## 7. 关键非功能要求

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
