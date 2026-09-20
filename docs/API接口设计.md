# MovieLife API 接口设计

## 1. 文档说明

- 版本：v0.33（阶段 14 Review AI Summary 正式冻结版）
- 基础路径：`/api`
- 数据格式：JSON，字符集 UTF-8
- 认证方式：登录后通过 `Authorization: Bearer <token>` 传递令牌
- 本版目标：冻结资源边界、URL、HTTP 方法、核心业务语义，并完成核心交易接口的请求响应与幂等约束；其余接口字段在后续详细设计中补齐。

MovieLife 是以电影内容消费为核心、扩展本地文娱活动发现和购票的社区平台。接口按“内容发现 → 评价互动 → 场次选座 → 下单支付”的业务闭环组织。

## 2. 通用约定

### 2.1 统一响应

```json
{
  "code": "OK",
  "message": "ok",
  "data": {},
  "requestId": "..."
}
```

失败响应使用稳定的业务错误码：

```json
{
  "code": "SEAT_ALREADY_LOCKED",
  "message": "所选座位已被其他用户锁定",
  "data": null,
  "requestId": "..."
}
```

HTTP 状态码表达协议层结果，业务 `code` 表达具体错误类型。例如座位冲突返回 HTTP `409 Conflict`，同时返回 `SEAT_ALREADY_LOCKED`。分页接口暂定返回 `data.list`、`data.total`、`data.page`、`data.pageSize`。

### 2.2 HTTP 状态码

| 状态码 | 含义                               |
| ------ | ---------------------------------- |
| 200    | 查询或修改成功                     |
| 201    | 创建成功                           |
| 204    | 删除成功且无响应体                 |
| 400    | 参数校验失败                       |
| 401    | 未登录或令牌无效                   |
| 403    | 无权限操作资源                     |
| 404    | 资源不存在                         |
| 409    | 状态冲突，如座位已被锁定、重复评价 |
| 429    | 请求过于频繁                       |
| 500    | 服务端异常                         |

### 2.3 查询与分页

列表接口统一预留：`page`、`pageSize`、`sortBy`、`sortOrder`。默认按业务相关性或创建时间倒序；筛选项按模块定义。

### 2.4 认证分级

- 公开：电影、活动、场馆、场次的查询接口。
- 登录：个人信息、关注、评价、点赞、订单、选座锁定。
- 管理端：排片、影厅座位、活动、内容审核和验票核销接口，统一放在 `/api/admin`。管理端接口除登录外还必须校验具体业务权限；普通登录用户不能因持有有效 Token 而获得管理能力。

## 3. API 清单

### 3.1 用户与认证

```bash
POST   /api/user/code                 # 获取短信验证码
POST   /api/user/login                # 手机号验证码登录
POST   /api/user/logout               # 退出登录

GET    /api/user/me                   # 获取当前用户
PUT    /api/user/me                   # 修改当前用户资料
GET    /api/user/{id}                 # 获取用户公开资料

PUT    /api/follow/{id}               # 关注用户
DELETE /api/follow/{id}               # 取消关注
GET    /api/follow/common/{id}        # 查询与指定用户的共同关注
GET    /api/follow/following/{id}     # 查询用户关注列表
GET    /api/follow/followers/{id}     # 查询用户粉丝列表
```

约束：私人资料读写统一使用 `/me`，`GET /api/user/{id}` 只返回公开资料；验证码发送和登录需要限流；用户公开资料不得返回手机号等敏感信息。

### 3.2 电影

```bash
GET /api/movies                         # 电影列表、筛选和分页
GET /api/movies/{id}                    # 电影详情
GET /api/movies/hot                     # 热映/热门电影
GET /api/movies/coming                  # 即将上映
GET /api/movies/{id}/showtimes          # 电影排片
GET /api/movies/{id}/reviews            # 电影评价
```

预留筛选：城市、状态、类型、地区、上映时间、评分区间和关键词。推荐接口后续接入“行为采集 → 标签打分 → 候选召回 → 规则排序 → AI 推荐理由”。

### 3.3 活动

```bash
GET /api/activities                     # 活动列表、筛选和分页
GET /api/activities/{id}                # 活动详情
GET /api/activities/hot                 # 热门活动
GET /api/activities/nearby              # 附近活动
GET /api/activities/{id}/showtimes      # 活动场次
```

阶段 10 仅接入“固定座位活动”，即使用既有 Venue、Hall、Seat、Showtime 和 ShowtimeSeat 表达的演出、脱口秀、话剧等活动。站票、通票、按票档扣减库存、多日票、套票以及不选座活动均不属于本阶段；不得用不存在的 Seat 或虚拟 Seat 伪装这些票型。

Step 10.1 冻结、Step 10.2 已实现以下公开只读查询契约：

1. `GET /api/activities`：无需登录。Query 参数为 `city`、`type`、`keyword`、`page` 和 `pageSize`；`page` 默认 1 且必须大于等于 1，`pageSize` 默认 20 且范围为 1～100。只返回 `tb_activity.status=1` 的活动，`keyword` 对 `title` 做包含匹配，结果按 `create_time DESC, id DESC` 稳定排序。响应列表项包含 `id`、`title`、`coverUrl`、`type`、`city`、`priceMin` 和 `priceMax`。无匹配数据返回空分页，不返回 `ACTIVITY_NOT_FOUND`。`priceMin/priceMax` 只用于活动发现页展示，锁座和订单金额始终以具体 ShowtimeSeat.price 为准。
2. `GET /api/activities/{id}`：无需登录。只返回 `status=1` 的活动；不存在、草稿或已下线统一返回 `404 ACTIVITY_NOT_FOUND`。响应包含列表字段及 `description`，本 Step 不附带收藏、评价或 AI 总结状态。
3. `GET /api/activities/{id}/showtimes`：无需登录。Query 参数为可选 `date=yyyy-MM-dd` 和 `venueId`；非法日期或非正数 venueId 返回 `400 INVALID_PARAM`。只返回活动有效、`biz_type=ACTIVITY`、`biz_id=活动 ID`、`status=1`、`start_time>NOW()`，且 Venue 有效、Hall 属于该 Venue 的场次；有日期时限定当地自然日范围。每项包含 `showtimeId`、`venueId`、`venueName`、`hallId`、`hallName`、`startTime`、`endTime`、`basePrice` 和 `availableSeats`，实现时批量读取 Venue 与 Hall，禁止 N+1。结果按 `start_time ASC, id ASC` 排序，无匹配数据返回 `[]`。

Step 12.3 对前两项普通 Activity 查询做兼容扩展：`ActivityListItemVO` 与 `ActivityDetailVO` 均新增 `collected: Boolean`。匿名访问继续允许并固定返回 `false`；携带有效登录态时，按当前用户、`target_type=ACTIVITY` 和目标 ID 从 `tb_collection` 返回真实状态。列表只对已经完成业务分页的本页 Activity ID 执行一次批量关系查询，详情最多执行一次关系查询；`collected` 不参与 Activity 状态、城市、类型、关键词筛选，不影响 `create_time DESC, id DESC` 排序、分页 `total`、价格或可见性规则。用户私有收藏状态不得写入公共缓存；Nearby、场次及票务响应不在本 Step 增加该字段。

Step 12.4 继续兼容扩展 `GET /api/activities/nearby`，仅在阶段 11 完成 Venue GEO 候选、MySQL 有效性过滤、Activity 去重、最近 Venue/下一场选择、稳定排序和过滤后分页之后，对最终一页 Activity ID 补充 `collected`。匿名请求统一返回 `false` 且不查询收藏表；登录请求只执行一次当前用户 `target_type=ACTIVITY AND target_id IN (...)` 的批量关系查询。收藏状态不参与候选合并、去重、上下文选择、排序或分页，`min(page × pageSize × 5, 200)` 与阶段 11 全部只读边界保持不变。

固定座位活动场次进入 Seat Map 和购票链路前，必须具有至少一个启用物理 Seat，并为全部启用 Seat 建立同场次 ShowtimeSeat；`total_seats` 必须与启用座位数量一致，`available_seats` 仍是由 ShowtimeSeat 状态派生的计数。关联缺失、重复或数量不一致属于 `500 INTERNAL_ERROR`，不能默认补成 AVAILABLE。Step 10.2 已将初始化 Activity 场次补成 Hall 3 下 6 个启用物理 Seat 和 6 条 ShowtimeSeat，并提供可重复执行的 `V10_2__seated_activity_seed.sql`；迁移按现有 ShowtimeSeat 状态重新计算余票，不把已售座位重置为可售。

活动票务复用现有通用路径，不复制 `/api/activity-orders` 或活动专用锁座接口：

```bash
GET  /api/showtimes/{id}
GET  /api/showtimes/{id}/seats
POST /api/showtimes/{id}/seats/lock
POST /api/showtimes/{id}/seats/release
POST /api/orders
GET  /api/orders
GET  /api/orders/{id}
POST /api/orders/{id}/cancel
POST /api/orders/{id}/pay
POST /api/orders/{id}/issue
POST /api/orders/{id}/refund
POST /api/admin/tickets/verify
```

`GET /api/showtimes/{id}` 对电影保持原 `movieId/movieName` 字段和语义，兼容增加 `bizType`、`activityId`、`activityName`：电影场次返回 `bizType=MOVIE` 且活动字段为 `null`；活动场次返回 `bizType=ACTIVITY`、活动字段有值且电影字段为 `null`。Seat Map 的既有字段不变。订单列表与详情采用相同的兼容扩展规则，保留电影字段并增加 `bizType`、`activityId`、`activityName`；查询实现必须按本页 Showtime 的业务类型分别批量读取 Movie 和 Activity，禁止逐订单查询。

Activity 首次下单与电影订单遵守完全相同的交易事实：Redis `TEMP_LOCKED` 只提供创建前资格，MySQL 中的 TicketOrder、TicketOrderItem、ShowtimeSeat 和 `available_seats` 是订单后的最终事实；lockToken 只能消费一次，已存在订单时先按 MySQL 幂等返回。取消、超时、支付、出票、退款和核销继续复用阶段 8～9 的事务、锁顺序、条件 UPDATE、affectedRows 校验和 Redis 边界，不增加 Activity 专属状态。

Step 10.2 已增加 Activity Entity、Mapper、Service、Controller、DTO、VO、`ACTIVITY_NOT_FOUND` 错误码及三项公开 HTTP 查询。活动场次列表只读取 MySQL，按一批场次批量补齐 Venue/Hall，并对场次核心字段和归属关系做失败关闭校验；该 Step 不读取或写入任何 Redis 活动状态，也不修改订单、票明细、ShowtimeSeat 或 `available_seats`。

Step 10.2 冻结证据：迁移在隔离 MySQL 8.0.17 上连续执行两次后仍为 1 个有效 Activity 场次、6 个启用物理 Seat 和 6 条 ShowtimeSeat；真实 HTTP 测试完成活动列表 → 详情 → 场次查询，并确认 MySQL 对应记录及 `seat:lock:*` 集合前后不变。全量 359 项测试通过，0 failure、0 error、0 skipped，Maven 构建成功。

Step 10.3 已让既有 `GET /api/showtimes/{id}` 和 `GET /api/showtimes/{id}/seats` 支持固定座位 Activity，不增加活动专用路径。Showtime 详情兼容增加 `bizType`、`activityId`、`activityName`：MOVIE 保留 `movieId/movieName` 且活动字段为 `null`，ACTIVITY 的电影字段为 `null`；Venue、Hall 或对应业务资源关系缺失返回 `INTERNAL_ERROR`，停用 Venue/Activity、无效或已开场场次返回 `SHOWTIME_NOT_FOUND`，未知 `bizType` 不对外暴露。

Activity Seat Map 继续返回既有 `ShowtimeSeatMapVO/SeatVO` 字段，但额外要求至少一个启用物理 Seat、`total_seats` 等于启用 Seat 数、全部启用 Seat 均具有合法且唯一的 ShowtimeSeat、`available_seats` 等于其中 AVAILABLE 数量；状态范围、价格或关联不合法统一返回 `INTERNAL_ERROR`，不能补默认可售状态。MOVIE 保持阶段 6 冻结行为，包括没有启用物理 Seat 时返回空列表。本 Step 全程只读 MySQL，不读取或写入 Redis，不修改任何库存或订单事实。

Step 10.3 冻结证据：真实 HTTP 验证动态 2 座 Activity 和初始化 6 座 Activity 的通用场次详情与 Seat Map，查询前后 Activity、Showtime 和 `seat:lock:*` 均不变；全量 370 项测试通过，0 failure、0 error、0 skipped，Maven 构建成功。活动锁座、创建订单、订单展示及后续交易兼容仍未实现；活动收藏、评价、附近活动、管理端、非固定座位票务、第三方支付、部分退款、核销撤销、Redis Stream、MQ 和 AI 继续留待后续独立 Step。

Step 10.4 已让既有 `POST /api/showtimes/{id}/seats/lock`、`POST /api/showtimes/{id}/seats/release` 和 `POST /api/orders` 支持固定座位 Activity，不增加 Activity 专用接口或状态。Activity 锁座在写入 Redis 前复用 Step 10.3 的完整 Seat Map 校验，再校验所选物理 Seat 与 ShowtimeSeat；任何整体数据缺失、状态非法或余票计数不一致均失败关闭，不签发临时资格。MOVIE 锁座保持阶段 7 的原查询和 Lua 路径。

首次创建 Activity 订单时，仍先按 `lockToken` 查询 MySQL：若已有归属和请求一致的订单，直接返回原订单，不以 Redis 是否存在为前提；只有 token 尚未消费时才检查 Redis 临时资格。新订单在原单个 MySQL 事务内、任何写入之前再次校验 Activity Seat Map，然后按既有顺序插入 TicketOrder、批量插入 TicketOrderItem、条件占用全部 ShowtimeSeat、扣减 `available_seats`，并严格校验全部 affectedRows。订单金额只取 ShowtimeSeat.price；提交成功后才按 token 条件清理 Redis。释放接口仍只根据服务端签发的 token 原子清理 Redis，不修改 MySQL。

Step 10.4 真实 HTTP 闭环已验证 Activity 锁座、主动释放、再次锁座、创建 UNPAID 订单、两档座价求和、MySQL 座位与余票事实、COMMIT 后 Redis 清理，以及 Redis 已不存在时相同 token 返回原订单；请求座位不一致仍返回 `ORDER_REQUEST_MISMATCH`。全量 375 项测试通过，0 failure、0 error、0 skipped，Maven 构建成功。本 Step 不实现 Activity 订单列表/详情展示兼容，也不扩展取消、超时、支付、出票、退款或核销验证；不新增 SQL、Redis 状态、Stream、MQ、第三方支付、部分退款或核销撤销。

Step 10.5 已让既有 `GET /api/orders` 和 `GET /api/orders/{id}` 展示固定座位 Activity 订单。`TicketOrderListItemVO` 与 `TicketOrderDetailVO` 兼容增加 `bizType`、`activityId`、`activityName`：ACTIVITY 返回活动字段且 `movieId/movieName` 为 null；MOVIE 保留原电影字段且活动字段为 null。列表按本页 Showtime 的业务类型分组，分别批量读取 Movie 和 Activity 后恢复原订单顺序；Activity 下线不隐藏已经成立的历史订单，关联记录物理缺失或未知 `bizType` 返回 `INTERNAL_ERROR`。

Step 10.5 真实 HTTP 联调沿用 Activity 锁座与创建订单闭环，继续查询订单列表和详情，验证活动名称、场次、场馆、影厅、座位、价格快照和票状态，并确认查询前后 TicketOrder、TicketOrderItem、ShowtimeSeat、`available_seats` 和 Redis 临时锁集合完全不变。全量 378 项测试通过，0 failure、0 error、0 skipped，Maven 构建成功。本 Step 不修改 SQL，不扩展取消、超时、支付、出票、退款或核销的 Activity 验证，也不新增 Redis 状态或后续阶段能力。

Step 10.6 已确认既有 `POST /api/orders/{id}/cancel` 与后台 `TicketOrderTimeoutJob` 可直接处理固定座位 Activity 订单，不增加 Activity 专用 Controller、Service、状态或定时任务。两条入口都以 TicketOrder 行锁串行化，随后按订单明细中的完整 seatId 集合条件释放 `HELD_BY_UNPAID_ORDER` 且 `lock_order_id=当前订单` 的 ShowtimeSeat，严格要求 affectedRows 等于明细数，再恢复不超过 `total_seats` 的 `available_seats` 并将订单条件更新为 `CANCELED`；任何一步失败均回滚整个订单事务。Activity 是否下线不影响已经成立订单的资源释放。

主动取消继续只允许订单本人操作；`CANCELED` 重复请求幂等成功，非 `UNPAID/CANCELED` 状态冲突，且不因为 `expireTime` 已过而拒绝数据库中仍为 `UNPAID` 的订单。超时任务继续限量扫描到期 UNPAID 订单并逐单独立事务复核；已取消、已支付、未到期或不存在的订单跳过，单订单失败不阻断后续订单，不新增 `EXPIRED` 状态。主动取消和超时取消竞争时只能有一方实际释放座位并恢复余票。

Step 10.6 真实联调覆盖 Activity 锁座 → 创建 UNPAID 订单 → 主动取消、重复主动取消、下线 Activity 历史订单取消、定时超时取消、重复任务、主动与超时并发竞争，以及部分座位状态异常时的事务全回滚。成功取消后 TicketOrderItem 价格和票状态保持不变，ShowtimeSeat 全部恢复 AVAILABLE、清空 `lock_order_id`，`available_seats` 只恢复一次；取消前后 Redis `seat:lock:*` 集合不变。全量 382 项测试通过，0 failure、0 error、0 skipped，Maven 构建成功。本 Step 不修改生产 Java、SQL 或 Redis，不提前扩展 Activity 支付、出票、退款和核销。

Step 10.7 已确认既有 `POST /api/orders/{id}/pay` 可直接处理固定座位 Activity 订单，不增加 Activity 专用接口、请求参数、支付状态或响应类型。支付只接受本人尚未超过 `expireTime` 的 UNPAID 订单；PAID 重复请求幂等返回首次 `payTime`，到期订单返回 `ORDER_EXPIRED` 且不在支付事务内顺手取消，CANCELED 或 REFUNDED 返回 `ORDER_STATUS_CONFLICT`。Activity 下线不影响已经成立订单支付，支付以 MySQL 订单事实为准。

支付继续在一个 MySQL 事务中先锁 TicketOrder，随后读取全部 TicketOrderItem，再按 `showtime_id + seatIds + status=HELD_BY_UNPAID_ORDER + lock_order_id=当前订单` 条件批量将 ShowtimeSeat 更新为 SOLD、递增版本并保留 `lock_order_id`。座位 affectedRows 必须等于订单明细数，随后以 `id + status=UNPAID + expire_time>payTime` 条件更新订单为 PAID；任一步失败整体回滚。创建订单时已经扣减 `available_seats`，因此支付不再修改余票，不生成 ticketCode，也不改变 TicketOrderItem 的 PENDING 状态。

