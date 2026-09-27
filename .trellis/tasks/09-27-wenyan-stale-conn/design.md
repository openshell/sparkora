# 设计：发布超时阈值与探针超时解耦（09-27-wenyan-stale-conn）

## 1. 设计目标

把「客户端超时 < 服务端实耗」这一**唯一根因**修掉，并消除它带来的两个衍生物：
① 探针被发布超时牵连而长时间阻塞；② 超时错误文案不可读且不防重复草稿。

**不做**（初版设计中的过度工程，已砍）：`ChannelStatus` 五态分类、幂等/非幂等重试白名单、
`Connection: close`、引入 httpclient5。前三者建立在「连接复用缺陷」这一**已被证伪**的假设上；
本次不需要它们即可闭环。

## 2. 根因链

```
/publish 实耗 43.4s(实测,7 图 17MB)
        ↓
publishTimeoutMs = 30000(实际来自 application.yml 默认;.env 未配置)
        ↓
客户端 30s 抛 read timeout → 前端「发布失败」
        ↓
服务端仍在跑,~43s 成功写入草稿  ⇒ 草稿箱已有文章
        ↓
用户重试 ⇒ 又一篇重复草稿（脏数据自我放大）
```

## 3. 关键决策

### D1 发布超时：30000 → 180000，且显式写入 `.env`

- 依据：实耗 43.4s。选 180s = 实耗的 ~4 倍余量，覆盖「图更多 / 微信侧慢 / VPN 抖动」。
- 为什么不是 60s：43s 已用掉 60s 的 72%，图翻倍即逼近上限，缺少安全边际。
- 显式落 `.env`：该值此前只存在于 `.env.example`，实际生效的是代码默认值 —— 隐性配置是本次
  「阈值意外停在 30s」能长期存在的原因。`.env.example` 默认值同步改为 180000 并写明依据。
- **不改代码默认值**（`application.yml` 的 `${WENYAN_MCP_PUBLISH_TIMEOUT_MS:30000}` 兜底值
  同步提到 180000），避免漏配环境变量时又退回 30s 重现事故。

### D2 探针超时独立（必需，非可选）

`WenyanServerService` 构造期只建一个 `RestClient`，读超时为
`max(publishTimeoutMs, 5000)`，**探针与发布共用**。D1 把它调到 180s 后，通道不可用时
`publishOptions` 会阻塞 180s —— 比修之前更糟。

- `WenyanProperties` 新增 `verifyTimeoutMs`（默认 5000ms，`.env` 可选
  `WENYAN_MCP_VERIFY_TIMEOUT_MS`）。
- `WenyanServerService` 建**两个** `RestClient`：探针用（`verifyTimeoutMs`）、发布用
  （`publishTimeoutMs`）。抽出私有工厂 `buildRest(Duration readTimeout)` 去重。
- 备选否决：给 `get()` 按路径换超时需要 `ClientHttpRequestFactory` 代理，
  复杂度高于两个实例；且探针与发布的连接池本就该隔离（探针不复用发布的长时连接）。
- 探针超时**不加自动重试**：探针是纯只读，加重试会把「通道真不可达」的等待翻倍。
  单次 5s 超时已经足够快，且失败时用户刷新即可重探。

### D3 超时错误文案可读 + 防重复草稿提示

现状 `publish()` 的兜底 `catch (Exception e)` 把框架内部异常串直接透给用户：
`Error while extracting response for type [java.lang.String] and content type [application/octet-stream]`。

- 新增超时识别：沿用既有 `HttpStatusCodeException` 分支里已有的
  `e.getCause() instanceof SocketTimeoutException` 判定思路，抽成可复用方法，
  同样用于 `get()`/`postForData()` 的兜底分支。
- 超时文案：`发布超时(服务端可能仍在处理并已写入草稿,请先到公众号草稿箱确认,确认缺失后再重发)`。
- 保持**不自动重试**（非幂等）。用户确认后走既有「重发(覆盖草稿)」路径。
- 其余异常继续给中文摘要，但过滤掉 `Error while extracting response…` 这类框架串
  （无信息量且吓人）。

### D4 前端等待提示对齐实耗

`StepPublish.vue` 现有「正在渲染并通过 wenyan-server 写入草稿箱,约十几秒…」→ 改为
「约 1 分钟」。理由：实耗 43s，文案说「十几秒」会让用户以为卡死而刷新/重试，
而重试正是制造重复草稿的动作（R6）。

## 4. 兼容性与风险

| 风险 | 评估 | 处置 |
|---|---|---|
| 180s 过长，通道真挂时用户等太久 | **由 D2 隔离**：探针 5s 出结论并禁用按钮，用户不会走到发布 | AC4 验收 |
| 用户在 180s 内重复点发布 | 按钮 `loading` 态已禁用重复点击；且草稿可覆盖重发 | 回归确认 loading 态有效 |
| 43s 是否随文章变大而线性增长 | 是（图越多越久）。180s 覆盖到约 30 张图量级 | 在 `docs/spec/publish.md` 登记实耗与阈值依据 |
| 探针/发布两个连接池 | 探针低频（每次进发布页 1 次），连接数上限极小 | 接受 |

## 5. 回滚

改动集中在 `WenyanServerService`（双 RestClient + 超时文案）、`WenyanProperties`（新增一项）、
`application.yml`（兜底值）、`.env` / `.env.example`（值）、`StepPublish.vue`（一句文案）。
回滚 = 还原这些；新增配置项有默认值，不设环境变量亦可运行。无 DB/协议变更。

## 6. 规格同步点（Phase 3.3）

- `docs/spec/publish.md`：`/publish` 实耗 43s 与 180s 阈值的依据；超时=可能已写入草稿的
  语义；探针超时独立于发布超时；`publishEnabled` 仍由探针门禁。
- `docs/wenyan.md`：登记 `Keep-Alive: timeout=5` 实测 + **被证伪**的 stale 连接假设
  （避免后续重复排查），以及「客户端超时 < 服务端实耗 ⇒ 假失败 + 重复草稿」这一类事故模式。
- `.trellis/spec/backend/external-cli-integration.md`：新增约定
  「外部服务超阈值须按实测耗时设定并留余量；非幂等调用超时必须提示『可能已生效』，防重复执行」。
- `.trellis/spec/backend/error-handling.md`：禁止把框架内部异常串直接透给用户。
