# MovieLife API 接口设计

## 1. 文档说明

- 版本：v0.2（接口清单修订版）
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
- 管理端：排片、影厅座位、活动和内容审核接口，后续单独拆分 `/api/admin`。

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
GET /api/activities/{id}/reviews        # 活动评价
GET /api/activities/{id}/showtimes      # 活动场次
```

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

评价目标统一使用 `targetType + targetId`，支持 `MOVIE`、`ACTIVITY`、`VENUE`。资源嵌套接口和 `GET /api/reviews?targetType=MOVIE&targetId=1` 可以同时保留，但后端必须统一调用 `queryReviews(targetType, targetId, page)`，不得按电影、活动和场馆复制三套业务逻辑。同一用户对同一目标默认只能保留一条有效评价；是否需要购票/核销后评价，在业务规则版确定。

### 3.6 收藏

```bash
PUT    /api/collections/{targetType}/{targetId}    # 确保已收藏
DELETE /api/collections/{targetType}/{targetId}    # 确保未收藏
GET    /api/collections                            # 当前用户收藏列表
GET    /api/collections/check                      # 查询指定目标是否已收藏
       ?targetType=MOVIE
       &targetId=1001
```

收藏统一使用 `targetType + targetId`。阶段 4 已支持 `MOVIE`、`POST`、`REVIEW`，`ACTIVITY` 在活动模块落地后接入。创建和删除接口均为幂等操作，数据库唯一索引作为并发兜底。

电影详情和活动详情的 VO 优先直接返回当前用户的附加状态，例如 `collected`、`liked` 等，减少详情页额外请求。`GET /api/collections/check` 保留为通用兜底接口，适用于批量预检查、非详情页场景或新目标类型接入。

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

`GET /api/showtimes/{id}/seats` 是公开的场次座位图查询接口，复用场次详情的有效性规则，只允许未来、启用、关联场馆有效的 `MOVIE` 场次。接口只执行查询，不创建座位状态、不锁座，也不操作 Redis。

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
POST /api/orders/{id}/cancel           # 取消未支付订单
POST /api/orders/{id}/refund           # 申请退款
GET  /api/orders/{id}/tickets           # 查看票券/取票码
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

当前实现进度截至阶段 8 Step 8.9：`POST /api/orders` 已正式开放，要求登录并统一返回 HTTP 200 的 `Result<CreateTicketOrderVO>`；`userId` 只从服务端登录上下文获取，请求体不能提交用户、金额、状态、订单 ID 或过期时间。上述 MySQL 创建事务、唯一 token 冲突后的事务外幂等恢复，以及 COMMIT 后复用阶段 7 Lua 的 Redis 条件清理已经完整接入 HTTP 链路；订单 ID 沿用数据库自增策略。清理最多同步尝试两次，失败只记录已提交订单的上下文并继续返回成功；同 token HTTP 重试在 Redis Key 已删除后仍根据 MySQL 返回原订单，缺失 Key 按成功处理，其他 token 拥有的 seatKey 不会被删除。

创建订单当前可能返回的业务错误包括：`400 INVALID_PARAM`、`400 INVALID_LOCK_TOKEN`、`403 LOCK_NOT_OWNED`、`403 ORDER_NOT_OWNED`、`404 SHOWTIME_NOT_FOUND`、`409 SEAT_NOT_AVAILABLE`、`409 ORDER_REQUEST_MISMATCH` 以及不暴露内部数据细节的 `500 INTERNAL_ERROR`。首次创建与幂等重试均返回 HTTP 200 和相同响应结构。

已消费 token 对应订单的 `userId` 不属于当前用户时返回 `403 ORDER_NOT_OWNED`；`showtimeId` 或座位集合与原订单不一致时返回 `409 ORDER_REQUEST_MISMATCH`。未消费 token 的 Redis Metadata 不存在、过期、场次或座位集合不匹配，以及任一 seatKey 缺失或不再等于当前 token 时，统一返回 `400 INVALID_LOCK_TOKEN`；Metadata 用户不一致继续复用 `403 LOCK_NOT_OWNED`。

`GET /api/orders` 已开放为当前登录用户订单列表。Query 参数为 `page`（默认 1，必须大于等于 1）和 `pageSize`（默认 20，范围 1～100），暂不支持状态筛选。响应 `data` 沿用统一分页结构 `list/total/page/pageSize`；列表项包含 `orderId`、`showtimeId`、电影、场馆和影厅的 ID/名称、`startTime`、原始订单 `status`、`totalAmount`、`expireTime`、`createTime` 和 `seatCount`。无订单时返回 HTTP 200 和空 `list`。

列表固定按 `create_time DESC, id DESC` 排序，只查询 `user_id=当前登录用户` 的订单。当前页订单明细、Showtime、Movie、Venue 和 Hall 均按本页 ID 集合批量查询，`seatCount` 由一次批量明细结果按 `orderId` 分组统计，不执行逐订单关联查询。

`GET /api/orders/{id}` 已开放为当前用户订单详情。响应除列表上下文字段外，还包含 `endTime`、`payTime` 和按物理座位 `rowNo ASC, colNo ASC` 排序的 `items`；每项包含 `seatId`、`rowNo`、`colNo`、`seatNo`、`seatType`、订单明细价格快照、`ticketCode` 和 `ticketStatus`。详情一次查询全部订单明细，并对全部 seatId 一次批量查询 Seat，不逐明细读取座位。

订单不存在或不属于当前用户均返回 `404 ORDER_NOT_FOUND`，避免泄露其他用户订单是否存在；非法订单 ID 返回 `400 INVALID_ID`。订单存在但 Showtime、Movie、Venue、Hall、明细或 Seat 关联数据缺失时返回不暴露细节的 `500 INTERNAL_ERROR`。两个查询接口只读取 MySQL，不读取订单 Redis Key，不修改订单状态，也不会根据 `expireTime` 临时修正 `UNPAID` 状态。

`POST /api/orders/{id}/cancel` 已在阶段 8 Step 8.8 正式开放，仅允许当前登录用户主动取消自己的 `UNPAID` 订单。订单不存在或不属于当前用户统一返回 `404 ORDER_NOT_FOUND`；`PAID`、`REFUNDED` 等非 `UNPAID/CANCELED` 状态返回 `409 ORDER_STATUS_CONFLICT`；已经为 `CANCELED` 时按幂等成功返回 HTTP 200，不重复释放座位或增加余票。

取消在独立 MySQL 事务中完成：先以 `SELECT ... FOR UPDATE` 锁定订单行并确认归属与状态，一次查询全部订单明细；随后按 `showtime_id + seatIds + status=HELD_BY_UNPAID_ORDER + lock_order_id=当前订单` 一次批量释放全部座位，恢复 `available_seats`，最后以 `id + user_id + status=UNPAID` 条件更新订单为 `CANCELED`。座位释放数必须等于明细数，余票和订单更新影响行数必须为 1，任一步异常都会整体回滚。余票恢复同时限制不能超过 `total_seats`。

订单行锁与订单状态条件更新共同保证并发重复取消只会执行一次释放；第二个请求在首个事务提交后看到 `CANCELED` 并幂等返回。这也为未来支付事务与取消竞争保留了互斥边界。主动取消只依据数据库真实状态，不因 `expireTime` 已过而拒绝仍为 `UNPAID` 的订单，也不实现自动超时状态修正。取消不读取或写入 `seat:lock:*`，因为释放的是 MySQL 正式订单占用而不是阶段 7 临时锁。

`UNPAID` 订单到达 `expireTime` 后由后台定时任务自动取消，不新增公开 HTTP 接口。任务每分钟限量扫描最多 100 个 `status=UNPAID AND expire_time<=NOW()` 的订单 ID，并按 `expire_time ASC, id ASC` 稳定排序；每个订单在独立事务中重新以 `SELECT ... FOR UPDATE` 锁定并复核状态与到期时间，然后复用主动取消的座位释放和余票恢复逻辑，最终仍写入 `CANCELED`，不新增 `EXPIRED` 状态。

扫描后已经取消、已经支付、尚未到期或已不存在的订单直接跳过。单个订单因明细、座位归属或余票一致性异常而回滚时，只记录该订单失败并继续处理本批其他订单；任务本身不持有整批事务。自动超时完全以 MySQL 为准，不读取或写入 Redis，也不会创建新的 `seat:lock:*`。

支付回调和退款回调预留内部接口：

```bash
POST /api/payment/notify               # 支付渠道异步通知
POST /api/refund/notify                # 退款渠道异步通知
```

客户端不得直接修改订单状态。订单状态暂定为 `UNPAID`、`PAID`、`CANCELED`、`REFUNDED`；支付、取消、退款接口必须校验状态机和幂等性。

订单明细继续采用“一座一票”：`tb_ticket_order_item.ticket_code` 和 `ticket_status` 属于具体座位明细，`ticket_code` 在支付成功后的出票阶段生成。当前单场次订单通过 `order.showtime_id + item.seat_id` 唯一定位 `ShowtimeSeat`，不额外引入 `showtime_seat_id`。

### 3.10 AI 评价总结

```bash
GET /api/ai/summaries/{targetType}/{targetId} # 读取已有 AI 评价总结
```

响应数据预留 `summary`、`pros`、`cons`、`suitableFor`、`warningPoints`、`sourceReviewCount` 和生成版本等字段。该接口只读取已生成结果，不在用户请求链路中同步调用模型。评价发生有效变化后，由异步任务重新生成并写入 MySQL，同时更新或失效 Redis 缓存。

### 3.11 行为采集与推荐

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

## 5. 待细化事项

下一版按以下顺序补齐：

1. 补齐除锁座、创建订单外其余接口的请求参数、响应字段和示例 JSON。
2. 完善业务错误码、字段命名、时间和金额精度。
3. 登录态、权限、限流、幂等键和防重放规则。
4. 锁座、支付、取消、退款的状态机与并发时序。
5. OpenAPI 3.0 文件，以及管理端接口和回调验签说明。

## 6. 与数据库的对应关系

核心资源对应 `sql/movielife.sql` 中的 `tb_user`、`tb_movie`、`tb_activity`、`tb_venue`、`tb_showtime`、`tb_showtime_seat`、`tb_ticket_order`、`tb_ticket_order_item`、`tb_review`、`tb_post`、`tb_follow`、`tb_collection`、`tb_ai_review_summary`、`tb_user_behavior` 和 `tb_recommendation`。评价点赞关系不单独建 MySQL 表，使用 Redis ZSet `review:like:{reviewId}`，点赞总数持久化在 `tb_review.like_count`。多态目标统一通过 `targetType/targetId` 或 `bizType/bizId` 表达，具体字段映射以后端 DTO 为准。