Step 10.7 真实联调覆盖 Activity 锁座 → 创建订单 → 支付 → 重复支付、下线 Activity 历史订单支付、过期与已取消订单拒绝、双支付、支付与主动取消竞争、过期支付与超时取消竞争，以及部分座位状态异常时事务全回滚。合法并发终态只能是 `PAID + SOLD` 或 `CANCELED + AVAILABLE`，双支付只写入一个 payTime 和一次座位版本变化；支付前后 `available_seats` 与 Redis `seat:lock:*` 集合保持不变。全量 388 项测试通过，0 failure、0 error、0 skipped，Maven 构建成功。本 Step 不修改生产 Java、SQL 或 Redis，不提前实现 Activity 出票、退款和核销，也不引入第三方支付或异步通知。

Step 10.8 已确认既有 `POST /api/orders/{id}/issue` 可直接为固定座位 Activity 的本人 PAID 订单出票，不增加 Activity 专用接口、参数、VO、票状态或票码规则。出票以已经成立的 TicketOrder 与 TicketOrderItem 为 MySQL 事实，不查询 Movie/Activity 作为前置条件，因此 Activity 后续下线不影响历史 PAID 订单出票；非 PAID 状态仍返回 `ORDER_STATUS_CONFLICT`。

出票继续在单个 MySQL 事务内先锁 TicketOrder，再按 seatId 排序读取完整 TicketOrderItem 并校验订单归属、明细 ID、座位唯一性以及票码/票状态组合。PENDING 且票码为空的明细生成 32 位票码并通过单条条件批量 UPDATE 写为 VALID，affectedRows 必须等于待出票数；已为 VALID 且票码非空的明细保持原值，全部已出票时直接返回原票码，部分已出票时只补齐剩余明细。唯一索引冲突、条件更新行数不足或异常明细均失败关闭并由事务整体回滚，不接受部分成功。

Step 10.8 真实 HTTP 联调覆盖 Activity 正常出票与重复幂等、下线 Activity 历史订单出票、部分出票恢复、双出票并发以及票码/状态异常时零部分写入，并复用原出票事务测试确认批量唯一键冲突整体回滚。出票前后 TicketOrder 保持 PAID 且 `payTime` 不变，ShowtimeSeat 保持 SOLD 并保留 `lock_order_id`，`available_seats`、明细价格和 Redis `seat:lock:*` 集合均不变。全量 393 项测试通过，0 failure、0 error、0 skipped，Maven 构建成功。本 Step 不修改生产 Java、SQL 或 Redis，不提前验证 Activity 退款和核销，也不引入 Redis Stream、MQ、第三方支付、部分退款或核销撤销。

Step 10.9 已确认既有 `POST /api/orders/{id}/refund` 可直接处理固定座位 Activity 整单退款，不增加 Activity 专用接口、请求参数、退款状态或响应类型。仅本人 PAID 且场次尚未开始的订单可退款，REFUNDED 重复请求幂等成功，UNPAID/CANCELED 返回 `ORDER_STATUS_CONFLICT`；Activity 下线不影响已经成立的历史订单退款。任一票明细为 USED 时，在任何写入前返回 `REFUND_NOT_ALLOWED`，不接受部分座位退款。

退款继续在单个 MySQL 事务中先锁 TicketOrder，再按明细 ID 顺序锁定完整 TicketOrderItem。全部明细必须为 PENDING 或 VALID，随后按 `showtime_id + 完整 seatIds + status=SOLD + lock_order_id=当前订单` 条件批量恢复 ShowtimeSeat 为 AVAILABLE、清空 `lock_order_id` 并递增版本；affectedRows 必须等于明细数。之后恢复不超过 `total_seats` 的 `available_seats`、条件更新订单 `PAID → REFUNDED`，并把全部票明细统一更新为 REFUNDED。每一步影响行数均严格校验，任一步异常都整体回滚。

Step 10.9 真实 HTTP 联调覆盖未出票 Activity 整单退款、已出票整单退款并保留原票码、重复退款幂等、下线 Activity 历史订单退款、已开场或存在 USED 明细时零写入拒绝、双退款并发只恢复一次，以及部分座位状态异常时事务全回滚。退款保留 `payTime`、`lockToken`、订单明细、价格和既有 `ticketCode`，不读取或写入 Redis。全量 399 项测试通过，0 failure、0 error、0 skipped，Maven 构建成功。本 Step 不修改生产 Java、SQL 或 Redis，不提前实现 Activity 核销，也不引入真实退款渠道、退款回调、部分退款、Redis Stream 或 MQ。

Step 10.10 已确认既有 `POST /api/admin/tickets/verify` 可直接核销固定座位 Activity 票，不增加 Activity 专用接口、DTO、VO、权限表或票状态。请求仍只包含 `ticketCode`；服务端根据票明细关联订单与 Showtime，以启用的 `tb_venue_staff(user_id, venue_id)` 校验工作人员是否属于该 Activity 场次的实际 Venue。Activity 下线不影响已经成立且有效的活动票核销；无本场馆权限、跨场馆或停用工作人员统一返回 `TICKET_VERIFY_FORBIDDEN`。

核销继续在单个 MySQL 事务中先定位票明细，再锁 TicketOrder，随后锁目标 TicketOrderItem，保持与整单退款相同的订单后明细锁顺序。仅 PAID 订单的 VALID 票可通过 `id + order_id + ticket_status=VALID + used_time/used_by_user_id IS NULL` 条件更新为 USED，并在同一条 UPDATE 中写入首次 `usedTime` 和 `usedByUserId`；affectedRows 必须为 1。重复核销严格返回 `TICKET_ALREADY_USED`，不得覆盖首次核销证据；跨场馆拒绝发生在写入前。

Step 10.10 真实 HTTP 联调覆盖下线 Activity 的本场馆工作人员首次核销与重复拒绝、跨场馆工作人员拒绝且零写入、双核销并发仅一次成功，以及 Activity 核销与整单退款竞争。竞争终态只能是“整单 REFUNDED、核销返回 TICKET_REFUNDED”或“目标票 USED、订单保持 PAID、退款返回 REFUND_NOT_ALLOWED”。核销不修改 TicketOrder、ShowtimeSeat、`available_seats` 或 Redis。全量 403 项测试通过，0 failure、0 error、0 skipped，Maven 构建成功。本 Step 不修改生产 Java、SQL 或 Redis，不实现核销撤销、批量核销、电子闸机或异步消息。

Step 10.11 将阶段 10 冻结为固定座位 Activity 完整票务闭环，不新增接口、请求响应字段、数据库结构、Redis Key 或业务状态。主链路继续使用上述通用接口，按“活动查询 → 活动场次 → 通用场次详情 → Seat Map → Redis 临时锁座 → MySQL 创建 UNPAID 订单 → 订单查询 → 模拟支付 → 出票 → 场馆核销”顺序完成真实 HTTP 回归；最终事实必须为订单 PAID、所选 ShowtimeSeat 全部 SOLD 且归属原订单、一个目标票 USED、其余票 VALID、`available_seats=0`，并且创建订单提交后不得残留临时锁座 Key。

阶段冻结回归同时纳入主动取消、后台超时取消、支付与取消竞争、过期支付与超时竞争、未出票和已出票整单退款、核销与退款竞争，以及重复下单、支付、出票、退款、核销的幂等或严格拒绝行为。取消或超时成功只能形成 `CANCELED + AVAILABLE`；退款成功只能形成 `REFUNDED + 全明细 REFUNDED + AVAILABLE`；已核销票不得退款。所有写操作继续使用既有锁顺序、条件 UPDATE、严格 affectedRows 和单个 MySQL 事务，不接受部分成功。Step 10.11 不实现站票、通票、票档库存、第三方支付、部分退款、核销撤销、Redis Stream 或 MQ；阶段 10 至此完成并冻结，后续能力必须作为新阶段单独设计。

Step 10.11 冻结证据：隔离 MySQL 8.0.17、当前 `movielife.sql` 与兼容 GEO 的 Redis 下，Activity 定向 29 项和全量 404 项自动化测试全部通过，0 failure、0 error、0 skipped，Maven `clean package` 为 `BUILD SUCCESS`。

#### 阶段 11 Step 11.1：Activity Nearby 详细契约

阶段 11 只实现附近活动发现，同时纳入“浏览附近活动”和“查询指定活动的附近场馆”两个互补场景；不改动阶段 1～10，不包含非固定座位票务、Activity 收藏、Venue/Activity Review、Activity 管理端、AI、前端或 GEO 自动同步。Step 11.1 只冻结设计，不修改生产 Java、SQL、Redis Key 或公开接口实现。

##### 3.3.1 查询附近活动

1. URL：`/api/activities/nearby`
2. Method：`GET`
3. 是否登录：否
4. Query 参数：
   - `city: String`：必填，去除首尾空白后不能为空；表示目标 Venue 所在城市，不以 `tb_activity.city` 判断巡演城市。
   - `x: Double`：必填，经度范围 `-180～180`。
   - `y: Double`：必填，纬度范围 `-90～90`。
   - `distance: Integer`：可选，单位米，默认 `5000`，范围 `100～50000`。
   - `page: Integer`：可选，默认 `1`，必须大于等于 `1`。
   - `pageSize: Integer`：可选，默认 `20`，范围 `1～100`。
   - `type: String`：可选，按 `tb_activity.type` 精确过滤。
   - `venueType: VenueType`：可选，取值为 `CINEMA`、`THEATER`、`LIVEHOUSE` 或 `EXHIBITION`；为空时查询全部四类场馆。
5. Request DTO：`ActivityNearbyQueryDTO`，只承载上述 Query 参数，不接受用户 ID、Activity ID、Showtime ID 或座位信息。
6. Response VO：`List<ActivityNearbyVO>`，不返回无法精确计算的 `total`。每项包含 `id`、`title`、`coverUrl`、`type`、`priceMin`、`priceMax`、`nearestVenueId`、`nearestVenueName`、`nearestVenueType`、`nearestVenueAddress`、`venueCity`、`distance`、`nextShowtimeId`、`nextStartTime` 和 `nextAvailableSeats`；距离单位为米。`priceMin/priceMax` 仍只用于发现页展示，订单价格不读取这两个字段。
7. 业务错误码：参数缺失、越界或格式非法返回 `400 INVALID_PARAM`；非法场馆类型返回 `400 INVALID_VENUE_TYPE`；Redis GEO 异常、非法 GEO member、业务关联缺失或场次核心数据损坏返回 `500 INTERNAL_ERROR`。没有符合条件的活动正常返回 `[]`。
8. 数据库 / Redis 行为：不建立 `activity:geo:*`。服务根据 `venueType` 查询一个或全部 `venue:geo:{city}:{VenueType}`，Redis 只返回 `venueId + distance`；随后批量读取 MySQL 中启用的 Venue、未来有效 ACTIVITY Showtime、Activity 和 Hall。全链路只读，不创建或刷新缓存，不读取或写入 `seat:lock:*`，不修改订单、座位、余票或任何业务状态。

##### 3.3.2 查询指定活动的附近场馆

1. URL：`/api/venues/nearby`
2. Method：`GET`
3. 是否登录：否
4. Query 参数：继续使用现有 `city`、`type`、`x`、`y`、`distance`、`page`、`pageSize`、`bizType` 和 `bizId`；本阶段只把 `bizType` 的合法范围从 `MOVIE` 扩展为 `MOVIE/ACTIVITY`。查询 Activity 时必须同时传 `bizType=ACTIVITY&bizId={activityId}`，`bizType` 与 `bizId` 仍必须同时存在或同时为空。
5. Request DTO：复用 `VenueNearbyQueryDTO`，不新增 Activity 专用 DTO。
6. Response VO：复用 `List<VenueNearbyVO>`，字段保持 `id`、`name`、`type`、`city`、`address`、`avgScore`、`openTime` 和 `distance`，不破坏 MOVIE 客户端契约。
7. 业务错误码：参数组合非法返回 `400 INVALID_PARAM`；不支持的业务类型返回 `400 INVALID_BIZ_TYPE`；目标 Activity 不存在、草稿或下线返回 `404 ACTIVITY_NOT_FOUND`；Redis GEO 或数据完整性异常返回 `500 INTERNAL_ERROR`。没有未来有效场次时返回 `[]`。
8. 数据库 / Redis 行为：继续先从单个 `venue:geo:{city}:{type}` 召回候选 Venue，再以 `biz_type=ACTIVITY`、`biz_id=activityId`、`status=1`、`start_time>NOW()` 批量筛选存在未来场次的 Venue；同一 Venue 多个场次只返回一次。只读现有 Redis GEO 和 MySQL，不新增 Key、表、字段或索引。

##### 3.3.3 合并、去重、排序与分页

- 多 VenueType 查询对每个 GEO Key 使用相同的半径、升序和候选上限，再按 `venueId` 合并；同一 Venue 重复出现时保留最小距离。MySQL 必须重新校验 Venue 的 `status=1`、`city`、`type` 和经纬度，已删除、停用、跨城市或落入错误类型 Key 的历史 member 直接忽略。
- 业务过滤候选窗口统一为 `min(page × pageSize × 5, 200)`；`GET /api/activities/nearby` 对每个被查询的 VenueType Key 应用该上限，`GET /api/venues/nearby` 对单个 Key 应用该上限。200 是 MVP 的有界召回，不承诺完整深分页；超过候选窗口且无法形成当前页时返回 `[]`。
- 附近活动只接受 `tb_activity.status=1`，以及 `biz_type=ACTIVITY`、`status=1`、`start_time>NOW()` 的场次。Showtime 必须具有合法时间、Venue/Hall 关系、正数 `total_seats` 和 `0≤available_seats≤total_seats`。Activity 记录物理缺失、Hall 缺失或归属错误、场次核心字段非法均失败关闭为 `INTERNAL_ERROR`；已下线 Activity 被过滤，不作为系统异常。
- `available_seats=0` 的未来在售场次仍可参与附近活动发现，响应通过 `nextAvailableSeats=0` 表达售罄；本阶段不把发现资格等同于可立即购票资格。
- 同一 Activity 跨场馆或多场次时只返回一次：先选择距离最近的有效 Venue，距离相同时选择 `venueId` 较小者；再在该 Venue 内选择 `start_time ASC, showtimeId ASC` 的下一场。最终结果按 `distance ASC, nextStartTime ASC, activityId ASC` 排序，完成 Activity 去重和业务过滤后再按 `page/pageSize` 截取。
- 实现必须批量读取 Venue、Showtime、Activity 和 Hall，禁止按 Venue、Activity 或 Showtime 循环查询；普通 Activity 列表、MOVIE Nearby 和阶段 10 的所有票务行为保持不变。

##### 3.3.4 Step 11.1 验收矩阵与后续拆分

| 层级 | Step 11.1 冻结的验收范围 |
| --- | --- |
| 参数与 Controller | 两个公开 GET 场景、默认值、坐标/距离/分页边界、VenueType 与 bizType 错误映射 |
| Service | 多 GEO Key 合并、MySQL 过滤、Activity 去重、最近 Venue/下一场选择、稳定排序、过滤后分页、禁止 N+1 |
| 数据异常 | 空 Key、历史 member、停用 Venue/Activity、过期或停售 Showtime、损坏的 Hall/Venue/Showtime 关系 |
| 真实联调 | 真实 Redis GEO + MySQL + 随机端口 HTTP，覆盖跨 VenueType、跨场馆、多场次、售罄展示和指定 Activity 场馆过滤 |
| 只读边界 | 联调前后 MySQL 表、现有 GEO member、`seat:lock:*`、订单、ShowtimeSeat 和 `available_seats` 均不变化 |

后续实现按独立小 Step 推进：Step 11.2 只扩展指定 Activity 的附近场馆；Step 11.3 只实现附近活动主接口；Step 11.4 加固异常、候选窗口和分页；Step 11.5 完成真实主链路、全量回归、`BUILD SUCCESS`、文档同步、commit 并冻结阶段 11。不得提前合并后续 Step。

##### 3.3.5 Step 11.2：指定 Activity 的附近场馆实现冻结

Step 11.2 已按 3.3.2 契约实现并冻结：既有匿名接口 `GET /api/venues/nearby` 的 `VenueNearbyQueryDTO.bizType` 从仅允许 `MOVIE` 扩展为严格允许 `MOVIE/ACTIVITY`。`bizType=ACTIVITY` 时先读取 `tb_activity` 并要求记录存在且 `status=1`，否则返回 `404 ACTIVITY_NOT_FOUND`；随后从单个 `venue:geo:{city}:{VenueType}` 按距离升序召回 `min(page × pageSize × 5, 200)` 个候选，批量查询 `tb_showtime` 中 `biz_type=ACTIVITY`、`biz_id=activityId`、`status=1`、`start_time>NOW()` 的场馆 ID，按集合去重并保持原 GEO 距离顺序，最后执行过滤后分页。没有符合条件的未来场次返回 `[]`。

实现复用了 `VenueNearbyVO` 和既有 MySQL/Redis 数据模型，仅增加 Activity 状态校验和一条批量 Showtime Mapper 查询；MOVIE 分支仍调用原有 Movie 校验和原有批量场次查询，接口参数、错误码、排序、候选窗口与响应结构均未改变。查询全程只读，不新增或刷新 Redis 状态，不触碰 `seat:lock:*`、订单、ShowtimeSeat 或 `available_seats`，未修改 `sql/movielife.sql`，也未新增 Redis Key。

冻结验证覆盖 DTO 参数组合与非法类型、Activity 不存在/下线、未来/过期/停售场次、同场馆多场次去重、GEO 顺序、过滤后分页、MOVIE 分支回归及 Redis 异常映射。真实 MySQL + 兼容 GEO 的 Redis + 随机端口 HTTP 联调 15 项通过，并比较 Activity、Showtime 与 GEO member 的请求前后快照确认零写入；全量 411 项测试通过，0 failure、0 error、0 skipped，Maven `clean package` 为 `BUILD SUCCESS`。

Step 11.2 不实现 `GET /api/activities/nearby`、多 VenueType 合并、附近活动去重与下一场选择，也不包含 Step 11.4/11.5、非固定座位票务、Activity 收藏、Venue/Activity Review、Activity 管理端、AI、前端或 GEO 自动同步；这些能力继续按后续独立 Step 推进。

##### 3.3.6 Step 11.3：附近活动主接口实现冻结

Step 11.3 已实现匿名接口 `GET /api/activities/nearby`，请求由独立 `ActivityNearbyQueryDTO` 承载：`city/x/y` 必填，`distance=5000`、`page=1`、`pageSize=20` 为默认值，支持 Activity `type` 精确过滤和可选 `venueType`。未指定 `venueType` 时查询 `CINEMA/THEATER/LIVEHOUSE/EXHIBITION` 四个既有 GEO Key；非法 `venueType` 返回 `400 INVALID_VENUE_TYPE`。响应使用独立 `ActivityNearbyVO`，字段与 3.3.1 冻结契约一致，不返回 total。

