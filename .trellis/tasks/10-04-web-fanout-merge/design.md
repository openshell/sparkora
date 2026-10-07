# design.md — B: 多源并行聚合（PRIMARY_FANOUT + 跨源合并）

> 父任务：`../10-04-brief-retrieval-sources`。机制论证见父 `design.md` §2（为何不无条件全打 / 为何不放大 maxResults / 为何 sourceId 必须在合并后统一分配 / §2.5 SearXNG 质量门 / §2.3 合并规则）。
> 前置：`../10-04-serper-provider` 已完成（本任务依赖其 `WebProvider.SERPER`、`SearchTool.searchVertical`、`strategyLabel()` 三值）。

## 1. 边界

| 层 | 文件 | 改动 |
|---|---|---|
| 策略枚举 | `deep/search/SearchStrategy.java`（新增） | `FIRST_HIT` / `PRIMARY_FANOUT` |
| 配置 | `config/DeepProperties.java` | `webFanout`(默认 first_hit)、`webPrimaryProviders`(默认含 SEARXNG)、`webDenyDomains`、`webAllowDomains` |
| 路由 | `deep/search/WebSearchRouter.java` | `searchInternal` 分支：`FIRST_HIT` 原样包裹；`PRIMARY_FANOUT` 分组并行 |
| 合并 | `deep/search/WebResultNormalizer.java` | 新增 `merge(...)`：跨源去重/见证累加/排序/sourceId 统一分配；`normalize` 保留（单源路径用） |
| 契约 | `deep/search/WebSearchOutcome.java`、`SubAgentRunner.SearchMeta` | 增量 `usedProviders` / `providers`（兼容构造器） |
| 前端 | `ResearchProgress.vue` | 来源展示改读 `attempts`（增量字段，旧前端不报错） |
| 文档 | `docs/spec/brief-generation.md`、`retrieval.md`、`.env.example` | 同步 |

**不做**：补检索/预算/缓存（→ C）；`maxResults` 放大；query 改写；R4/R6 业务逻辑。

## 2. Fanout 流程（`PRIMARY_FANOUT`）

```
searchInternal(query, maxResults, snapshot, vertical):
  if snapshot==null || !snapshot.webAllowed(): return empty(DISABLED)   # 与 FIRST_HIT 共用
  if strategy == FIRST_HIT: return <既有逐 provider 短路逻辑，原样>    # 零回归
  # PRIMARY_FANOUT
  usable = [p in order if tool!=null && tool.available()]
  primary = [p in usable if p in cfgPrimarySet]
  if primary.isEmpty(): return <回落 FIRST_HIT 逻辑>                   # SearxNG-only 逐位不变
  # 并行（虚拟线程，各取满 maxResults）
  perProvider = primary.map(p -> normalize(call(p, query/vertical, maxResults)))
  hits = merge(perProvider, order, maxResults)                          # §3
  if hits not empty: return new WebSearchOutcome(hits, firstProvider, attempts, usedProviders)
  # primary 全空 → fallback 组（usable - primary）按原短路逻辑兜底
  return <fallback 短路>
```

- 并行用 `Executors.newVirtualThreadPerTaskExecutor()`（与 `DeepResearchService.doRunAsync` 同款）。
- 每个 provider 各取满 `maxResults`（并行无需分摊）；靠合并后统一截断控总量。
- 异常隔离：单 provider 异常 → `Attempt(REASON_ERROR, ok=false)`，不回传 `e.getMessage()`（沿用既有防密钥泄漏写法），其余 provider 正常。

## 3. 合并（`WebResultNormalizer.merge`）

- **跨源去重**：按既有 `normalizeUrl`（:74-92）去重，**首次出现的 provider 胜出**（order 靠前 = 优先级高）。
- **见证累加**：`witnessCount` = 同一 URL 被多个 **provider** 命中的次数。同 provider 多 endpoint 只累加 `witnessEndpoints`（D6），不进 `witnessCount`。
- **排序**：provider 在 `order` 中位次升序（稳定），provider 内保持原 rank。
- **截断**：合并排序后截到 `maxResults`（**不放大**）。
- **`sourceId` 统一分配**：把现有 `normalize` 的 `"W"+(out.size()+1)` 逻辑上移到 `merge` 末尾，对合并后列表统一编号。
  **正确性关键**：`validateFacts` 用 `byId.get(sourceId)` 严格比对 URL+provider（`SubAgentRunner:257-321`）；若两 provider 各从 `W1` 起号，引用会被误判 URL 不匹配而剔除。

> `merge` 不参与 confidence：`FactSheetService.distinctSources` 按 `url+modelName` 去重，同 URL 多源只算 1 源（父 design §2.3 澄清）。

## 4. SearXNG 质量门（B-R2a）

SearXNG 进 primary 组时先过滤，再进合并池：
- 域名黑名单 `DEEP_WEB_DENY_DOMAINS`（默认含 `bilibili.com`、`weixin.sogou.com`）；
- URL 类型：非正文页 `/video/`、跳转 `link?url=` 丢弃；
- 空 `title`+`content`、非法 URL 丢弃（沿用 `normalizeUrl`）。
- **只影响是否进合并池，不提升独立交叉计数**；对 non-SearXNG provider 不施加（零回归）。

## 5. 契约（全部向后兼容）

| 位置 | 现状 | 变更 |
|---|---|---|
| `WebSearchOutcome` | `usedProvider` 单值 | 保留语义不变；**新增** `usedProviders: List<WebProvider>`（兼容构造器，旧 3 参构造器保留） |
| `SubAgentRunner.SearchMeta` | `provider` 单值 | 保留；**新增** `providers: List<String>` |
| `WebProviderOrder.strategyLabel()` | 两值 | 已由 A 扩三值（含 SERPER → `PRIMARY_FANOUT`）；本任务快照在 fanout 开启时回落该标签 |
| `attempts[]` | `{provider,resultCount,latencyMs,fallbackReason,ok}` | 增量 `witnessTotal`（该 provider 命中中已有其他 provider 见证的数量） |

## 6. 配置与灰度

- `sparkora.deep.web-fanout`（默认 `first_hit`）；`.env.example` 新增 `DEEP_WEB_FANOUT`、`DEEP_WEB_PRIMARY_PROVIDERS`、`DEEP_WEB_DENY_DOMAINS`、`DEEP_WEB_ALLOW_DOMAINS`。
- **运行时 `sparkora_setting` 不放开该开关**（直接决定成本）。
- 灰度：`DEEP_WEB_FANOUT=primary_fanout` 单开 → 观察 `attempts` 多源命中率与 `fact_sheet` 的 `MULTI` 占比。

## 7. 风险

| 风险 | 缓解 |
|---|---|
| R2 单独上线对 MULTI 提升有限（父 §2.3） | 与 C 同批验收；AC-B7 为观测性验证而非强断言 |
| 两 provider 各从 W1 起号 → 引用误剔 | `sourceId` 在 merge 后统一分配（§3，AC-B4） |
| 多源条数上升稀释 LLM 抽取 | `maxResults` 不放大；合并后截断 |
| provider 异常/超时拖累整轮 | 并行 + 异常隔离；primary 全空时 fallback 兜底 |
| SearXNG 噪声污染 | 质量门（§4，AC-B8） |

## 8. 回滚

不设 `DEEP_WEB_FANOUT`（或置 `first_hit`）即回到单 provider 短路，行为逐位等价现状。无 DB 迁移。
