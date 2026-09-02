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
GET /api/venues/{id}/reviews             # 场馆评价
```

`GET /api/venues/nearby` 是公开接口，支持 `city`、`type`、`x`、`y`、`distance`、`page`、`pageSize`，其中 `x` 为经度、`y` 为纬度，`distance` 和响应中的距离单位均为米。经度范围为 `-180～180`，纬度范围为 `-90～90`，查询距离范围为 `100～50000` 米。

普通模式只传场馆和坐标条件：服务端查询 `venue:geo:{city}:{TYPE}`，按距离升序召回 `venueId + distance`，再从 MySQL 批量补齐启用的场馆信息，并按 GEO 顺序返回。GEO Key 不存在时返回空列表；Redis GEO 查询异常时返回 `INTERNAL_ERROR`，当前不使用 MySQL 经纬度计算降级。

电影过滤模式额外传入 `bizType=MOVIE&bizId={movieId}`。`bizType` 和 `bizId` 必须同时存在或同时为空，当前仅支持 `MOVIE`，其他类型返回 `INVALID_BIZ_TYPE`；电影不存在返回 `MOVIE_NOT_FOUND`。服务端先进行 GEO 候选召回，再通过 `tb_showtime` 的 `biz_type=MOVIE`、`biz_id`、`start_time > NOW()`、`status=1` 条件筛选存在未来可售场次的场馆，过滤后分页，最终保持 GEO 距离顺序。同一场馆即使存在多个有效场次也只返回一次。

### 3.8 场次、座位与锁座

```bash
GET /api/showtimes/{id}                  # 场次详情
GET /api/showtimes/{id}/seats            # 场次座位状态
POST /api/showtimes/{id}/seats/lock      # 仅锁定座位，不创建订单
POST /api/showtimes/{id}/seats/release   # 主动释放未转订单的锁座
```

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
    "expireTime": "2026-09-01T16:20:00+08:00"
  },
  "requestId": "..."
}
```

`SeatLockService` 只负责在 Redis 中原子校验并占用 `showtimeId + seatIds`，锁记录必须绑定当前用户并设置有效期。释放接口通过 `lockToken` 释放当前用户尚未转为订单的锁。

释放锁座请求体固定为：

```json
{
  "lockToken": "xxx"
}
```

客户端不得提交任意 `seatIds` 作为释放依据。服务端必须从 `lockToken` 中解析并校验用户、场次和完整座位集合，只允许锁的持有者释放自己的有效锁；锁已转换为订单或已过期时，接口应保持幂等，不得影响其他用户的锁或已售座位。

核心规则：座位状态必须绑定 `showtimeId`，不能只锁定影厅物理座位；任意一个座位不可用时整次锁座失败；锁过期自动释放。客户端展示的座位状态允许短暂缓存，但创建订单和支付时必须再次校验服务端状态。

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

`TicketOrderService` 必须校验 `lockToken` 的用户归属、场次、座位集合和过期时间，并在一个受控事务中创建 `UNPAID` 订单及座位明细、关联锁座状态。相同幂等键或同一有效 `lockToken` 重试不得创建重复订单。订单创建成功后，Redis 临时锁转换为数据库中的订单占用状态；订单超时或取消后释放座位。

支付回调和退款回调预留内部接口：

```bash
POST /api/payment/notify               # 支付渠道异步通知
POST /api/refund/notify                # 退款渠道异步通知
```

客户端不得直接修改订单状态。订单状态暂定为 `UNPAID`、`PAID`、`CANCELED`、`REFUNDED`；支付、取消、退款接口必须校验状态机和幂等性。

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