每个目标 GEO Key 独立使用 `min(page × pageSize × 5, 200)` 候选窗口。服务按 `venueId` 合并候选，并仅采用 Venue 在 MySQL 中实际类型所对应 GEO Key 的距离；随后一次批量装载 Venue、一次批量查询未来有效 ACTIVITY Showtime、一次批量装载 Activity、一次批量装载 Hall。Venue 必须启用、城市与请求一致、类型与 GEO 来源一致且经纬度合法；未来有效场次必须满足 `biz_type=ACTIVITY`、`status=1`、`start_time>NOW()`，同时校验时间、座位数量、余票范围和 Hall/Venue 关系。物理缺失的 Activity 或损坏的场次关联失败关闭为 `INTERNAL_ERROR`，下线 Activity 与不匹配的 Activity type 被过滤，`available_seats=0` 仍可展示。

同一 Activity 的候选按 `distance ASC, venueId ASC, startTime ASC, showtimeId ASC` 选出最近场馆及该场馆下一场，再按 `distance ASC, nextStartTime ASC, activityId ASC` 对最终 Activity 列表稳定排序，完成全部业务过滤后按 `page/pageSize` 截取。实现不存在按 Venue、Activity、Hall 或 Showtime 循环查询的 Mapper 调用；普通 Activity 列表、指定 Activity 场馆查询、MOVIE Nearby 和阶段 1～10 行为保持不变。

冻结验证包含 DTO/Controller/Service 以及真实 MySQL、兼容 GEO 的 Redis、随机端口 HTTP：覆盖单类型与四类型召回、跨类型合并、同一 Activity 跨场馆/多场次去重、最近场馆优先、该场馆下一场、Activity type 过滤、过滤后分页、售罄展示、非法 VenueType、禁止 N+1 和请求前后 MySQL/GEO 快照不变。Step 11.3 定向 19 项测试通过；全量 420 项测试通过，0 failure、0 error、0 skipped，Maven `clean package` 为 `BUILD SUCCESS`。

Step 11.3 不修改 `sql/movielife.sql`，不新增 Redis Key、缓存或写状态，不触碰锁座、订单、ShowtimeSeat 和 `available_seats`。Step 11.4/11.5、非固定座位票务、Activity 收藏、Venue/Activity Review、Activity 管理端、AI、前端和 GEO 自动同步均未提前实现。

##### 3.3.7 Step 11.4：边界、异常与分页加固冻结

Step 11.4 不改变 3.3.1～3.3.6 的 URL、Method、登录要求、DTO、VO、错误码或排序规则，只为既有 `GET /api/activities/nearby` 补齐边界证据。GEO Key 不存在继续返回 `[]`；Redis GEO 调用失败、无法解析的 member 或缺失距离失败关闭为 `INTERNAL_ERROR`，不降级为 MySQL 经纬度扫描。GEO 中物理缺失、停用、跨城市或实际 VenueType 与来源 Key 不一致的 Venue 被忽略。

MySQL 资格仍由既有批量查询和内存关联共同决定：已结束、停售、非 `ACTIVITY` 的 Showtime 不参与发现；下线 Activity 被过滤；Hall 缺失或不属于 Showtime.Venue 时返回 `INTERNAL_ERROR`。同一 Activity 跨多个 Venue、Showtime 或 VenueType 仍只返回一次；等距时严格使用 `venueId`、`nextStartTime`、`showtimeId` 和 `activityId` 既定次序消除 Redis/MySQL 返回顺序差异。`available_seats=0` 不影响发现资格。

分页继续在全部业务过滤和 Activity 去重后执行。每个目标 VenueType 的候选窗口严格为 `min(page × pageSize × 5, 200)`；请求页起点达到或超过本次最多 200 条有界候选形成的结果时返回 `[]`，不为深页扩大窗口、不返回伪造 total，也不执行无界数据库补偿查询。

新增 8 项自动化测试，并强化既有随机端口 HTTP 联调的只读快照。真实 MySQL/Redis 验证覆盖空 GEO、历史 member、停用 Venue、下线 Activity、过期/停售/错误业务类型场次、Hall/Venue 错配、跨类型与多场馆/多场次去重、等距稳定排序、过滤后分页、候选上限和售罄展示；查询前后 Activity、Venue、Hall、Showtime、ShowtimeSeat、TicketOrder、TicketOrderItem、GEO member、`seat:lock:*` 与 `seat:lock:token:*` 均不变。原 MOVIE Nearby 专项 61 项通过；全量 428 项测试通过，0 failure、0 error、0 skipped，Maven `clean package` 为 `BUILD SUCCESS`。

Step 11.4 至此冻结。本 Step 未修改生产 Java、`sql/movielife.sql`、Redis Key 或阶段 1～10 状态机，不实现 Step 11.5、非固定座位票务、Activity 收藏、Venue/Activity Review、Activity 管理端、AI、前端或 GEO 自动同步。

##### 3.3.8 Step 11.5：完整主链路与阶段冻结

Step 11.5 不新增接口或业务实现，而是使用真实 MySQL、兼容 GEO 的 Redis 与随机端口 HTTP，将阶段 11 和已冻结的阶段 10 串成一条可重复验收链路：`Activity Nearby → Activity Detail → Activity Showtimes → Showtime Detail → Seat Map → Redis 临时锁座 → 创建订单 → 相同 lockToken 幂等重试 → 支付 → 出票 → 场馆核销`。Nearby 返回的 Activity、最近 Venue 和下一 Showtime 必须与后续详情及场次接口保持一致。

从 Nearby 到 Seat Map 的只读区间，请求前后逐项比较 Activity、Venue、Hall、Showtime、ShowtimeSeat、TicketOrder、TicketOrderItem、Venue GEO、`seat:lock:*` 和 `seat:lock:token:*`，确认没有 MySQL 或 Redis 写入。进入锁座后，`TEMP_LOCKED` 仍只存在于订单创建之前；新订单提交后形成唯一 TicketOrder、两条完整 TicketOrderItem、全部 ShowtimeSeat `AVAILABLE → HELD_BY_UNPAID_ORDER` 并归属该订单，`available_seats` 从 2 精确扣减到 0，随后按 token 条件清理 Redis 临时锁。

相同用户、Showtime 和规范化 seatIds 使用已经消费的 lockToken 重试时，即使 Redis Key 已不存在也返回原 orderId；MySQL 不增加订单或明细。后续支付、出票和核销继续复用阶段 10 状态机：订单 `UNPAID → PAID`，ShowtimeSeat `HELD → SOLD`，票从 PENDING 出票为 VALID，目标票首次核销为 USED 并保留证据；这些操作不创建新的票务 Redis 状态，也不改变已扣减的 `available_seats`。

Activity Nearby 与完整 Activity 票务定向 56 项测试通过；全量 428 项自动化测试通过，0 failure、0 error、0 skipped，Maven `clean package` 为 `BUILD SUCCESS`。联调数据和 GEO member 均在测试后精确清理，未修改 `sql/movielife.sql`、生产 Java、Redis Key 或阶段 1～10 已冻结契约。

阶段 11 至此正式冻结。后续不得在阶段 11 中继续加入非固定座位活动票务、Activity 收藏、Venue/Activity Review、Activity 管理端、AI、前端或 GEO 自动同步；任何新能力必须另行明确独立阶段并重新按设计、实现、测试、真实联调、构建、提交和冻结推进。

### 3.4 社区动态

```bash
POST   /api/posts                        # 发布动态
GET    /api/posts                        # 动态列表/发现流
GET    /api/posts/{id}                   # 动态详情
PUT    /api/posts/{id}                   # 修改本人动态
DELETE /api/posts/{id}                   # 删除本人动态
PUT    /api/posts/{id}/like              # 确保当前用户已点赞
DELETE /api/posts/{id}/like              # 确保当前用户未点赞
GET    /api/posts/{id}/comments          # 动态评论
POST   /api/posts/{id}/comments          # 发表评论
DELETE /api/comments/{id}                # 删除本人评论
GET    /api/feed/following               # 关注用户动态流
```

### 3.5 评价与互动

```bash
POST   /api/reviews                      # 创建评价
PUT    /api/reviews/{id}                 # 修改本人评价
DELETE /api/reviews/{id}                 # 删除本人评价

GET    /api/reviews                      # 按目标对象查询评价
GET    /api/reviews/{id}                 # 评价详情
PUT    /api/reviews/{id}/like            # 确保当前用户已点赞
DELETE /api/reviews/{id}/like            # 确保当前用户未点赞
GET    /api/reviews/{id}/comments        # 评价评论
POST   /api/reviews/{id}/comments        # 发表评论
```

评价目标统一使用 `targetType + targetId`。阶段 3 已实现 `MOVIE`；阶段 13 Step 13.1 只冻结 `ACTIVITY` 的兼容扩展契约，尚未修改生产接口。Activity Review 后续实现继续复用 `/api/reviews`，不新增 `/api/activities/{id}/reviews` 嵌套入口，不复制 Activity 专用 Controller、Service、DTO 或 VO。同一用户对同一目标最多保留一条有效评价；Activity 第一版不校验购票、支付、出票或核销记录。

### 3.6 收藏

```bash
PUT    /api/collections/{targetType}/{targetId}    # 确保已收藏
DELETE /api/collections/{targetType}/{targetId}    # 确保未收藏
GET    /api/collections                            # 当前用户收藏列表
GET    /api/collections/check                      # 查询指定目标是否已收藏
       ?targetType=MOVIE
       &targetId=1001
```

收藏统一使用 `targetType + targetId`。阶段 4 已支持 `MOVIE`、`POST`、`REVIEW`；阶段 12 Step 12.1 在既有通用接口上增加 `ACTIVITY`，不新增 Activity 专用收藏路径。创建和删除接口均为幂等操作，数据库唯一索引作为并发兜底。

电影详情和活动详情的 VO 优先直接返回当前用户的附加状态，例如 `collected`、`liked` 等，减少详情页额外请求。`GET /api/collections/check` 保留为通用兜底接口，适用于批量预检查、非详情页场景或新目标类型接入。

#### 3.6.1 Step 12.1 Activity 收藏写路径与状态检查

1. URL：`PUT /api/collections/ACTIVITY/{activityId}`。Method：`PUT`。是否登录：是。Path 参数 `activityId` 必须为正整数；无 Request DTO，成功响应 `data=null`。Service 将 `targetType` 去除首尾空白并转为大写，严格允许 `MOVIE/POST/REVIEW/ACTIVITY`；Activity 必须物理存在且 `status=1`，不存在、草稿或下线统一返回 `404 COLLECTION_TARGET_NOT_FOUND`。相同用户重复收藏幂等成功；并发请求由 `uk_collection_user_target(user_id, target_type, target_id)` 收敛，命中重复键视为另一个等价 PUT 已完成，不重复创建关系。
2. URL：`DELETE /api/collections/ACTIVITY/{activityId}`。Method：`DELETE`。是否登录：是。Path 参数 `activityId` 必须为正整数；无 Request DTO，成功响应 `data=null`。取消收藏只按当前用户、规范化目标类型和目标 ID 删除关系，不要求 Activity 当前仍存在或在线；无关系、重复取消、目标后来下线或删除均幂等成功。
3. URL：`GET /api/collections/check?targetType=ACTIVITY&targetId={activityId}`。Method：`GET`。是否登录：是。Query 参数 `targetType` 必填，`targetId` 必须为正整数；Request DTO 沿用 `CollectionCheckDTO`，Response VO 沿用 `CollectionCheckVO { collected }`。该接口只检查当前用户的 MySQL 收藏关系，不校验 Activity 当前可见性；无关系返回 `collected=false`。
4. 业务错误码：未登录返回 `401 UNAUTHORIZED`；目标 ID 非正数返回 `400 INVALID_ID`；未知收藏类型返回 `400 INVALID_COLLECTION_TARGET_TYPE`；新增收藏时 Activity 不存在、草稿或下线返回 `404 COLLECTION_TARGET_NOT_FOUND`。
5. 数据库 / Redis 行为：收藏关系仍只写 `tb_collection`，不修改 `sql/movielife.sql`，不新增 Redis Key 或缓存，不写 Activity、Showtime、ShowtimeSeat、TicketOrder、TicketOrderItem、`available_seats` 或 Venue GEO。Step 12.1 不实现 `GET /api/collections?targetType=ACTIVITY`，不向 Activity 列表、详情或 Nearby VO 增加 `collected`。

Step 12.1 已完成并冻结：8 项 Collection Service 单元测试与 5 项真实 MySQL/Redis/随机端口 HTTP 测试通过，覆盖正常、重复、并发收藏，重复取消，关系检查，用户隔离，未登录、非法 ID、非法类型，以及 Activity 不存在、草稿、下线和下线后仍可检查、取消。定向共 13 项通过；全量 439 项测试通过，0 failure、0 error、0 skipped；Maven `clean package` 为 `BUILD SUCCESS`。真实联调确认并发收藏最终只有一条 MySQL 关系，除预期 `tb_collection` 变化外，Activity、Showtime、ShowtimeSeat、TicketOrder、TicketOrderItem、Venue GEO、`seat:lock:*` 和 `seat:lock:token:*` 均保持不变。

#### 3.6.2 Step 12.2 Activity 收藏列表

1. URL：`GET /api/collections?targetType=ACTIVITY&page={page}&pageSize={pageSize}`。Method：`GET`。是否登录：是。Query 参数沿用 `CollectionQueryDTO`：`targetType` 必填并按既有规则规范化，`page` 默认 1 且必须大于等于 1，`pageSize` 默认 20 且范围为 1～100。Response 沿用 `PageVO<CollectionVO>`，每项为 `CollectionVO { targetType, targetId, target, collectTime }`，其中 `target` 使用既有 `ActivityListItemVO { id, title, coverUrl, type, city, priceMin, priceMax }`。
2. 只返回当前登录用户 `target_type=ACTIVITY` 且关联 `tb_activity.status=1` 的收藏。Activity 物理缺失、草稿或下线时不展示，但不删除、修改或修复原 `tb_collection` 关系。`total` 是完成有效 Activity 约束后的可展示关系总数，不是原始收藏关系数。
3. 分页在 MySQL 有效目标约束之后执行，按 `tb_collection.create_time DESC, tb_collection.id DESC` 稳定排序；因此失效关系不会制造空洞页，相同收藏时间由收藏记录 ID 确定顺序。Service 对当前页 Activity ID 只执行一次批量读取，并按收藏关系页顺序组装，禁止逐条查询。
4. 业务错误码：未登录返回 `401 UNAUTHORIZED`；未知收藏类型返回 `400 INVALID_COLLECTION_TARGET_TYPE`；非法分页参数沿用 `400 INVALID_PARAM`。无有效 Activity 收藏时返回 `list=[]`、`total=0`。
5. 数据库 / Redis 行为：接口只读 MySQL，不清理 `tb_collection`，不写 Activity、Showtime、ShowtimeSeat、TicketOrder、TicketOrderItem 或 `available_seats`，不读取或写入 Venue GEO、`seat:lock:*`、`seat:lock:token:*`，也不新增 Redis Key、缓存、表、字段或索引。MOVIE/POST/REVIEW 收藏列表继续走阶段 4 原有路径；本 Step 不向 Activity 列表、详情或 Nearby VO 增加 `collected`。

Step 12.2 已完成并冻结：9 项 Collection Service 单元测试与 6 项真实 MySQL/Redis/随机端口 HTTP 测试通过，定向共 15 项，覆盖有效目标约束分页、相同时间下 ID 倒序、第二页、失效目标过滤、过滤后 `total`、用户隔离、登录校验、一次批量 Activity 读取、零关系清理和其他收藏类型路径隔离。全量 441 项测试通过，0 failure、0 error、0 skipped；Maven `clean package` 为 `BUILD SUCCESS`。真实联调确认读取前后全部测试收藏关系、Activity 和 Redis Key 集合不变。

#### 3.6.3 Step 12.3 Activity 普通列表和详情补充 collected

1. URL：`GET /api/activities` 与 `GET /api/activities/{id}`。Method：`GET`。是否登录：否；可选 Bearer 登录态只用于补充用户私有状态。两个既有响应 VO 兼容新增 `collected`，不删除或重命名原字段。
2. 匿名请求返回 `collected=false` 且不查询 `tb_collection`。登录列表在 Activity 原分页完成后，按当前用户、`target_type=ACTIVITY` 和本页全部 Activity ID 执行一次 `IN` 查询；登录详情在有效 Activity 查出后最多执行一次对应关系计数。不同用户的关系严格隔离。
3. `collected` 只属于响应组装，不进入 Activity 查询条件、排序、分页或公共缓存。Activity 不存在、草稿、下线、价格范围、筛选和稳定排序继续遵循阶段 10 契约。Activity 收藏列表因复用 `ActivityListItemVO`，其已成立关系对应摘要返回 `collected=true`，不增加额外数据库查询。
4. 数据库 / Redis 行为：只读 `tb_collection`，不新增、删除或更新收藏关系；不修改 Activity、Showtime、ShowtimeSeat、TicketOrder、TicketOrderItem、`available_seats` 或 GEO，不新增 Redis Key、缓存、表、字段或索引。MOVIE 收藏查询实现及 Nearby 接口保持不变。

Step 12.3 已完成并冻结：Activity Service 14 项、Activity Controller 8 项、真实 MySQL/Redis/随机端口 Activity Collection HTTP 7 项、Collection Service 9 项及 Movie Service 4 项定向测试全部通过，共 42 项；覆盖匿名 false、登录收藏前 false/收藏后 true、列表与详情、用户隔离、列表单次批量关系查询、详情单次关系查询、共享收藏摘要状态及 MOVIE 回归。全量 445 项测试通过，0 failure、0 error、0 skipped；Maven `clean package` 为 `BUILD SUCCESS`。

#### 3.6.4 Step 12.4 Activity Nearby 补充 collected

1. URL：`GET /api/activities/nearby`。Method：`GET`。是否登录：否；可选 Bearer 登录态仅用于补充当前用户收藏状态。`ActivityNearbyVO` 在原字段末尾兼容增加 `collected: Boolean`。
2. 处理顺序固定为：Venue GEO 候选 → MySQL 有效性过滤 → Activity 去重 → 最近 Venue 选择 → 下一场次选择 → 稳定排序 → 过滤后分页 → 最终页 Activity ID 批量查询收藏关系 → 填充 `collected`。收藏状态绝不参与前七步。
3. 匿名请求统一填充 `false`，不访问 `tb_collection`；登录请求只对最终一页 ID 执行一次 `user_id + target_type=ACTIVITY + target_id IN (...)` 查询，不逐条查询。空结果不查询收藏表。
4. 数据库 / Redis 行为：只读既有 Venue GEO 和 MySQL，不新增、删除、刷新或修复 GEO，不写 `tb_collection`、Activity、Venue、Hall、Showtime、ShowtimeSeat、TicketOrder、TicketOrderItem 或 `available_seats`，不新增 Redis Key、缓存、表、字段或索引。候选窗口仍为 `min(page × pageSize × 5, 200)`。

