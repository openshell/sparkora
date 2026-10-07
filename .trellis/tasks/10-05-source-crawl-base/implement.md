# implement.md — B: 信源采集基座

> 依赖：`10-05-crawl4ai-transport`（C）的 `FetchTransport`/`HttpFetchTransport` 接口。Crawl4AI 实现未配置时仅采集 A 级 HTTP 源。
> 共享契约以父 `../10-05-self-hosted-sources/design.md` §2 与本文 `design.md` 为准。

## 实施顺序

1. **迁移 `V13__source_registry.sql`**（勿改已应用 V1–V11）
   - 建 `sparkora_source` / **`sparkora_source_channel`** / `sparkora_source_job`（结构见 design §2.2；**列表地址/`category`/`parse_rules` 落 channel 表**，一源多栏目）。
   - `ALTER TABLE sparkora_news ADD COLUMN source_id BIGINT REFERENCES sparkora_source(id)`（可空，存量 NULL=byd）。
   - **不新建 `sparkora_source_doc`/embedding**——复用 `sparkora_news_doc`（design §2.1 id 空间约束）。
   - 幂等：`CREATE TABLE IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS`。
2. **配置 `SourceProperties` + `.env.example`**（键见 design §7，URL 类 `_BASE_URL` 结尾）。
3. **实体/mapper**：`SourceEntity`、**`SourceChannelEntity`**、`SourceJobEntity`（`@TableName` 加 `sparkora_` 前缀由全局配置处理）。
4. **解析**：`SourceTableParser`（表格→行文本）+ `RssSourceClient`（jsoup xml）+ `SiteSourceClient`（选择器）。
5. **采集编排**：`SourceCollectService`（**遍历源的 enabled channels** → 每 channel list→detail→upsert→任务计数）、`SourceJobService`（仿 `NewsSyncJobService`，含 `markStaleRunningAsFailed`/`hasFreshRunning`）；幂等键 `(sourceId, channelId, externalId)`。
6. **动态调度**：`SourceScheduleService`（`TaskScheduler` + `CronTrigger`，map 以 sourceId 为键，启动注册 + 增删改热更新；执行时展开该源全部 enabled channels）；
   **发布窗口** `window_start_day`/`window_end_day` → 每日 cron + 窗口内采完即跳过（AC-B8）。
7. **控制器**：`SourceController`（design §6 八个端点，含 `/api/source-contents*` 内容查询，`R<T>` + `@PreAuthorize`）。
   **BYD 优先过滤**：工信部等源按 `parse_rules` 过滤出 BYD 条目全收，其他车企仅销量/投诉对比字段。
8. **配图转存（B-R10）**：新增 `SourceImageService`（正文图抽取 → 按 `detail_base_url` 解析相对链 → `ImageService.saveExternalImage`，`source="source"`、`sourceRef=派生 news_id`）；`ImageService.SOURCES` 白名单加 `"source"`；`saveExternalImage` 增「按传入基址解析」重载（`resolveBydUrl` 保留给 BYD 旧路径，零回归）；单图失败 warn 跳过不阻断。
9. **文档**：`docs/spec/knowledge/sources.md`（新增）+ `docs/spec/knowledge/news.md`（补「NEWS 为通用信源特例」）+ `docs/spec/image.md`（新来源值 `source`）。
10. **测试**（见下）→ 全绿后交 check。

## 实现期第一件核对事项

- **[核对] id 空间**：确认 `SourceCollectService` 非 BYD 源写的是 `sparkora_news` + `sparkora_news_doc`，
  `refId` 用的是 `sparkora_news_doc.id`，**没有任何地方**给新域单独分配向量 `refId`（否则撞 `docId`）。
- **[核对] 调度**：确认没有残留 `@Scheduled(cron=...)` 用于多源；确认 `SOURCE_COLLECT_ENABLED=false` 时
  不注册任何 trigger、不发起采集；确认窗口语义与单点 cron 行为区分。
- **[核对] 频控归属**：HTTP 通道无硬日限（走 `SOURCE_HTTP_MIN_INTERVAL_MS`）；Crawl4AI 同 host ≤2/天由 C 强制。
- **[核对] 内容 API**：`/api/source-contents*` 已实现且 `source=byd` 字段齐备（U 的硬前置）。

## 验证命令

```bash
mvn -q -DskipTests compile
mvn test                                    # 现有 842 例全绿
```

## 测试清单

- `RssSourceClientTest`：RSS/Atom 两种 feed 解析（`pubDate` 缺失容错）。
- `SourceTableParserTest`：HTML 表格 → 行文本，**列数/数值不丢**（对应 AC-B7）。
- `SourceScheduleServiceTest`：注册 → 改 cron → 注销（`ScheduledFuture` 取消）；禁用不注册；
  **发布窗口**：窗口内触发、成功后跳过、非窗口不触发（AC-B8）。
- `SourceContentApiTest`：`/api/source-contents` 分页/筛选；详情含正文/切块数；`source=byd` 字段齐备；viewer 可读、写 403（AC-B9）。
- `SourceChannelTest`：一源两栏目各自解析/去重/降级互不影响；同 `externalId` 跨栏目视为不同条目；源详情返回 `channels[]`；单栏目源等价旧行为（AC-B11）。
- `SourceImageServiceTest`：正文图抽取 → 转存调用（mock `ImageService`）断言 `source="source"`/`sourceRef`；**相对图链按 `detail_base_url` 解析**（断言域名非 `byd.com`）；单图失败不阻断（AC-B10）。
- `SourceCollectServiceTest`：幂等 upsert（同 externalId 不重复）；单条失败进 `failed_items` 不阻断；正文空仍入库。
- `SourceJobServiceTest`：陈旧 RUNNING 超 60 分钟置 FAILED；`hasFreshRunning` 跳过。
- 回归：`NewsSyncScheduler`/BYD 同步路径行为不变。

## 风险检查点（实现期）

| 检查点 | 何时 | 判定 |
|---|---|---|
| `sparkora_news.source_id` 可空、BYD 行 NULL | 迁移后 | 存量 BYD 检索对拍无差异 |
| 动态调度不并发（全局串行） | 调度实现后 | 无每源 `@Async`；同源运行中跳过 |
| 表格数值保留 | `SourceTableParserTest` | 断言行内数值齐全 |
| Crawl4AI 未配置时 B 级源降级 | 采集联调 | 记降级原因，任务 PARTIAL 不 FAILED |

## 回滚点

- 迁移：`git revert` V13 + `DROP TABLE sparkora_source*, ALTER ... DROP COLUMN source_id`（可空列，无数据损失）。
- 运行时：`SOURCE_COLLECT_ENABLED=false` 立即停止采集，不删数据。

## 交付物

- `V13__source_registry.sql`、`Source*` 代码、控制器、`sources.md` spec、单测全绿。
- 不包含：切块/嵌入（E）、UI（U）、Crawl4AI 客户端（C）。