Step 12.4 已完成并冻结：阶段 11 DTO、Controller、Service、边界、真实 MySQL/Redis/随机端口 HTTP 与 Activity 主链路扩展定向回归 81 项通过，覆盖返回集合、顺序、过滤后分页、最近 Venue、下一 Showtime、售罄发现、候选窗口、无 N+1 和业务快照不变；新增登录单元测试证明收藏关系只在最终页批量查询一次，真实 HTTP 证明匿名、收藏用户和其他用户除 `collected` 外得到完全相同的 Activity/场馆/场次上下文。全量 446 项测试通过，0 failure、0 error、0 skipped；Maven `clean package` 为 `BUILD SUCCESS`。

#### 3.6.5 Step 12.5 边界加固与阶段冻结

1. 本 Step 不增加或修改生产接口、DTO、VO、Mapper、Service、数据库结构和 Redis 结构，只补充 Activity 收藏跨接口、并发、边界、回归及数据快照证据。
2. 真实 MySQL、Redis 和随机端口 HTTP 主链路固定验证：列表、详情、Nearby 初始 `collected=false` → 收藏及重复收藏 → 三类查询均为 `true`、收藏列表出现、关系检查为 `true` → 取消及重复取消 → 三类查询均恢复 `false`、收藏列表消失、关系检查为 `false`。关系变化期间 MySQL 最多存在一条当前用户 Activity 收藏记录。
3. 两个用户收藏同一 Activity 时分别形成自己的唯一关系；同一用户并发收藏收敛为一条关系；收藏与取消并发允许按数据库实际执行顺序最终为未收藏或已收藏，但关系检查和收藏列表必须与最终 MySQL 事实一致。
4. 下线、草稿和不存在 Activity 继续统一拒绝新收藏；既有收藏在目标下线后不进入收藏列表，但仍可检查关系并取消。Activity 收藏列表继续在分页前过滤有效目标，并按 `create_time DESC, id DESC` 稳定排序；深分页返回空列表但保留准确 `total`。
5. Activity 普通列表、详情和 Nearby 的无 N+1 约束保持不变；MOVIE、POST、REVIEW 收藏继续沿用阶段 4 的收藏、检查、列表和取消路径。本 Step 不新增任何收藏 Redis Key。
6. 数据库 / Redis 行为：真实主链路只允许 `tb_collection` 出现预期的临时 Activity 关系变化，结束后恢复基线；`tb_activity`、`tb_showtime`、`tb_showtime_seat`、`tb_ticket_order`、`tb_ticket_order_item`、`available_seats`、Venue GEO member、`seat:lock:*`、`seat:lock:token:*` 和 Redis Key 集合保持不变。

Step 12.5 已完成并冻结：阶段 12 与阶段 11 交叉定向测试 103 项通过；全量 452 项测试通过，0 failure、0 error、0 skipped；真实 MySQL、Redis、随机端口 HTTP 联调及前后快照验证通过，Maven `clean package` 为 `BUILD SUCCESS`。阶段 12 不再接受功能扩展，后续能力必须作为新的独立阶段设计。

### 3.7 场馆

```bash
GET /api/venues                         # 场馆列表和筛选
GET /api/venues/{id}                    # 场馆详情
GET /api/venues/nearby                  # 附近场馆
    ?city=上海
    &type=CINEMA
    &bizType=MOVIE
    &bizId=1001
    &x=经度
    &y=纬度
    &distance=距离米数
GET /api/venues/{id}/halls               # 场馆影厅/场厅列表
GET /api/venues/{venueId}/showtimes       # 场馆下指定电影的可售场次
    ?movieId=1
    &date=2026-09-03
GET /api/venues/{id}/reviews             # 场馆评价
```

`GET /api/venues/nearby` 是公开接口，支持 `city`、`type`、`x`、`y`、`distance`、`page`、`pageSize`，其中 `x` 为经度、`y` 为纬度，`distance` 和响应中的距离单位均为米。经度范围为 `-180～180`，纬度范围为 `-90～90`，查询距离范围为 `100～50000` 米。

普通模式只传场馆和坐标条件：服务端查询 `venue:geo:{city}:{TYPE}`，按距离升序召回 `venueId + distance`，再从 MySQL 批量补齐启用的场馆信息，并按 GEO 顺序返回。GEO Key 不存在时返回空列表；Redis GEO 查询异常时返回 `INTERNAL_ERROR`，当前不使用 MySQL 经纬度计算降级。

电影过滤模式额外传入 `bizType=MOVIE&bizId={movieId}`。`bizType` 和 `bizId` 必须同时存在或同时为空，当前仅支持 `MOVIE`，其他类型返回 `INVALID_BIZ_TYPE`；电影不存在返回 `MOVIE_NOT_FOUND`。服务端先进行 GEO 候选召回，再通过 `tb_showtime` 的 `biz_type=MOVIE`、`biz_id`、`start_time > NOW()`、`status=1` 条件筛选存在未来可售场次的场馆，过滤后分页，最终保持 GEO 距离顺序。同一场馆即使存在多个有效场次也只返回一次。

`GET /api/venues/{venueId}/showtimes` 是公开接口，当前只查询电影场次。`movieId` 和 `date` 均为必填参数，日期格式固定为 `yyyy-MM-dd`。场馆不存在或已停用返回 `VENUE_NOT_FOUND`，电影不存在返回 `MOVIE_NOT_FOUND`，ID 或日期参数不合法返回相应的 400 错误；当天没有可售场次时正常返回空列表。

有效场次必须同时满足 `venue_id`、`biz_type=MOVIE`、`biz_id=movieId`、`status=1`、`start_time >= dateStart`、`start_time < nextDateStart` 和 `start_time > NOW()`，并按 `start_time ASC` 返回。响应项包含 `id`、`hallId`、`hallName`、`startTime`、`endTime`、`basePrice`；其中 `basePrice` 是场次基础价，座位差异价留待座位查询阶段处理。影厅信息通过一次批量查询补齐，本阶段不缓存场次组合列表。

### 3.8 场次、座位与锁座

```bash
GET /api/showtimes/{id}                  # 场次详情
GET /api/showtimes/{id}/seats            # 场次座位状态
POST /api/showtimes/{id}/seats/lock      # 仅锁定座位，不创建订单
POST /api/showtimes/{id}/seats/release   # 主动释放未转订单的锁座
```

`GET /api/showtimes/{id}` 是公开的场次详情接口。`id` 必须为正整数；场次不存在、已停用、已过期或不是 `MOVIE` 类型时，统一返回 `404 SHOWTIME_NOT_FOUND`，不对外暴露内部状态差异。关联场馆已停用时同样视为场次不可公开；Venue、Hall、Movie 缺失或 Hall 不属于该场馆属于内部数据不一致，返回统一服务异常。

接口固定读取一条 Showtime，并分别查询关联的 Venue、Hall、Movie，不使用复杂 JOIN，也不产生列表 N+1。响应 `ShowtimeDetailVO` 包含 `id`、`venueId`、`venueName`、`hallId`、`hallName`、`movieId`、`movieName`、`startTime`、`endTime`、`basePrice`。详情具有时间敏感性，当前直接读取 MySQL，不增加 Redis 缓存。

`GET /api/showtimes/{id}/seats` 是公开的场次座位图查询接口，复用场次详情的有效性规则，允许未来、启用、关联场馆有效的 `MOVIE` 或固定座位 `ACTIVITY` 场次。接口只执行查询，不创建座位状态、不锁座，也不操作 Redis。

响应包含 `showtimeId`、`hallId`、`hallName`、`basePrice` 和扁平 `seats` 列表。每个座位返回 `seatId`、`rowNo`、`colNo`、`seatNo`、`seatType`、`status`、`price`；其中 `status` 来自 `tb_showtime_seat`，当前数值语义为 `0=AVAILABLE`、`1=HELD_BY_UNPAID_ORDER`、`2=SOLD`，`price` 为该场次下的座位价格。`tb_seat.base_status=0` 的物理座位不返回。

座位按 `row_no ASC, col_no ASC` 稳定排序。服务端先批量查询 Hall 的有效 `tb_seat`，再一次查询对应 `tb_showtime_seat` 并通过 `seatId` 映射；Hall 没有有效座位时返回 `seats: []`。只要存在有效 Seat 却缺少对应 ShowtimeSeat，就按内部数据不完整返回 `INTERNAL_ERROR`，不会把缺失状态默认为可售。当前座位图直接读取 MySQL，不缓存。

阶段 6 的只读查询链路已经冻结为：`Movie Detail → Nearby Venue → Venue Movie Showtimes → Showtime Detail → Showtime Seat Map`。三个场次相关接口统一采用 `biz_type=MOVIE`、`status=1`、`start_time > NOW()` 的有效场次定义；Venue Showtimes 无匹配数据返回 `[]`，有效 Hall 没有启用物理座位时返回 `seats: []`。Showtime 和 Seat Map 仍不缓存，Seat Map 当前只展示 MySQL 状态，不合并 Redis `TEMP_LOCKED`；这是阶段 7 完成后的既定行为，不代表锁座遗漏。

锁座请求：

```json
{
  "seatIds": [101, 102]
}
```

锁座成功返回：

```json
{
  "code": "OK",
  "message": "ok",
  "data": {
    "lockToken": "xxx",
    "expireTime": "2026-09-03T14:30:00"
  },
  "requestId": "..."
}
```

该接口要求登录。`seatIds` 必须包含 1～6 个正整数且不允许重复，服务端按 Long 升序规范化。Seat 与 ShowtimeSeat 均采用批量查询完成完整性和可用性校验；任意一个座位不满足条件时整次请求失败。多座位锁由单次 Lua 原子执行，全部成功或全部失败，不允许遗留部分锁。

`SeatLockService` 只负责在 Redis 中原子校验并占用 `showtimeId + seatIds`，锁记录绑定当前用户并使用 5 分钟 TTL。当前用户对同一 Showtime、同一规范化座位集合的有效锁重复提交时，直接返回原 `lockToken + expireTime`，不重新写 Redis、不刷新 TTL。Redis 临时锁冲突返回 `409 SEAT_ALREADY_LOCKED`；MySQL 中任一 ShowtimeSeat 已被待支付订单占用或售出时返回 `409 SEAT_NOT_AVAILABLE`。

释放锁座请求体固定为：

```json
{
  "lockToken": "xxx"
}
```

释放接口要求登录且请求体只接受 `lockToken`，不接受 `seatIds`。服务端必须从 token 元数据读取用户、场次和完整座位集合，只允许锁持有者释放；非持有者返回 `403 LOCK_NOT_OWNED`，URL showtimeId 与 token 元数据不匹配时返回 `400 INVALID_LOCK_TOKEN`。释放 Lua 只删除 `seatKey.value == lockToken` 的键，不能误删后来产生的新锁。

Token 已过期、已释放或重复 release 时均按幂等成功处理，不返回 404。普通 release 只清理 Redis 临时状态，不修改 `tb_showtime_seat.status`，也不承担订单取消职责。

核心规则：座位状态必须绑定 `showtimeId`，不能只锁定影厅物理座位；任意一个座位不可用时整次锁座失败；锁过期由 Redis TTL 自动释放。阶段 7 的 lock/release 均不修改 `tb_showtime_seat.status`。当前 Seat Map 仍直接读取 MySQL，锁座期间可能继续展示 `AVAILABLE`；后续创建订单和支付时必须再次校验服务端锁与数据库状态。

### 3.9 订单与支付

```bash
POST /api/orders                       # 创建订单（单场次、多座位）
GET  /api/orders                       # 当前用户订单列表
GET  /api/orders/{id}                  # 订单详情
POST /api/orders/{id}/pay              # 发起支付
POST /api/orders/{id}/issue            # PAID 订单出票（已实现，Step 8.11）
POST /api/orders/{id}/cancel           # 取消未支付订单
POST /api/orders/{id}/refund           # 整单退款（已实现，Step 8.12）
GET  /api/orders/{id}/tickets           # 查看票券/取票码（预留，尚未开放）
```

创建订单请求：

```json
{
  "showtimeId": 1001,
  "seatIds": [101, 102],
  "lockToken": "xxx"
}
```

创建订单成功响应中的 `CreateTicketOrderVO` 固定包含 `orderId`、`status`、`totalAmount`、`expireTime`，不包含属于票明细或支付结果的 `ticketCode`、`ticketStatus`、`payTime`。`seatIds` 必须为 1 到 6 个互不重复的正整数，服务端去重、排序后必须与原数量一致；`lockToken` 非空且最长 64 位。

`TicketOrderService` 首次创建订单前必须校验 `lockToken` 的用户归属、场次、规范化座位集合、有效期以及全部 seatKey 的完整归属。客户端提交的 `seatIds` 只用于一致性校验，实际下单座位以 Redis token 元数据为准；价格由服务端读取 `ShowtimeSeat.price` 计算，不能信任客户端价格。

`Redis Metadata` 只表示尚未消费阶段的临时下单资格；`tb_ticket_order.lock_token` 是消费成功后的长期幂等事实，并具有唯一约束。请求进入后必须先按 `lockToken` 查询订单：已有订单且用户、场次和订单明细座位集合完全一致时直接返回原订单，即使 Redis Key 已删除也不得创建第二个订单；只有 token 尚未消费时才校验 Redis 和 MySQL 并首次创建订单。

订单创建必须在一个受控 MySQL 事务中完成：创建 `UNPAID` 订单、批量创建座位明细、将全部目标 `ShowtimeSeat` 按 `status=AVAILABLE AND lock_order_id IS NULL` 条件更新为 `HELD_BY_UNPAID_ORDER` 并绑定当前订单，同时同步减少 `Showtime.available_seats`。座位更新数量必须等于 `seatIds` 数量，余票计数更新影响行数必须为 1；否则整个事务回滚。`available_seats` 只是派生计数，更新失败按内部数据一致性异常处理，不能替代 `ShowtimeSeat` 的可售判断。

Redis 5 分钟 `lockExpireTime` 只决定能否首次创建订单。订单成功后使用独立的 15 分钟支付窗口，`orderExpireTime = min(创建基准时间 + 15 分钟, Showtime.startTime)`，并且必须严格晚于订单创建基准时间；否则首次创建失败，不生成创建即过期的 `UNPAID` 订单。

数据库事务提交前禁止删除 Redis 锁。提交成功后才按 `seatKey.value == lockToken` 条件清理 seatKey 和 token 元数据；清理失败不能回滚已经成立的订单事实，只能重试清理或等待原 TTL 收敛。订单超时或取消后，由订单域按 `status=HELD_BY_UNPAID_ORDER AND lock_order_id=当前订单` 的归属条件释放座位，普通锁座 release 不承担该职责。

若并发创建命中 `uk_order_lock_token`，当前失败事务必须先回滚，再在事务外或新事务中按 `lockToken` 查询既有订单，校验 `userId`、`showtimeId` 和规范化 `seatIds` 后返回原订单；不得在已经被标记为 rollback-only 的事务中继续查询并返回。

当前实现进度截至阶段 8 Step 8.12：`POST /api/orders` 已正式开放，要求登录并统一返回 HTTP 200 的 `Result<CreateTicketOrderVO>`；`userId` 只从服务端登录上下文获取，请求体不能提交用户、金额、状态、订单 ID 或过期时间。上述 MySQL 创建事务、唯一 token 冲突后的事务外幂等恢复，以及 COMMIT 后复用阶段 7 Lua 的 Redis 条件清理已经完整接入 HTTP 链路；订单 ID 沿用数据库自增策略。清理最多同步尝试两次，失败只记录已提交订单的上下文并继续返回成功；同 token HTTP 重试在 Redis Key 已删除后仍根据 MySQL 返回原订单，缺失 Key 按成功处理，其他 token 拥有的 seatKey 不会被删除。

创建订单当前可能返回的业务错误包括：`400 INVALID_PARAM`、`400 INVALID_LOCK_TOKEN`、`403 LOCK_NOT_OWNED`、`403 ORDER_NOT_OWNED`、`404 SHOWTIME_NOT_FOUND`、`409 SEAT_NOT_AVAILABLE`、`409 ORDER_REQUEST_MISMATCH` 以及不暴露内部数据细节的 `500 INTERNAL_ERROR`。首次创建与幂等重试均返回 HTTP 200 和相同响应结构。

已消费 token 对应订单的 `userId` 不属于当前用户时返回 `403 ORDER_NOT_OWNED`；`showtimeId` 或座位集合与原订单不一致时返回 `409 ORDER_REQUEST_MISMATCH`。未消费 token 的 Redis Metadata 不存在、过期、场次或座位集合不匹配，以及任一 seatKey 缺失或不再等于当前 token 时，统一返回 `400 INVALID_LOCK_TOKEN`；Metadata 用户不一致继续复用 `403 LOCK_NOT_OWNED`。

`GET /api/orders` 已开放为当前登录用户订单列表。Query 参数为 `page`（默认 1，必须大于等于 1）和 `pageSize`（默认 20，范围 1～100），暂不支持状态筛选。响应 `data` 沿用统一分页结构 `list/total/page/pageSize`；列表项包含 `orderId`、`showtimeId`、`bizType`、电影或活动的兼容 ID/名称字段、场馆和影厅的 ID/名称、`startTime`、原始订单 `status`、`totalAmount`、`expireTime`、`createTime` 和 `seatCount`。无订单时返回 HTTP 200 和空 `list`。

列表固定按 `create_time DESC, id DESC` 排序，只查询 `user_id=当前登录用户` 的订单。当前页订单明细、Showtime、Venue 和 Hall 均按本页 ID 集合批量查询；Showtime 按 `bizType` 分组后分别批量读取 Movie 与 Activity，`seatCount` 由一次批量明细结果按 `orderId` 分组统计，不执行逐订单关联查询。历史 Activity 即使已经下线也按主键返回订单快照上下文，不套用公开活动详情的 `status=1` 条件。

`GET /api/orders/{id}` 已开放为当前用户订单详情。响应除列表上下文字段外，还包含 `endTime`、`payTime` 和按物理座位 `rowNo ASC, colNo ASC` 排序的 `items`；每项包含 `seatId`、`rowNo`、`colNo`、`seatNo`、`seatType`、订单明细价格快照、`ticketCode` 和 `ticketStatus`。详情一次查询全部订单明细，并对全部 seatId 一次批量查询 Seat，不逐明细读取座位。

订单不存在或不属于当前用户均返回 `404 ORDER_NOT_FOUND`，避免泄露其他用户订单是否存在；非法订单 ID 返回 `400 INVALID_ID`。订单存在但 Showtime、对应 Movie/Activity、Venue、Hall、明细或 Seat 关联数据缺失，或者 Showtime 使用未知 `bizType` 时，返回不暴露细节的 `500 INTERNAL_ERROR`。两个查询接口只读取 MySQL，不读取订单 Redis Key，不修改订单状态，也不会根据 `expireTime` 临时修正 `UNPAID` 状态。

`POST /api/orders/{id}/cancel` 已在阶段 8 Step 8.8 正式开放，仅允许当前登录用户主动取消自己的 `UNPAID` 订单。订单不存在或不属于当前用户统一返回 `404 ORDER_NOT_FOUND`；`PAID`、`REFUNDED` 等非 `UNPAID/CANCELED` 状态返回 `409 ORDER_STATUS_CONFLICT`；已经为 `CANCELED` 时按幂等成功返回 HTTP 200，不重复释放座位或增加余票。

取消在独立 MySQL 事务中完成：先以 `SELECT ... FOR UPDATE` 锁定订单行并确认归属与状态，一次查询全部订单明细；随后按 `showtime_id + seatIds + status=HELD_BY_UNPAID_ORDER + lock_order_id=当前订单` 一次批量释放全部座位，恢复 `available_seats`，最后以 `id + user_id + status=UNPAID` 条件更新订单为 `CANCELED`。座位释放数必须等于明细数，余票和订单更新影响行数必须为 1，任一步异常都会整体回滚。余票恢复同时限制不能超过 `total_seats`。

订单行锁与订单状态条件更新共同保证并发重复取消只会执行一次释放；第二个请求在首个事务提交后看到 `CANCELED` 并幂等返回。这也为未来支付事务与取消竞争保留了互斥边界。主动取消只依据数据库真实状态，不因 `expireTime` 已过而拒绝仍为 `UNPAID` 的订单，也不实现自动超时状态修正。取消不读取或写入 `seat:lock:*`，因为释放的是 MySQL 正式订单占用而不是阶段 7 临时锁。

`UNPAID` 订单到达 `expireTime` 后由后台定时任务自动取消，不新增公开 HTTP 接口。任务每分钟限量扫描最多 100 个 `status=UNPAID AND expire_time<=NOW()` 的订单 ID，并按 `expire_time ASC, id ASC` 稳定排序；每个订单在独立事务中重新以 `SELECT ... FOR UPDATE` 锁定并复核状态与到期时间，然后复用主动取消的座位释放和余票恢复逻辑，最终仍写入 `CANCELED`，不新增 `EXPIRED` 状态。

扫描后已经取消、已经支付、尚未到期或已不存在的订单直接跳过。单个订单因明细、座位归属或余票一致性异常而回滚时，只记录该订单失败并继续处理本批其他订单；任务本身不持有整批事务。自动超时完全以 MySQL 为准，不读取或写入 Redis，也不会创建新的 `seat:lock:*`。

`POST /api/orders/{id}/pay` 已开放为当前阶段的模拟支付接口，必须登录且不接收支付参数。订单不存在或不属于当前用户统一返回 `404 ORDER_NOT_FOUND`；只有尚未超过支付截止时间的 `UNPAID` 订单可以支付，`expire_time<=事务内当前时间` 返回 `409 ORDER_EXPIRED`，`CANCELED`、`REFUNDED` 等状态返回 `409 ORDER_STATUS_CONFLICT`。已经 `PAID` 的订单幂等返回首次支付结果，不重复修改座位或 `pay_time`。

支付响应 `PayTicketOrderVO` 包含 `orderId`、`status=PAID` 和 `payTime`。支付事务以 `SELECT ... FOR UPDATE` 锁定订单行，一次读取全部订单明细，再按 `showtime_id + seatIds + status=HELD_BY_UNPAID_ORDER + lock_order_id=当前订单` 批量将座位更新为 `SOLD`、递增版本并保留 `lock_order_id`；座位影响行数必须等于订单明细数，随后以 `id + status=UNPAID + expire_time>payTime` 条件写入 `PAID` 和 `pay_time`。任一步异常均整体回滚。

创建订单时已经扣减 `available_seats`，因此支付时不再修改余票计数。支付不生成 `ticketCode`、不改变 `ticket_status`，也不读取或写入 Redis。订单行锁使双支付、支付与主动取消、支付与超时取消串行竞争，最终只能形成 `PAID + SOLD` 或 `CANCELED + AVAILABLE` 的一致状态。

`POST /api/orders/{id}/issue` 已在 Step 8.11 实现。必须登录，路径 `id` 为正整数，无请求参数。仅本人 PAID 订单可出票；不存在或非本人返回 `404 ORDER_NOT_FOUND`，非 PAID 返回 `409 ORDER_STATUS_CONFLICT`，非法 ID 返回 `400 INVALID_ID`，异常明细或写入失败返回 `500 INTERNAL_ERROR`。

成功返回 HTTP 200，`Result.data` 为 `List<IssueTicketVO>`，每项包含 `seatId`、32 位 `ticketCode` 和 `ticketStatus`。沿用冻结枚举 `PENDING(0) → VALID(1)`，其中 VALID 表示已出票可用；不新增 ISSUED 枚举。每个票码使用去掉连字符的随机 UUID，数据库 `uk_ticket_code` 约束全局唯一。

出票事务先锁订单行、校验全部明细，再通过单条批量条件 UPDATE 为待出票明细写入票码。条件包含当前订单、明细 ID、空票码和 PENDING 状态，影响行数必须等于待出票数量；唯一索引冲突或行数不足均回滚。全部已出票时直接返回原票码；部分已出票时仅补齐其余明细，票码与状态不一致时拒绝处理。出票后详情接口直接展示持久化票码；订单仍为 PAID，座位、lock_order_id、余票和 Redis 均不参与出票更新。

`POST /api/orders/{id}/refund` 已在 Step 8.12 实现。接口必须登录且只允许订单本人操作；订单不存在或非本人统一返回 `404 ORDER_NOT_FOUND`，仅 `PAID` 可退款，`UNPAID`、`CANCELED` 返回 `409 ORDER_STATUS_CONFLICT`，已 `REFUNDED` 则幂等返回 HTTP 200。事务取得订单行锁后必须确认 `Showtime.start_time > now`，否则返回 `409 REFUND_NOT_ALLOWED`。

退款在一个 MySQL 事务内先锁订单，再按明细 ID 顺序锁定全部 TicketOrderItem。任一明细为 `USED(2)` 时，在任何写入前返回 `409 REFUND_NOT_ALLOWED`；否则依次批量释放全部归属座位（`SOLD → AVAILABLE` 且 `lock_order_id` 清空）、恢复不超过 `total_seats` 的 `available_seats`、条件更新订单 `PAID → REFUNDED`，并把全部 PENDING 或 VALID 票明细更新为 `REFUNDED(3)`。所有批量更新均严格要求完整影响行数；异常时整体回滚。`ticketCode`、`pay_time`、`lock_token` 与订单明细历史均保留，退款不会读写 Redis，也不支持部分退款或真实支付渠道退款。

支付回调和退款回调仍预留内部接口：

```bash
POST /api/payment/notify               # 支付渠道异步通知
POST /api/refund/notify                # 退款渠道异步通知
```

客户端不得直接修改订单状态。订单状态为 `UNPAID`、`PAID`、`CANCELED`、`REFUNDED`；当前模拟支付、主动取消、票明细出票、单票核销与整单退款已经实现，真实支付平台和部分退款仍留待后续阶段。

订单明细继续采用“一座一票”：`tb_ticket_order_item.ticket_code` 和 `ticket_status` 属于具体座位明细，`ticket_code` 在支付成功后的出票阶段生成。当前单场次订单通过 `order.showtime_id + item.seat_id` 唯一定位 `ShowtimeSeat`，不额外引入 `showtime_seat_id`。

### 3.10 管理端验票核销

```bash
POST /api/admin/tickets/verify          # 单张票验票核销（已实现，Step 9.4）
```

接口必须登录，并要求当前用户在 `tb_venue_staff` 中与目标 Showtime 所属 Venue 存在 `status=1` 的有效工作人员关系。普通购票用户即使持有本人票码也不能调用。目标 Venue 必须由服务端通过 `ticketCode → TicketOrderItem.order_id → TicketOrder.showtime_id → Showtime.venue_id` 推导；请求体不得提交 `userId`、`venueId`、`orderId`、票状态或核销时间。

请求 DTO 为 `VerifyTicketDTO`：

```json
{
  "ticketCode": "8f2c89e9f65d4c12b749f091b2db36cc"
}
```

`ticketCode` 必填，去除首尾空白后长度为 1～64；不接受空串。当前出票生成 32 位无连字符 UUID，但核销请求不把这一实现长度固化为唯一合法格式，数据库唯一索引 `uk_ticket_code` 仍是票码身份约束。票码放在 JSON 请求体中，不放入 URL、Query 参数或普通访问日志；业务日志只允许记录掩码或不可逆摘要。

成功响应使用 `VerifyTicketVO`，统一包在 `Result<VerifyTicketVO>` 中：

```json
{
  "code": "OK",
  "message": "ok",
  "data": {
    "ticketCode": "8f2c89e9f65d4c12b749f091b2db36cc",
    "ticketStatus": 2,
    "usedTime": "2026-09-10T18:40:00",
    "orderId": 9001,
    "showtimeId": 1001,
    "venueId": 1,
    "hallId": 10,
    "seatId": 101,
    "seatNo": "A01"
  },
  "requestId": "..."
}
```

仅当订单为 `PAID`、票明细为 `VALID(1)`，且当前工作人员对 Showtime 所属 Venue 具有有效关系时，才能将这一张 `TicketOrderItem` 更新为 `USED(2)`，同时写入首次 `used_time` 和 `used_by_user_id`。数据库通过 `chk_order_item_ticket_status` 限制票状态只能为 0～3，并通过 `chk_order_item_used_evidence` 保证只有 USED 同时具有两项核销证据，其他状态的两项证据必须同时为空。多座位订单按票码逐张核销；一次请求只能改变命中的一条明细，不改变同订单其他票。订单继续保持 `PAID`，ShowtimeSeat 继续保持 `SOLD` 并保留 `lock_order_id`，`available_seats` 不变，整个核销链路不读取或写入 Redis。

业务错误码如下：

- `400 INVALID_PARAM`：请求体缺失、票码为空或超过 64 位。
- `401 UNAUTHORIZED`：未登录或登录态失效。
- `403 TICKET_VERIFY_FORBIDDEN`：当前用户不是有效场馆工作人员，或只对其他场馆具有权限。
- `404 TICKET_NOT_FOUND`：票码不存在。
- `409 TICKET_NOT_ISSUED`：票明细仍为 `PENDING(0)`。
- `409 TICKET_ALREADY_USED`：票明细已经为 `USED(2)`；必须保留首次核销事实，不得覆盖 `used_time` 或 `used_by_user_id`，本次请求不算核销成功。
- `409 TICKET_REFUNDED`：票明细已经为 `REFUNDED(3)`。
- `409 ORDER_STATUS_CONFLICT`：关联订单不是 `PAID`。
- `500 INTERNAL_ERROR`：票码、订单、Showtime、Hall、Seat 或核销证据存在不应出现的数据不一致；响应不得暴露内部表结构。

核销事务必须采用与退款一致的锁顺序。服务端可以先按唯一票码做不加锁的定位查询以取得 `orderId`，但正式判定必须在单个 MySQL 事务内先对 TicketOrder 执行 `SELECT ... FOR UPDATE`，再重新读取并锁定目标 TicketOrderItem，随后校验 Showtime、场馆工作人员关系和票状态。最终更新必须包含 `id + order_id + ticket_status=VALID + used_time IS NULL + used_by_user_id IS NULL` 条件，且严格要求 `affectedRows=1`；否则重新读取最终状态并映射为已核销、已退款、订单冲突或内部一致性错误，不能接受部分成功。

退款继续先锁 TicketOrder，再读取订单全部 TicketOrderItem。只要任一明细为 `USED(2)`，整单退款必须在释放座位、恢复余票或修改任何票状态之前返回 `409 REFUND_NOT_ALLOWED`，不能依赖后续批量 UPDATE 行数不足再表现为 `INTERNAL_ERROR`。这样核销与退款并发时由同一订单行锁串行化，最终只能形成“先退款，票全部 REFUNDED”或“先核销，至少一张 USED 且整单不可退款”两种合法结果。

Step 9.1 仅冻结 SQL、API 和事务契约，不创建 Controller、Service、Mapper、DTO、VO 或测试代码，也不开放该接口。本 Step 不包含二维码签名、离线核销、批量核销、核销撤销、核销设备管理、Activity 票务、Redis 防重或额外的核销时间窗规则。

Step 9.2 已完成增量迁移、VenueStaff 与票明细持久化映射，并把退款调整为“先锁订单、再锁全部明细”，在写入前拒绝包含 USED 明细的整单退款。Step 9.3 已完成核销事务服务、权限校验、首次核销证据写入、重复扫描语义及核销与退款并发收敛。Step 9.4 已开放 Controller、DTO 和 VO；票码只从 JSON 请求体读取。阶段 9 最终回归共 345 项自动化测试，覆盖真实 HTTP、MySQL 和 Redis 边界，Maven `clean package` 构建成功。

### 3.11 AI 评价总结

```bash
GET /api/ai/summaries/{targetType}/{targetId} # 读取已有 AI 评价总结
```

阶段 14 将该能力命名为 `Review AI Summary`，第一阶段严格支持 `MOVIE`、`ACTIVITY` 两种评价目标；`VENUE`、`POST`、个性化推荐、内容审核和购票决策均不属于本能力。接口允许匿名读取，不提供公开或管理端的手工生成接口，也不允许客户端提交 Prompt、模型、供应商地址、密钥或任务状态。

响应统一使用 `ReviewAiSummaryVO`，包含 `targetType`、`targetId`、`status`、`summary`、`pros`、`cons`、`suitableFor`、`warningPoints`、`sourceReviewCount`、`inputReviewCount`、`version`、`promptVersion`、`generatedAt` 和 `stale`。该接口只读取 MySQL 中的任务与总结事实并使用 Redis 缓存成功结果，绝不在用户 HTTP 请求链路中同步调用模型。评价发生有效变化后，必须在 Review 事务成功提交后异步触发重新生成；AI 通知、任务、模型或缓存失败不得回滚已经提交的评价。

### 3.12 行为采集与推荐

```bash
POST /api/behavior-events               # 上报浏览、搜索、收藏、购票等行为
GET  /api/recommendations               # 获取当前用户推荐内容
GET  /api/recommendations/movies        # 获取电影推荐
GET  /api/recommendations/activities    # 获取活动推荐
```

推荐理由直接随推荐项返回，不设置独立理由接口：

```json
{
  "targetType": "MOVIE",
  "targetId": 1001,
  "title": "某电影",
  "score": 0.873,
  "reason": "因为你最近浏览了多部悬疑片……"
}
```

行为上报只接收白名单事件类型，不能由客户端直接写入用户标签或推荐分数；推荐结果允许降级为规则排序，AI 仅负责生成可解释理由，不参与库存、价格和订单状态判断。代码命名统一使用 `BehaviorEventDTO`、`BehaviorEventService` 和 `BehaviorEventConsumer`，避免与文娱活动 `Activity` 混淆。

## 4. 第一条业务闭环：详细接口契约

本节只细化以下链路：登录 → 电影列表 → 电影详情 → 评价 → 评价点赞。每个接口固定描述 URL、Method、登录要求、参数、DTO、VO、业务错误码和数据库/Redis 行为。

### 4.1 认证接口

#### 4.1.1 获取验证码

1. URL：`/api/user/code`
2. Method：`POST`
3. 是否登录：否
4. Query / Path 参数：无
5. Request DTO：

```json
{
  "phone": "13800000001"
}
```

6. Response VO：

```json
{
  "code": "OK",
  "message": "ok",
  "data": {
    "expireSeconds": 300
  },
  "requestId": "..."
}
```

7. 业务错误码：`INVALID_PHONE`、`CODE_SEND_TOO_FREQUENT`、`CODE_SEND_FAILED`
8. 数据库 / Redis 行为：校验手机号格式；按 `login:code:{phone}` 写入验证码和 5 分钟 TTL；按 `login:code:limit:{phone}` 限制发送频率；验证码本身不落 MySQL。

#### 4.1.2 验证码登录

1. URL：`/api/user/login`
2. Method：`POST`
3. 是否登录：否
4. Query / Path 参数：无
5. Request DTO：

```json
{
  "phone": "13800000001",
  "code": "123456"
}
```

6. Response VO：

```json
{
  "code": "OK",
  "message": "ok",
  "data": {
    "token": "token-value",
    "expiresIn": 2592000,
    "user": {
      "id": 1,
      "nickName": "观影用户A",
      "icon": null,
      "city": "上海"
    }
  },
  "requestId": "..."
}
```

7. 业务错误码：`INVALID_PHONE`、`CODE_REQUIRED`、`CODE_INVALID`、`CODE_EXPIRED`、`LOGIN_FAILED`
8. 数据库 / Redis 行为：读取 `login:code:{phone}` 并校验；验证码校验成功后通过 Lua 原子完成“比较验证码 + 删除验证码”，保证只能成功消费一次；按手机号查询 `tb_user`，不存在则创建用户；生成随机 token，写入 `login:token:{token}`，值为用户 ID 并设置 TTL；不在响应中返回手机号。V1 也可先使用 `GET → 校验 → DELETE`，但必须标记为后续原子化优化项。

#### 4.1.3 退出登录

1. URL：`/api/user/logout`
2. Method：`POST`
3. 是否登录：是
4. Query / Path 参数：无；从 `Authorization` 读取 token
5. Request DTO：无，请求体为空
6. Response VO：`data: null`
7. 业务错误码：`TOKEN_INVALID`；token 已失效时可按幂等成功处理
8. 数据库 / Redis 行为：删除 `login:token:{token}`；不修改 `tb_user`。

#### 4.1.4 获取当前用户

1. URL：`/api/user/me`
2. Method：`GET`
3. 是否登录：是
4. Query / Path 参数：无
5. Request DTO：无
6. Response VO：

```json
{
  "code": "OK",
  "message": "ok",
  "data": {
    "id": 1,
    "nickName": "观影用户A",
    "icon": null,
    "gender": 1,
    "city": "上海",
    "birthday": null
  },
  "requestId": "..."
}
```

7. 业务错误码：`UNAUTHORIZED`、`TOKEN_INVALID`、`USER_NOT_FOUND`
8. 数据库 / Redis 行为：校验 `login:token:{token}` 获取用户 ID；查询 `tb_user`，可增加 `user:detail:{id}` 缓存；手机号等敏感字段不放入 UserMeVO。

### 4.2 电影接口

#### 4.2.1 电影列表

1. URL：`/api/movies`
2. Method：`GET`
3. 是否登录：否；登录用户可获得个性化 `collected` 状态
4. Query / Path 参数：

```yaml
page: Int = 1
pageSize: Int = 20        # 最大 100
keyword: String?
status: SHOWING|COMING|OFFLINE?
category: String?
region: String?
```

5. Request DTO：无，请求参数封装为 `MovieQueryDTO`
6. Response VO：

```json
{
  "code": "OK",
  "message": "ok",
  "data": {
    "list": [
      {
        "id": 1001,
        "title": "电影名称",
        "coverUrl": "...",
        "category": "悬疑/剧情",
        "releaseDate": "2026-08-20",
        "avgScore": 4.6,
        "scoreCount": 1832,
        "status": "SHOWING",
        "collected": true
      }
    ],
    "total": 1,
    "page": 1,
    "pageSize": 20
  },
  "requestId": "..."
}
```

7. 业务错误码：`INVALID_PAGE`、`PAGE_SIZE_TOO_LARGE`、`INVALID_MOVIE_STATUS`
8. 数据库 / Redis 行为：仅缓存高频固定列表：`movie:hot`、`movie:coming`、`movie:showing:{page}`；普通关键词或多条件查询不生成任意 `queryHash`，直接查询 `tb_movie`；登录用户批量查询 `tb_collection` 或收藏状态缓存，避免逐条查询。电影新增、修改、上下线时失效对应固定列表缓存。

#### 4.2.2 电影详情

1. URL：`/api/movies/{id}`
2. Method：`GET`
3. 是否登录：否；登录用户附加返回 `collected`
4. Query / Path 参数：`id: Long`，必须为正整数
5. Request DTO：无
6. Response VO：

```json
{
  "code": "OK",
  "message": "ok",
  "data": {
    "id": 1001,
    "title": "电影名称",
    "originalTitle": "Movie Title",
    "coverUrl": "...",
    "category": "悬疑/剧情",
    "region": "中国大陆",
    "language": "中文",
    "releaseDate": "2026-08-20",
    "duration": 120,
    "director": "导演",
    "actors": ["演员A", "演员B"],
    "summary": "...",
    "avgScore": 4.6,
    "scoreCount": 1832,
    "status": "SHOWING",
    "collected": true
  },
  "requestId": "..."
}
```

7. 业务错误码：`INVALID_ID`、`MOVIE_NOT_FOUND`
8. 数据库 / Redis 行为：查询 `movie:detail:{id}`；命中直接返回；未命中查询 `tb_movie`，不存在返回 `MOVIE_NOT_FOUND`，存在则写入缓存；`tb_movie.actors` 的逗号分隔字符串由 Service/Converter 拆分为 VO 的字符串数组；当前用户已登录时查询或批量补充 `tb_collection` 状态。详情缓存不能直接缓存用户私有的 `collected` 值。

### 4.3 评价接口

第一条闭环只允许评价电影，`ACTIVITY`、`VENUE` 保留为后续扩展类型。

#### 4.3.1 创建评价

1. URL：`/api/reviews`
2. Method：`POST`
3. 是否登录：是
4. Query / Path 参数：无
5. Request DTO：

```json
{
  "targetType": "MOVIE",
  "targetId": 1001,
  "score": 4.5,
  "content": "剧情很好，后半段稍慢。",
  "images": []
}
```

6. Response VO：返回创建后的 `ReviewVO`，至少包括 `id`、`user`、`targetType`、`targetId`、`score`、`content`、`images`、`likeCount`、`liked`、`createTime`。
7. 业务错误码：`UNAUTHORIZED`、`INVALID_TARGET_TYPE`、`MOVIE_NOT_FOUND`、`INVALID_SCORE`、`INVALID_SCORE_STEP`、`CONTENT_REQUIRED`、`DUPLICATE_REVIEW`
8. 数据库 / Redis 行为：校验 `targetType=MOVIE`、电影存在、`score` 在 `[1.0,5.0]` 且步长为 `0.5`；事务插入 `tb_review`，再使用单条原子 SQL 更新电影评分聚合：

```sql
UPDATE tb_movie
SET avg_score = (avg_score * score_count + #{score}) / (score_count + 1),
    score_count = score_count + 1
WHERE id = #{movieId};
```

不采用“先 SELECT、Java 计算、再 UPDATE”；事务提交后删除 `movie:detail:{targetId}`。评价列表第一版不缓存，AI Stream 和行为事件推迟到对应阶段实现。

#### 4.3.2 评价列表

1. URL：`/api/reviews`
2. Method：`GET`
3. 是否登录：否；登录用户附加返回 `liked`
4. Query / Path 参数：

```yaml
targetType: MOVIE
targetId: Long
page: Int = 1
pageSize: Int = 20
sortBy: latest|likeCount|score = latest
```

5. Request DTO：无，请求参数封装为 `ReviewQueryDTO`
6. Response VO：`ReviewPageVO`，包含 `list`、`total`、`page`、`pageSize`；列表项使用 `ReviewVO`。
7. 业务错误码：`INVALID_TARGET_TYPE`、`MOVIE_NOT_FOUND`、`INVALID_ID`、`INVALID_PAGE`、`PAGE_SIZE_TOO_LARGE`
8. 数据库 / Redis 行为：第一版直接分页查询 `tb_review` 中 `status=1` 的记录，不缓存频繁变化的评价列表；一次批量查询评价作者，禁止 MySQL N+1。登录用户通过 Redis ZSet 补充每条评价的 `liked`，匿名用户固定为 `false`。

#### 4.3.3 评价详情

1. URL：`/api/reviews/{id}`
2. Method：`GET`
3. 是否登录：否；登录用户附加返回 `liked`
4. Query / Path 参数：`id: Long`，必须为正整数
5. Request DTO：无
6. Response VO：返回完整 `ReviewVO`，包含评价作者公开资料、目标信息、评分、正文、图片、点赞数、当前用户 `liked` 状态和时间。
7. 业务错误码：`INVALID_ID`、`REVIEW_NOT_FOUND`
8. 数据库 / Redis 行为：第一版直接查询 `tb_review` 且要求 `status=1`，评价详情暂不缓存；查询作者公开资料，登录用户通过 `ZSCORE review:like:{id} userId` 补充 `liked`，匿名用户固定为 `false`。

#### 4.3.4 修改评价

1. URL：`/api/reviews/{id}`
2. Method：`PUT`
3. 是否登录：是，且只能修改本人评价
4. Query / Path 参数：`id: Long`
5. Request DTO：

```json
{
  "score": 4.0,
  "content": "修改后的评价内容。",
  "images": []
}
```

6. Response VO：返回更新后的 `ReviewVO`。
7. 业务错误码：`UNAUTHORIZED`、`REVIEW_NOT_FOUND`、`REVIEW_FORBIDDEN`、`INVALID_SCORE`、`INVALID_SCORE_STEP`、`CONTENT_REQUIRED`
8. 数据库 / Redis 行为：校验作者归属和评分规则；事务更新 `tb_review`；使用旧评分和新评分的差值原子更新聚合：

```sql
UPDATE tb_movie
SET avg_score = (avg_score * score_count - #{oldScore} + #{newScore}) / score_count
WHERE id = #{movieId} AND score_count > 0;
```

事务提交后删除电影详情缓存；评价列表和详情第一版不缓存，AI Stream 推迟到 AI 阶段。

#### 4.3.5 删除评价

1. URL：`/api/reviews/{id}`
2. Method：`DELETE`
3. 是否登录：是，且只能删除本人评价
4. Query / Path 参数：`id: Long`
5. Request DTO：无
6. Response VO：`data: null`；重复删除已删除评价按幂等成功处理。
7. 业务错误码：`UNAUTHORIZED`、`REVIEW_NOT_FOUND`、`REVIEW_FORBIDDEN`
8. 数据库 / Redis 行为：软删除，将 `tb_review.status` 更新为 `0`；事务内按以下规则原子更新电影聚合：

```sql
-- score_count > 1
UPDATE tb_movie
SET avg_score = (avg_score * score_count - #{oldScore}) / (score_count - 1),
    score_count = score_count - 1
WHERE id = #{movieId} AND score_count > 1;

-- score_count = 1
UPDATE tb_movie
SET avg_score = 0, score_count = 0
WHERE id = #{movieId} AND score_count = 1;
```

事务提交后删除电影详情缓存和该评价的点赞 ZSet；保留历史记录，不物理删除。AI Stream 推迟到 AI 阶段。

### 4.4 评价点赞接口

点赞接口不使用 toggle，而是使用“确保状态”的幂等语义。

#### 4.4.1 点赞评价

1. URL：`/api/reviews/{id}/like`
2. Method：`PUT`
3. 是否登录：是
4. Query / Path 参数：`id: Long`，评价 ID
5. Request DTO：无
6. Response VO：

```json
{
  "code": "OK",
  "message": "ok",
  "data": {
    "liked": true,
    "likeCount": 24
  },
  "requestId": "..."
}
```

7. 业务错误码：`UNAUTHORIZED`、`REVIEW_NOT_FOUND`、`LIKE_TARGET_INVALID`
8. 数据库 / Redis 行为：校验评价有效；以 `review:like:{id}` Redis ZSet 为点赞关系来源，`member=userId`、`score=点赞时间戳`；使用仅首次新增语义判断是否需要原子增加 `tb_review.like_count`。数据库更新失败时反向移除 ZSet 关系并记录错误；评价列表和详情第一版不缓存。

#### 4.4.2 取消评价点赞

1. URL：`/api/reviews/{id}/like`
2. Method：`DELETE`
3. 是否登录：是
4. Query / Path 参数：`id: Long`，评价 ID
5. Request DTO：无
6. Response VO：返回 `{ "liked": false, "likeCount": 23 }`。
7. 业务错误码：`UNAUTHORIZED`、`REVIEW_NOT_FOUND`、`LIKE_TARGET_INVALID`
8. 数据库 / Redis 行为：校验评价有效；通过 Lua 原子读取旧 score 并执行 `ZREM`，仅确实移除当前用户时原子减少 `tb_review.like_count`，并保证不会小于 0。数据库异常时按旧 score 恢复 ZSet 关系；ZSet 按 score 可支持最近点赞用户和前 N 个点赞用户查询。

### 4.5 阶段 13 Step 13.1 Activity Review 契约

Step 13.1 只冻结 Activity Review 的公开契约、生命周期和后续实现边界，不修改生产 Java、`sql/movielife.sql`、数据库结构、DTO、VO、Mapper、Redis Key 或现有公开接口行为。阶段 1～12 与 MOVIE Review 继续保持冻结；以下 `ACTIVITY` 能力只能从 Step 13.2 开始按后续独立 Step 实现。

Activity Review 复用 `targetType + targetId` 多态模型和 `/api/reviews` 通用入口。`targetType` 第一版要求严格大写 `ACTIVITY`，不在本阶段顺带改变 `MOVIE` 的参数规范化行为。活动评价不要求用户持有订单、已支付、已出票或已核销；不得读取 TicketOrder 或 TicketOrderItem 作为评价资格条件。

#### 4.5.1 创建或恢复 Activity 评价

1. URL：`/api/reviews`。
2. Method：`POST`。
3. 是否登录：是，使用现有 `@LoginRequired` 和 `UserHolder` 获取当前用户。
4. Query / Path / Body 参数：无 Query、Path 参数；Body 中 `targetType` 必须为 `ACTIVITY`，`targetId` 必须为正整数，`score` 范围为 `1.0～5.0` 且步长为 `0.5`，`content` 和 `images` 沿用阶段 3 的校验规则。
5. Request DTO：继续复用 `CreateReviewDTO { targetType, targetId, score, content, images }`，不新增 Activity 专用 DTO。
6. Response VO：继续返回 `ReviewVO`，包括 `id`、作者公开信息、`targetType=ACTIVITY`、`targetId`、`score`、`content`、`images`、`likeCount`、`commentCount`、`liked`、`createTime` 和 `updateTime`。
7. 业务错误码：`UNAUTHORIZED`、`INVALID_TARGET_TYPE`、`INVALID_ID`、`ACTIVITY_NOT_FOUND`、`INVALID_SCORE`、`INVALID_SCORE_STEP`、`CONTENT_REQUIRED`、`DUPLICATE_REVIEW`、`INTERNAL_ERROR`。
8. 数据库 / Redis 行为：创建或恢复前读取 `tb_activity`，要求目标物理存在且 `status=1`；不存在、草稿或下线统一返回 `ACTIVITY_NOT_FOUND`。同一用户、`ACTIVITY` 和目标 ID 已有 `status=1` 记录时返回 `DUPLICATE_REVIEW`；已有 `status=0` 记录时恢复原记录并重置评价内容、评分、图片、点赞计数和时间。后续实现必须在同一 MySQL 事务中写 `tb_review` 并用单条原子 SQL 更新 `tb_activity.avg_score/score_count`，严格校验 `affectedRows`；不得在 Java 中先读聚合再计算写回。恢复成功后仅在 COMMIT 后清理该 Review 既有 `review:like:{reviewId}`，不新增 Activity Review Redis Key、Activity 公共缓存、AI 事件、行为事件或票务状态。

#### 4.5.2 Activity 评价列表

1. URL：`/api/reviews`，Activity 不新增嵌套列表路径。
2. Method：`GET`。
3. 是否登录：否；匿名用户的 `liked` 固定为 `false`，有效登录用户沿用现有点赞关系读取。
4. Query / Path / Body 参数：`targetType=ACTIVITY`、正整数 `targetId`、`page` 默认 1、`pageSize` 默认 20 且最大 100、`sortBy=latest|likeCount|score` 默认 `latest`；无 Path 和 Body 参数。
5. Request DTO：Query 参数继续封装为 `ReviewQueryDTO`，不新增 Activity 专用查询 DTO。
6. Response VO：返回 `PageVO<ReviewVO>`；只包含 `status=1` 的 Activity 评价，排序分别为 `create_time DESC, id DESC`、`like_count DESC, id DESC` 或 `score DESC, id DESC`，保证稳定分页。
7. 业务错误码：`INVALID_TARGET_TYPE`、`INVALID_ID`、`ACTIVITY_NOT_FOUND`、`INVALID_PAGE`、`PAGE_SIZE_TOO_LARGE`、`INTERNAL_ERROR`。
8. 数据库 / Redis 行为：列表查询前要求 `tb_activity` 物理存在且 `status=1`；不存在、草稿或下线统一返回 `ACTIVITY_NOT_FOUND`，不暴露其评价列表。随后直接分页查询 `tb_review(target_type='ACTIVITY', target_id, status=1)`，一次批量读取本页作者，禁止 MySQL N+1，不缓存评价列表。登录用户继续通过现有 `review:like:{reviewId}` ZSet 补充 `liked`；查询不得写 MySQL、Activity、GEO、锁座或订单状态，通用登录 TTL 刷新不属于评价业务写入。

#### 4.5.3 Activity 评价详情

1. URL：`/api/reviews/{id}`。
2. Method：`GET`。
3. 是否登录：否；匿名 `liked=false`，有效登录用户返回自己的真实点赞状态。
4. Query / Path / Body 参数：Path 参数 `id` 必须为正整数；无 Query 和 Body 参数。
5. Request DTO：无。
6. Response VO：继续返回完整 `ReviewVO`，不新增 Activity 专用详情 VO。
7. 业务错误码：`INVALID_ID`、`REVIEW_NOT_FOUND`、`INTERNAL_ERROR`。
8. 数据库 / Redis 行为：延续阶段 3 的通用 Review 详情语义，只要求命中的 `tb_review.status=1`，不重新校验 Activity 当前状态。Activity 后续下线不使已知评价 ID 失效；目标物理缺失属于异常数据，但本接口仍以 Review 本身是否有效为公开判定。评价和作者从 MySQL 读取，登录用户通过 `ZSCORE review:like:{id} userId` 补充 `liked`，不缓存详情、不写 Activity 或其他业务状态。

#### 4.5.4 修改 Activity 评价

1. URL：`/api/reviews/{id}`。
2. Method：`PUT`。
3. 是否登录：是，且只能修改本人评价。
4. Query / Path / Body 参数：Path 参数 `id` 必须为正整数；Body 中 `score`、`content`、`images` 沿用阶段 3 规则，不允许通过 Body 改变 `targetType` 或 `targetId`。
5. Request DTO：继续复用 `UpdateReviewDTO { score, content, images }`。
6. Response VO：返回修改后的 `ReviewVO`。
7. 业务错误码：`UNAUTHORIZED`、`INVALID_ID`、`REVIEW_NOT_FOUND`、`REVIEW_FORBIDDEN`、`INVALID_SCORE`、`INVALID_SCORE_STEP`、`CONTENT_REQUIRED`、`INTERNAL_ERROR`。
8. 数据库 / Redis 行为：先按评价 ID 锁定 `tb_review`，要求评价有效、属于当前用户且 `target_type='ACTIVITY'`；目标 `tb_activity` 必须物理存在，但 `status=2` 下线不阻止作者修改既有评价，物理缺失作为 `INTERNAL_ERROR` 失败关闭。事务内更新评价并使用旧、新评分通过单条原子 SQL 替换 Activity 聚合，影响行数必须为 1；失败整体回滚。Activity 当前无详情缓存，因此不新增缓存失效、Redis Key、AI 事件或票务读写。

#### 4.5.5 删除 Activity 评价

1. URL：`/api/reviews/{id}`。
2. Method：`DELETE`。
3. 是否登录：是，且只能删除本人评价。
4. Query / Path / Body 参数：Path 参数 `id` 必须为正整数；无 Query 和 Body 参数。
5. Request DTO：无。
6. Response VO：`data=null`；同一条已软删除评价重复删除按阶段 3 规则幂等成功。
7. 业务错误码：`UNAUTHORIZED`、`INVALID_ID`、`REVIEW_NOT_FOUND`、`REVIEW_FORBIDDEN`、`INTERNAL_ERROR`。
8. 数据库 / Redis 行为：先按评价 ID 锁定 `tb_review` 并校验作者；目标 Activity 后续下线不阻止删除，物理缺失作为 `INTERNAL_ERROR`。首次删除在同一事务内软删除 Review，并以原子 SQL 减少 `tb_activity.score_count`、重算 `avg_score`；删除最后一条有效评价时固定写为 `avg_score=0.00, score_count=0`。所有条件 UPDATE 严格校验 `affectedRows`，任何失败整体回滚；COMMIT 后清理 `review:like:{reviewId}`，不删除其他用户关系或写入 Activity、订单、GEO 和临时锁状态。

#### 4.5.6 Activity 评价点赞与取消点赞

1. URL：`/api/reviews/{id}/like`。
2. Method：确保已点赞使用 `PUT`，确保未点赞使用 `DELETE`。
3. 是否登录：是。
4. Query / Path / Body 参数：Path 参数 `id` 必须为正整数；无 Query 和 Body 参数。
5. Request DTO：无。
6. Response VO：继续返回 `ReviewLikeVO { liked, likeCount }`。
7. 业务错误码：`UNAUTHORIZED`、`INVALID_ID`、`LIKE_TARGET_INVALID`、`INTERNAL_ERROR`。
8. 数据库 / Redis 行为：Activity Review 与 MOVIE Review 共用阶段 3 已冻结的点赞实现，只校验评价本身有效，不重新校验 Activity 当前状态。关系事实保存在 `review:like:{reviewId}` ZSet，`member=userId`、`score=首次点赞时间戳`；只有 ZSet 实际新增或移除时才原子增减 `tb_review.like_count`，数据库失败继续执行既有反向补偿。不得创建 `activity:review:like:*` 等新 Key。

#### 4.5.7 Activity 评分展示与后续实现边界

Step 13.2 才允许为 `tb_activity` 设计并实现 `avg_score DECIMAL(3,2) NOT NULL DEFAULT 0.00` 和 `score_count INT NOT NULL DEFAULT 0` 及可重复迁移；Step 13.1 不修改 SQL。Step 13.4 才允许在 `ActivityListItemVO`、`ActivityDetailVO` 和 `ActivityNearbyVO` 末尾兼容增加 `avgScore`、`scoreCount`。`GET /api/collections?targetType=ACTIVITY` 复用 `ActivityListItemVO`，因此收藏列表必须同步展示同一聚合值，不新增 Collection 专用评分字段。

评分字段只用于响应展示，不参与 Activity 普通列表的查询条件和 `create_time DESC, id DESC` 顺序，也不进入阶段 11 `NearbyActivityChoice`，不得改变多 VenueType GEO 候选、`min(page × pageSize × 5, 200)` 窗口、Activity 去重、最近 Venue、下一 Showtime、售罄可发现、稳定排序或过滤后分页。`ActivityShowtimeVO`、Showtime、订单和票券 VO 不增加评分字段。

Step 13.1 至此冻结。后续必须依次完成独立的持久化、写路径、查询展示、并发边界和最终联调 Step；本 Step 不实现 Step 13.2 及以后能力，不加入 Venue Review、购买/核销后评价、评价审核、评价评论、AI 总结、Activity 管理端、非固定座位票务、Redis Stream、MQ、第三方支付、部分退款或核销撤销。

#### 4.5.8 Step 13.2～13.6 实施结果与最终冻结

阶段 13 已按 13.1 契约完成实现，未新增 Activity 专用评价入口。`POST/GET /api/reviews`、`GET/PUT/DELETE /api/reviews/{id}` 和 `PUT/DELETE /api/reviews/{id}/like` 仍是唯一公开接口；DTO、VO、错误码和下线语义均保持 13.1 冻结结论。

持久化层已为 `tb_activity` 增加 `avg_score DECIMAL(3,2) NOT NULL DEFAULT 0.00` 与 `score_count INT NOT NULL DEFAULT 0`，主 SQL 与增量迁移保持一致并支持聚合回填。创建、恢复、替换评分、删除和最后一条删除均由 ActivityMapper 单条原子 SQL 完成，所有条件更新严格要求 `affectedRows == 1`，没有 Java 先读平均分再回写的路径。

写路径在单个 MySQL 事务内完成 Review 状态和 Activity 聚合更新。创建或恢复只增加一次计数，修改保持计数不变，重复删除幂等；恢复会重置旧点赞计数，并在 COMMIT 后清理旧 `review:like:{reviewId}`。为避免同一用户首次评价时 InnoDB 缺口锁竞争，创建事务使用 `READ_COMMITTED`，数据库唯一约束继续作为最终防重事实。

`ActivityListItemVO`、`ActivityDetailVO` 和 `ActivityNearbyVO` 已统一返回 `avgScore`、`scoreCount`，Activity 收藏列表复用的 `ActivityListItemVO` 也填充相同值。普通列表仍按 `create_time DESC, id DESC`；Nearby 的 GEO 候选、去重、最近场馆、下一场次、稳定排序和过滤后分页未改变；`collected` 的查询与批量组装逻辑未改变，没有新增 MySQL N+1。

Step 13.6 的真实 MySQL、Redis、随机端口 HTTP 链路验证了：初始 `0.00/0`，用户 A 创建 4.5、用户 B 创建 3.5 后为 `4.00/2`，A 修改为 5.0 后为 `4.25/2`，点赞与取消点赞一致，B 删除后为 `5.00/1`，B 恢复后聚合恢复，并覆盖 Activity 下线后的列表、详情、评价详情、修改、删除和禁止恢复语义。测试前后 Showtime、ShowtimeSeat、TicketOrder、TicketOrderItem、`available_seats`、Venue GEO、`seat:lock:*` 和 `seat:lock:token:*` 快照一致。

阶段 10 的 `Activity → Showtime → Seat Map → Lock → Order → Pay → Issue → Verify` 真实 HTTP 闭环已重新通过。阶段 13 定向测试 19 项、阶段 11～12 交叉测试 20 项均通过；全量 486 项测试通过，0 failure、0 error、0 skipped，`mvn clean package` 为 `BUILD SUCCESS`。测试数据、Review Like 和锁座 Key 已清理，阶段 13 至此冻结。

### 4.6 阶段 14 Step 14.1 Review AI Summary 通用契约

Step 14.1 只冻结 `MOVIE`、`ACTIVITY` 两类 Review 的 AI 总结公开契约、异步任务职责、缓存规则、安全边界和后续实施顺序。本 Step 不修改生产 Java、`sql/movielife.sql`、数据库结构、DTO、VO、Mapper、Redis Key、依赖、运行时配置或现有公开接口行为，不调用模型，也不实现 Step 14.2 及以后能力。阶段 1～13 的 Review、评分聚合、收藏、Nearby 和票务语义继续冻结。

#### 4.6.1 能力与目标边界

能力名称统一为 `Review AI Summary`。它只把目标下当前有效的 Review 转换为一份结构化消费参考，不生成电影剧情简介，不修改目标资料，不审核或改写评价，也不影响推荐、价格、库存、订单或票券状态。`tb_movie.summary` 永远表示运营维护的电影剧情简介；AI 评价总结只能持久化到 `tb_ai_review_summary` 及后续明确设计的 AI 任务结构，禁止写入 `tb_movie.summary`、`tb_activity.description` 或 Review 正文。

第一阶段 `targetType` 要求严格大写并仅允许：

- `MOVIE`：要求 `tb_movie` 物理存在。为保持现有 Movie Review 列表语义，不新增 `COMING/SHOWING/OFFLINE` 状态门槛；不存在返回 `404 MOVIE_NOT_FOUND`。
- `ACTIVITY`：目标级总结与 Activity Review 列表保持一致，要求 `tb_activity` 物理存在且 `status=1`；不存在、草稿或下线统一返回 `404 ACTIVITY_NOT_FOUND`。Activity 下线不删除历史 AI 版本，但公开接口不再返回其总结。

`VENUE`、`POST` 及其他值统一返回 `400 INVALID_TARGET_TYPE`。Movie 与 Activity 即使数字 ID 相同，也必须以 `targetType + targetId` 完整隔离任务、版本、输入指纹、MySQL 记录和 Redis 缓存。

#### 4.6.2 AI 总结查询

1. URL：`/api/ai/summaries/{targetType}/{targetId}`。
2. Method：`GET`。
3. 是否登录：否；结果是目标级公共内容，不读取当前用户，也不返回用户私有状态。携带有效登录态时只允许发生既有通用 Token TTL 刷新。
4. Query / Path / Body 参数：Path 中 `targetType` 必须为严格大写 `MOVIE` 或 `ACTIVITY`，`targetId` 必须为正整数；无 Query 和 Body 参数。客户端不得提交 Prompt、模型名、供应商地址、密钥、输入评价、版本或任务操作。
5. Request DTO：无。
6. Response VO：统一返回 `ReviewAiSummaryVO`：

```json
{
  "targetType": "MOVIE",
  "targetId": 1001,
  "status": "READY",
  "summary": "整体口碑较好，叙事和表演获得较多认可。",
  "pros": ["表演自然", "叙事完整"],
  "cons": ["部分段落节奏偏慢"],
  "suitableFor": ["偏好剧情片的观众"],
  "warningPoints": ["追求强节奏的观众需谨慎"],
  "sourceReviewCount": 28,
  "inputReviewCount": 28,
  "version": 3,
  "promptVersion": "review-summary-v1",
  "generatedAt": "2026-09-17T10:30:00",
  "stale": false
}
```

7. 业务错误码：`INVALID_TARGET_TYPE`、`INVALID_ID`、`MOVIE_NOT_FOUND`、`ACTIVITY_NOT_FOUND`、`INTERNAL_ERROR`。
8. 数据库 / Redis / 模型行为：先按目标类型执行上述目标有效性校验，再读取 MySQL 中的当前任务和最新可公开总结；允许从 Redis 读取同一份公共结果。Redis 不可用或缓存内容非法时必须回源 MySQL，不得因此同步调用模型。MySQL 和 Redis 均不应在普通 GET 中创建 AI 任务；缓存回填是唯一允许的附带写入，且失败不改变 HTTP 业务结果。

#### 4.6.3 公开状态与字段空值

`ReviewAiSummaryVO.status` 只允许以下值：

- `NOT_ENOUGH_REVIEWS`：当前有效评价少于 3 条，不调用模型；总结文本、数组、版本、Prompt 版本和生成时间为 `null`，计数字段返回当前事实，`stale=false`。
- `GENERATING`：已经达到 3 条，但尚无成功总结，任务处于等待、处理或可重试状态；总结结果字段为 `null`，`stale=false`。
- `READY`：最新成功总结与当前已处理任务版本一致；返回完整结果，`stale=false`。
- `REFRESHING`：已有成功总结，但之后又发生有效评价变化或任务正在重试；继续返回最近一次成功结果，`stale=true`。
- `UNAVAILABLE`：达到生成条件但没有成功历史结果，且本轮任务已经进入不可继续自动重试的失败状态；总结结果字段为 `null`，不向客户端暴露供应商响应、Prompt、堆栈或内部错误文本。

`pros`、`cons`、`suitableFor` 和 `warningPoints` 在有成功总结时返回数组，不返回 JSON 字符串；每组最多 5 项，单项最多 100 个字符。`summary` 最多 300 个字符。服务端必须把模型响应视为不可信输入并完成 JSON 结构、类型、数量、长度和空白校验；校验失败不能发布为成功版本。

#### 4.6.4 评价输入与触发规则

输入事实只来自 `tb_review` 中目标匹配且 `status=1` 的有效记录。触发范围严格限定为 `MOVIE` 或 `ACTIVITY` Review 的成功创建、软删除恢复、修改和首次软删除；必须在原 Review MySQL 事务提交后发出异步通知。事务回滚、重复删除、评价查询、点赞、取消点赞、评论数变化、收藏变化、Activity Nearby、订单和票务状态变化均不得触发总结生成。

模型输入只包含评价 ID、评分和去除首尾空白后的正文；不得发送用户 ID、昵称、头像、手机号、图片、点赞用户关系、评论、收藏、订单、支付、出票、核销或座位信息。有效评价总数记为 `sourceReviewCount`；实际最多选取 100 条，按 `update_time DESC, id DESC` 稳定排序，实际输入数记为 `inputReviewCount`。单条正文最多取前 1000 个字符，所有输入还必须受可配置的总字符或 token 预算约束。

输入指纹必须至少覆盖 `targetType + targetId + promptVersion + 规范化后有序输入中的 reviewId/score/content`，使用稳定摘要算法生成。相同目标、Prompt 版本和输入指纹不得重复发布成功结果。评价正文属于不可信用户数据，Prompt 必须把它封装为待分析数据；正文中的命令、角色声明、链接或提示词不得改变系统指令、输出格式或权限边界。

#### 4.6.5 异步任务与一致性

Review AI Summary 复用项目总体框架中的 Redis Stream 作为异步通知通道；Stream 只负责至少一次投递，不是总结或任务的最终事实。后续实现使用独立的 AI Summary Stream、Consumer Group 和服务端消费者，不复用 Review Like ZSet、Feed、GEO、锁座或订单 Key。任务状态、当前可公开版本、重试次数和租约等可靠事实必须持久化到 MySQL；具体表字段、索引和迁移属于 Step 14.2，本 Step 不修改 SQL。

内部任务状态冻结为：

```text
PENDING → PROCESSING → SUCCEEDED
                    ↘ RETRY_WAIT → PROCESSING
                    ↘ DEAD
PENDING/PROCESSING → INSUFFICIENT_REVIEWS
```

评价发生新变化时必须推进目标的待处理版本；处于 `SUCCEEDED`、`RETRY_WAIT`、`DEAD` 或 `INSUFFICIENT_REVIEWS` 的旧任务均可因新变化重新进入 `PENDING`。多实例消费者必须通过 MySQL 条件更新和有期限租约保证同一目标同一待处理版本只有一个有效执行者。消费者在调用模型前读取当前 MySQL 评价快照；模型调用期间若又发生评价变化，本次结果可以保留为历史版本，但不得把更新后的任务错误标记为最新完成，必须继续处理后续版本。

AI 通知在 Review 事务提交后执行。Stream、任务标记或模型调用失败只记录可恢复状态和脱敏日志，不得回滚已经提交的 Review、评分聚合或缓存清理。后续实现必须提供有界重试、Pending 恢复以及基于 MySQL Review 事实的周期性对账修复，避免提交后通知偶发失败造成永久漏生成。消费者只有在 MySQL 已成功持久化可公开结果后才能确认 Stream 消息；重复投递依靠目标版本和输入指纹幂等收敛。

#### 4.6.6 MySQL 与 Redis 缓存职责

`tb_ai_review_summary` 继续保存 AI 评价总结，与 `tb_movie.summary` 的电影剧情简介完全无关。成功总结按 `targetType + targetId` 独立递增版本并保留历史；后续 Step 14.2 才允许设计输入指纹、Prompt 版本、输入数量、任务状态及当前版本指针等持久化字段或任务表，并提供兼容 MySQL 8.0.17 的可重复迁移。

MySQL 是任务状态、版本、输入计数和总结内容的最终事实。Redis 只缓存已经由 MySQL 确认的公共查询结果，缓存 Key 规划为：

```text
ai:review:summary:{targetType}:{targetId}
```

Key 中 `targetType` 使用严格大写。成功或旧版本降级结果采用 30 分钟基础 TTL 加 0～5 分钟随机抖动；`NOT_ENOUGH_REVIEWS`、`GENERATING` 和 `UNAVAILABLE` 最多短暂缓存 2 分钟。评价变化提交后应使对应目标缓存失效或进入可识别的刷新状态；新结果必须先提交 MySQL，再更新或删除缓存。缓存写入、删除失败不得改变 MySQL 任务结论，Redis 数据丢失不得丢失总结或触发同步模型调用。

Review Like 继续只使用 `review:like:{reviewId}` ZSet；Review AI Summary 不读取点赞关系，不因点赞变化重新生成，也不得修改 `movie:detail:*`、Venue GEO、Feed、登录、收藏、`seat:lock:*`、`seat:lock:token:*` 或任何订单相关 Key。

#### 4.6.7 权限、安全与冻结边界

公开能力只有匿名只读 GET。阶段 14 不新增手工生成、重试、取消、Prompt 调试或模型配置 HTTP 接口，也不把内部 Worker 包装成 `/api/admin`。模型供应商地址、模型名、超时、密钥和预算只能由服务端受控配置提供；密钥不得进入仓库、数据库结果、Redis 消息、响应或普通日志。日志不得记录完整 Prompt、完整评价正文、模型密钥或供应商原始响应，只记录 requestId、目标、内部任务 ID、结果分类、耗时和必要的脱敏诊断信息。

AI 输出只提供消费参考，不参与 Review 是否允许写入、目标评分聚合、Activity 上下架、Movie 状态、Nearby 排序、收藏、Showtime、ShowtimeSeat、`available_seats`、锁座、订单、支付、出票、退款或核销判断。模型或 AI 基础设施不可用时，以上核心业务必须保持原行为。

后续实施顺序冻结为：Step 14.2 通用持久化与任务基础；Step 14.3 通用评价快照、Prompt 与模型适配；Step 14.4 先完成 Movie Review AI Summary 闭环；Step 14.5 再以同一通用能力接入 Activity；Step 14.6 完成跨目标并发、真实模型主链路、全量回归和阶段冻结。任何 Step 只能实现自身范围，不得提前接入下一目标，也不得加入 Venue/Post 总结、个性化推荐、RAG、向量数据库、AI 审核、管理端、非固定座位票务、第三方支付、MQ 或其他 AI 能力。

#### 4.6.8 Step 14.1 验证与冻结结果

Step 14.1 最终只修改 `docs/API接口设计.md` 与 `docs/MovieLife_项目技术设计.md`，没有修改生产 Java、测试代码、SQL、迁移、依赖、配置或 Redis Key，也没有调用模型。阶段 13 Activity Review 定向回归 34 项通过；阶段 1～13 全量 486 项测试通过，0 failure、0 error、0 skipped；不跳过测试的 `mvn clean package` 为 `BUILD SUCCESS`。

真实验证使用隔离 MySQL 8.0.17、Redis 7 和 Spring Boot 随机 HTTP 端口；权威 SQL 初始化、Activity Review 完整 HTTP 链路及既有全部集成测试通过。测试后隔离 Redis `DBSIZE=0`，本轮 MySQL、Redis 容器及临时数据已删除。Step 14.1 至此冻结，下一步只能单独进入 Step 14.2 通用持久化与任务基础。

#### 4.6.9 Step 14.2 通用持久化与任务基础

Step 14.2 只实现目标无关的可靠性底座，不开放本节规划的 GET 接口，不监听 Review 变化，不创建 Redis Stream 消息，不读取 Movie/Activity 有效性，不组装评价快照、计算输入指纹或调用模型。因而本 Step 完成后，现有 Movie/Activity Review 请求仍不会产生 AI 任务或总结。

`tb_ai_review_summary` 只保存成功结果的不可变版本，新增 `prompt_version`、`source_fingerprint CHAR(64) ASCII BINARY`、`source_version` 和 `input_review_count`。`target_type + target_id + version` 保证目标内版本唯一；`target_type + target_id + prompt_version + source_fingerprint` 保证同一规范输入不会重复发布。输入指纹固定为小写 SHA-256 十六进制文本，但指纹的规范化计算属于 Step 14.3，本 Step 只校验和持久化已经计算好的值。

`tb_ai_summary_task` 对每个 `target_type + target_id` 只保留一行任务事实：

- `dirty_version` 表示累计收到的有效变化版本，`processed_version` 表示已经完成判断的版本。
- `processing_version` 把当前 Worker 租约绑定到它领取时的 `dirty_version`；生成期间再次变脏时，本次完成只能推进到旧版本，任务继续保持 `PENDING`。
- `last_summary_version` 负责分配目标内成功总结版本，`current_summary_id` 是当前可用不可变总结指针；发布事务会校验指针指向的 Summary 与任务目标一致。
- `status` 只允许 `PENDING`、`PROCESSING`、`SUCCEEDED`、`RETRY_WAIT`、`DEAD`、`INSUFFICIENT_REVIEWS`。
- `attempt_count`、`next_retry_time`、`lease_owner`、`lease_until` 和脱敏后的 `last_error_code` 支撑有界重试与 Worker 宕机接管；供应商原文、Prompt、密钥和评价正文不得写入错误字段。旧版本处理期间若已有更新的 `dirty_version`，旧版本随后重试或终止时必须立即回到 `PENDING` 并清除旧尝试，不能延迟或杀死新输入。

任务写入全部使用 MySQL 原子条件语句并严格检查 `affectedRows`。领取仅允许未完成的 `PENDING`、到期的 `RETRY_WAIT` 或租约过期的 `PROCESSING`；续租、重试、终止、评价不足和发布都必须匹配目标、`processing_version`、租约持有者及未过期时间。发布在同一事务内锁定任务行、按输入幂等查找或插入 Summary、更新当前指针并释放租约；任一步失败整体回滚。

Redis 本 Step 只提供内部不可变结果缓存仓库，Key 仍为 `ai:review:summary:{targetType}:{targetId}`。Value 是带 `summaryId`、目标和版本的完整快照，TTL 为 30 分钟加 0～5 分钟抖动；只能整体替换，不能逐字段修改。读取到损坏或串目标数据按未命中处理并删除；Redis 读写失败不改变 MySQL 任务或总结事实。非终态短缓存、MySQL 回源编排及 HTTP 输出留到目标闭环 Step 实现。

Step 14.2 的内部类不构成客户端契约，普通用户、场馆工作人员和管理端均无调用入口。后续 Step 14.3 只能继续实现通用评价快照、稳定指纹算法、Prompt、模型 Adapter 和响应校验，不得借此接入 Movie/Activity Review 触发或公开查询。

#### 4.6.10 Step 14.2 验证与冻结结果

Step 14.2 定向测试 14 项通过，覆盖真实 MySQL 任务状态、目标隔离、并发标脏、租约竞争与过期接管、重试到期、处理中再次变脏、旧版本失败不压住新输入、评价不足、指纹幂等、当前 Summary 指针，以及真实 Redis 不可变快照和缓存故障降级。阶段 13 Activity Review 定向回归 34 项通过；全量 500 项测试通过，0 failure、0 error、0 skipped；不跳过测试的 `mvn clean package` 为 `BUILD SUCCESS`。

真实验证使用隔离 MySQL 8.0.17、Redis 7 和 Spring Boot 随机 HTTP 端口。权威 SQL 初始化成功，`V14_2__ai_summary_persistence.sql` 连续执行两次成功；既有 Activity Review HTTP 链路保持原行为。测试数据清理后 AI Task/Summary 均为 0 行，隔离 Redis 最终 `DBSIZE=0`，临时容器均已删除。Step 14.2 至此冻结，下一步只能单独进入 Step 14.3 通用评价快照、输入指纹、Prompt 与模型适配。

#### 4.6.11 Step 14.3 通用模型适配和评价快照

Step 14.3 只实现服务端内部的通用生成基础，不开放本节规划的公开查询接口，不监听 Review 写入，不标脏、领取或完成 `tb_ai_summary_task`，不发布 `tb_ai_review_summary`，也不创建 Redis Stream 消息或结果缓存。`ReviewSummaryGenerationService.generate(targetType, targetId, sourceVersion)` 只返回“评价不足”或一个尚未持久化的 `AiSummaryPublication`；后续业务闭环必须显式接管任务状态与发布事务。

Movie 与 Activity 共用一条生成链路，差异只由 `ReviewSummaryTargetPolicy` 提供。`MovieSummaryTargetPolicy` 要求 `tb_movie` 物理存在，不附加状态限制；`ActivitySummaryTargetPolicy` 要求 `tb_activity` 物理存在且 `status=1`，否则沿用 `MOVIE_NOT_FOUND` 或 `ACTIVITY_NOT_FOUND`。核心代码禁止出现目标专用的 `generateMovieSummary()`、`generateActivitySummary()` 两套实现。

`ReviewSummarySourceService` 在只读事务内先验证目标，再统计当前 `status=1` 的有效 Review 总数，并按 `update_time DESC, id DESC` 最多读取最新 100 条。模型输入只包含 Review ID、规范化评分和正文，不包含用户身份、图片、点赞或票务数据。正文先统一换行、执行 Unicode NFC 和首尾空白清理，再以固定占位符遮蔽手机号、邮箱和中国大陆身份证号；单条最多 1000 个 Unicode code point，总正文预算最多 20000 个 code point。送入 Prompt 与指纹的必须是完全相同的脱敏、截断结果。

输入指纹固定为小写 64 位 SHA-256。摘要前的数据按 UTF-8 长度前缀编码，依次覆盖 `targetType`、`targetId`、`promptVersion`、有效评价总数、实际输入数以及有序的 `reviewId/score/content`，避免简单字符串拼接产生边界歧义。固定 Prompt 版本为 `review-summary-v1`；系统指令与用户评价 JSON 分开传递，明确评价正文只是不可信数据，不能改变角色、权限或输出格式。

`AiReviewSummaryClient` 当前提供 OpenAI-compatible HTTP Adapter。Endpoint、API Key、模型名、3 秒连接超时、45 秒读取超时和 16 KiB 响应上限均来自服务端配置；客户端不能覆盖。请求正文和 Prompt 不写普通日志，响应按字节流有界读取。HTTP 401/403、429、5xx、网络连接、超时和响应无效分别归类为稳定错误码，数据库和公开响应均不得保存供应商原始错误正文。

模型响应必须是单个 JSON 对象，且恰好包含 `summary`、`pros`、`cons`、`suitableFor`、`warningPoints` 五个字段；拒绝 Markdown fence、重复字段、未知字段、尾随 JSON、错误类型、空字符串、重复数组项和越界内容。`summary` 最多 300 个 Unicode code point，四组数组各最多 5 项、每项最多 100 个 Unicode code point。超时、连接失败、限流、供应商 5xx 和无效响应可进入任务级重试；配置、认证和请求拒绝不重试。第 1、2 次失败的基础等待分别为 30 秒、2 分钟，后续为 10 分钟，总尝试上限 3 次；Adapter 内部不阻塞重试。

#### 4.6.12 Step 14.3 验证与冻结结果

Step 14.3 定向测试 17 项通过，覆盖 Movie/Activity 目标策略、真实 MySQL 一致快照与排序、Unicode 规范化和截断、PII 脱敏、稳定指纹、Prompt 注入隔离、严格 JSON、真实本地 HTTP Adapter、状态/超时分类、响应大小上限、重试决策、评价不足短路及待发布结果组装。阶段 13 Activity Review 交叉回归 34 项通过；全量 517 项测试通过，0 failure、0 error、0 skipped；不跳过测试的 `mvn clean package` 为 `BUILD SUCCESS`。

真实验证使用隔离 MySQL 8.0.17、Redis 7、随机 Spring Boot HTTP 端口和随机本地模型 Stub 端口。快照事务回滚后测试 Movie/User/Review 数据为 0，AI Task/Summary 均为 0 行，Redis 最终 `DBSIZE=0`；隔离容器和临时运行进程已删除。Step 14.3 至此冻结，下一步只能单独进入 Step 14.4 Movie Review AI Summary 闭环。

#### 4.6.13 Step 14.4 Movie Review AI Summary 闭环

Step 14.4 只接入 `MOVIE`。Movie Review 成功创建、软删除恢复、修改或首次软删除后，沿用既有 Review 事务提交回调，先标记通用 MySQL 任务为脏，再清理 `ai:review:summary:MOVIE:{movieId}` 公共缓存并向独立 `ai:review:summary:stream` 写入只含 `targetType=MOVIE`、`targetId` 的通知。事务回滚、重复删除、查询、点赞、收藏或票务变化均不触发；AI 任务、Stream 或缓存异常不得回滚已经提交的 Review 与评分聚合。能力由服务端配置开关控制，默认关闭，不改变未启用环境的既有 Review 行为。

Movie Worker 使用 Consumer Group `ai:review:summary:group` 接收至少一次通知，并以 MySQL 条件 UPDATE 竞争任务版本和有期限租约。领取成功后只调用 Step 14.3 的通用 `ReviewSummaryGenerationService`，不复制 Movie 专用 Prompt、快照或模型生成方法。有效评价少于 3 条时写入 `INSUFFICIENT_REVIEWS` 并清除旧缓存；成功结果通过 `publish()` 在同一 MySQL 事务中幂等写入不可变 Summary、推进版本并更新当前指针，事务返回后才更新公共 Redis 缓存。可重试错误进入 `RETRY_WAIT`，到期扫描器重新投递；达到上限或不可重试错误进入 `DEAD`。只有状态转换已由 MySQL 接受的消息才确认，重复消息通过任务版本和输入指纹收敛。

匿名只读入口现已开放 `GET /api/ai/summaries/MOVIE/{movieId}`。它先验证正整数 ID 和 Movie 物理存在，再读取有效 Review 数、MySQL 任务和当前 Summary；Redis 仅用于读取同一不可变成功结果，未命中或损坏时回源 MySQL 并尽力回填，绝不在 GET 中创建任务或同步调用模型。公开状态严格映射为 `NOT_ENOUGH_REVIEWS`、`GENERATING`、`READY`、`REFRESHING`、`UNAVAILABLE`；`REFRESHING` 继续返回最近成功版本并设置 `stale=true`，内部错误码、租约、重试次数和供应商信息不进入响应。

本 Step 没有监听 `ACTIVITY` Review，也不接受 `ACTIVITY` 查询；Activity 目标策略仍仅作为 Step 14.3 通用生成基础存在，必须到 Step 14.5 才能复用同一触发、Worker 和查询能力接入。跨消费者 Pending 接管、周期性对账、跨目标并发和真实供应商主链路仍属于 Step 14.6；本 Step 不新增管理端生成接口、Venue/Post、个性化推荐或任何票务改动。

#### 4.6.14 Step 14.4 验证与冻结结果

Step 14.4 定向测试 24 项通过，覆盖创建、恢复、修改、首次删除、重复删除与事务回滚触发边界、Activity 不误触发、缓存失败不阻塞通知、Movie 公开五状态、评价不足、成功发布、可重试与终止失败、到期重投、匿名 Controller，以及真实 MySQL、Redis Stream、租约、Summary 指针、结果缓存和随机端口 HTTP 闭环。阶段 13 Activity Review 交叉回归 34 项通过；全量 541 项测试通过，0 failure、0 error、0 skipped；不跳过测试的 `mvn clean package` 为 `BUILD SUCCESS`。

真实验证使用隔离 MySQL 8.0.17、Redis 7 和随机 Spring Boot HTTP 端口，模型边界使用受控 `AiReviewSummaryClient` Stub，模型 Adapter 的真实本地 HTTP 协议证据继续继承 Step 14.3。测试数据、AI Task/Summary、Stream 与公共缓存已清理，Redis 最终 `DBSIZE=0`，隔离容器已删除。Step 14.4 至此冻结，下一步只能单独进入 Step 14.5 Activity Review AI Summary 接入。

#### 4.6.15 Step 14.5 Activity Review AI Summary 接入

Step 14.5 不复制 Movie 专用链路，而是把 14.4 的 Trigger、Worker、查询服务和 Stream 消费者收敛为按 `targetType` 工作的通用组件。Activity Review 成功创建、软删除恢复、修改或首次软删除后，仍在原 Review 事务成功提交后调用通用 Trigger，以 `ACTIVITY + activityId` 原子标脏、删除对应公共缓存并发布独立 AI Summary Stream 通知。Movie 行为保持不变；任务、Summary 版本、输入指纹和缓存继续按完整目标隔离。

Activity 触发、快照、Worker 发布前复核和公开 GET 均复用 `ActivitySummaryTargetPolicy`：只有物理存在且 `status=1` 的 Activity 才能生成或公开查询。Activity 下线不删除 `tb_ai_review_summary` 历史版本、当前 Summary 指针或已有结果缓存，但公开查询统一返回 `404 ACTIVITY_NOT_FOUND`；下线后原作者仍可按阶段 13 规则修改或删除已有 Review，这类写入不会继续标脏或生成新总结。生成期间下线时，Worker 不发布本轮结果，并以脱敏失败码结束已领取版本，历史结果保持不变。

通用链路不读取 `TicketOrder`、`TicketOrderItem`、支付、出票、核销、座位或锁座事实。Activity 票务、收藏、Nearby 和 Review Like 的任何变化均没有 AI Trigger 入口；点赞/取消点赞不推进 `dirty_version`。阶段 10～13 的 Activity 上下架、普通列表、详情、Nearby、收藏、Review 写入权限与评分聚合语义均保持不变。公开接口仍为匿名只读 `GET /api/ai/summaries/{targetType}/{targetId}`，现在严格允许 `MOVIE` 和 `ACTIVITY`，不新增生成、重试、管理或 Activity 嵌套评价接口。

#### 4.6.16 Step 14.5 验证与冻结结果

Step 14.5 定向测试 42 项通过，覆盖 Activity 提交后触发、事务回滚、Stream 消费、租约领取、评价不足、成功发布、缓存、匿名 HTTP 五状态、下线前后边界、下线期间发布保护、历史 Summary 保留，以及 Movie 既有闭环兼容。阶段 10～13 Activity 专项交叉回归 107 项通过，覆盖普通查询、详情、Nearby、收藏、评价写入/查询、并发与聚合；全量 546 项测试通过，0 failure、0 error、0 skipped。

真实联调使用隔离 MySQL 8.0.17、Redis 7 和随机 Spring Boot HTTP 端口，模型边界使用受控 `AiReviewSummaryClient` Stub，真实模型 HTTP Adapter 证据继续继承已冻结的 Step 14.3。完成不跳过测试的 `mvn clean package`、测试数据和 Redis 清理并提交后，本 Step 正式冻结；下一步只能单独进入 Step 14.6，不得在 14.5 回填跨目标并发压测、通知丢失对账、Venue/Post 总结或其他 AI 能力。

#### 4.6.17 Step 14.6 并发、恢复与补偿

Stage 14 的最终可靠性仍以 MySQL 任务行作为唯一任务事实。每个 Worker 使用进程信息和随机后缀生成独立消费者名，领取时由 `target_type + target_id`、状态、版本和租约条件 UPDATE 竞争；因此 Movie 与 Activity 即使数值 ID 相同也完全隔离，多 Worker、重复 Stream 消息或周期性重复补偿最多增加无害通知，不会重复发布同一任务版本。处理中评价再次变化只推进 `dirty_version`，旧 Worker 发布或失败时不会吞掉新版本，任务继续保持 `PENDING` 等待下一轮。

Redis Stream 的新消息由 Consumer Group 消费；超过最小空闲时间的 Pending 消息使用 `XCLAIM` 转移给当前消费者，成功完成或确认无可领取任务后才 ACK，异常中断则保留 Pending。独立 MySQL 对账周期扫描 `PENDING`、已到期 `RETRY_WAIT` 和租约已过期的 `PROCESSING`，重新发送只含目标类型和 ID 的通知。Redis Stream 仍不是任务事实：消息丢失由 MySQL 对账恢复，消息重复由条件领取消解；Redis 故障期间任务事实不丢失，恢复后由对账继续推进。

模型 `429` 限流、连接失败、超时和供应商 `5xx` 按通用重试策略进入 `RETRY_WAIT`，不可恢复输入或达到最大次数才进入 `DEAD`；错误码必须脱敏，API Key、完整 Prompt 和原始供应商响应不得写入日志、MySQL、Redis 或公开响应。公开查询读取 Redis 失败或缓存缺失时回源 MySQL 当前 Summary 指针，并尽力回填不可变结果缓存；缓存异常不改变公开状态和 MySQL 最终事实。上述恢复机制不新增 API，不改变 Movie/Activity 有效性、Review 写入、评分聚合、收藏、Nearby、Review Like 或任何票务语义。

#### 4.6.18 Stage 14 最终验证与冻结

Step 14.6 定向测试与 Stage 14 专项回归共 64 项通过，覆盖同 ID 跨目标隔离、8 Worker 并发唯一领取、租约过期接管、处理中版本推进、重复 Stream 消息、跨消费者 Pending 恢复、MySQL 对账、到期重试、活跃租约保护、模型限流/服务不可用以及 Redis 故障回源 MySQL。全量 552 项测试通过，0 failure、0 error、0 skipped；不跳过测试的 `mvn clean package` 为 `BUILD SUCCESS`。

真实模型联调使用隔离 MySQL 8.0.17、Redis 7、独立 HTTP 端口和服务端环境变量中的 DeepSeek 配置。相同数值 ID 的 Movie 与 Activity 分别以 3 条有效中文评价调用 `deepseek-flash`，均发布 Summary 版本 1，MySQL 任务进入 `SUCCEEDED`，匿名 GET 均返回 `READY`，两个公共 Redis 缓存存在且 Stream Pending 为 0。联调记录不保存或输出 API Key。测试 Movie、Activity、User、Review、AI Task/Summary、Stream 与缓存全部清理，Redis 最终 `DBSIZE=0`，隔离容器和应用进程均已停止。Stage 14 至此正式冻结；后续阶段尚未指定，不得回改阶段 1～14 已冻结业务语义，也不得顺带扩展 Venue/Post、推荐、审核或其他 AI 能力。

## 5. 待细化事项

下一版按以下顺序补齐：

1. 补齐除锁座、创建订单外其余接口的请求参数、响应字段和示例 JSON。
2. 完善业务错误码、字段命名、时间和金额精度。
3. 登录态、权限、限流、幂等键和防重放规则。
4. 锁座、支付、取消、退款的状态机与并发时序。
5. OpenAPI 3.0 文件，以及管理端接口和回调验签说明。

## 6. 与数据库的对应关系

核心资源对应 `sql/movielife.sql` 中的 `tb_user`、`tb_movie`、`tb_activity`、`tb_venue`、`tb_venue_staff`、`tb_showtime`、`tb_showtime_seat`、`tb_ticket_order`、`tb_ticket_order_item`、`tb_review`、`tb_post`、`tb_follow`、`tb_collection`、`tb_ai_review_summary`、`tb_user_behavior` 和 `tb_recommendation`。`tb_venue_staff` 是场馆级管理权限事实，不等同于普通登录态；TicketOrderItem 的 `used_time` 和 `used_by_user_id` 保存首次成功核销证据。评价点赞关系不单独建 MySQL 表，使用 Redis ZSet `review:like:{reviewId}`，点赞总数持久化在 `tb_review.like_count`。多态目标统一通过 `targetType/targetId` 或 `bizType/bizId` 表达，具体字段映射以后端 DTO 为准。
