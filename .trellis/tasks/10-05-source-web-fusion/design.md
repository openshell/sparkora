# design.md — F: 外部搜索与本地信源融合

> 父任务设计：`../10-05-self-hosted-sources/design.md`（§2.5 事实契约、§2.6 SOURCE 类型与权威分档）。
> 依赖：`10-05-source-domain-retrieval`（E 的本地信源进检索）+ `10-04-brief-retrieval-sources`（provider 契约）。
> 现状依据：`src/main/java/com/sparkora/deep/service/FactSheetService.java`、`SubAgentRunner.java`。

## 1. 边界

| 层 | 文件 | 改动 |
|---|---|---|
| 来源类型 | `deep/tool/SearchHit.java`、`KnowledgeSearchTool` | 新增 `SOURCE` 类型；本地信源命中不再一律 KB |
| 校验 | `deep/service/SubAgentRunner.java` | `validateFacts` 白名单扩 `KB\|WEB\|SOURCE` |
| 融合 | `deep/service/FactSheetService.java` | `SOURCE` 分级分支、本地优先、权威档 |
| 可观测 | `SearchMeta` / `fact_sheet` / 前端徽标 | 本地/外部占比、去重、置信档 |
| 文档 | `docs/spec/brief-generation.md` §5、`retrieval.md` | 字段级契约 |

**不做**：provider 接入/fanout/补检索（10-04）；采集/入库/切块（前置子任务）；LLM 语义融合。

## 2. SOURCE 类型（F-R1 / 父 §2.6）

现状冲突：
- `validateFacts:268-271` 硬白名单 `KB|WEB`，其他 type → 转 gap；
- `KnowledgeSearchTool.name()="KB"` + `SearchHit.kb(...)` → 本地信源一律标 KB、拿 0.9。

**决策**：
1. `SearchHit` 新增工厂 `SearchHit.source(title, modelName, docId, snippet, score, sourceType, authorityTier)`，
   `type="SOURCE"`。
2. `KnowledgeSearchTool`（或经 E 的检索链路）对 NEWS 域 `sourceType=user-source` 的命中返回 `SOURCE`；
   `byd-news` 仍返回 `KB`（**保持 BYD 现有等价**，避免抬升/改变 BYD 语义）。
3. `validateFacts` 白名单扩为 `KB|WEB|SOURCE`。**核验语义须写准**：本地命中（SOURCE/KB）**没有 sourceId/url**，
   无法像 WEB 那样逐条比对。故规则为：
   - `SOURCE` **无** url 且**无** sourceId → 直接接受（与现有 KB 事实同一路径，`validateFacts` 现对缺 type+无 url/sourceId 即放行）；
   - `SOURCE` **带** url 或 sourceId → 按 WEB 规则核验（sourceId 必须命中本次输入、URL/provider 匹配），
     **杜绝自造 URL 借 SOURCE 类型绕过校验**。
   `modelName`/`provider` 记信源标识用于溯源，不参与 URL 校验。
4. `FactSheetService` 的 `type` 默认值仍为 `KB`（缺省兜底不变），显式 `SOURCE` 走新分支。

> 默认不启用自建信源时没有任何 `SOURCE` 命中，故上述改动**零回归**。

**本地命中仅经 KB 工具进入（实现注意）**：`SubAgentRunner.research`（:88-95）只在 `toolsAllowed.contains("KB")` 时调
`kbTool.search(...)`，本地命中（含 BYD 新闻与 user-source）**全部经 `KnowledgeSearchTool` 这一条通道**。
故 F 只需改 `KnowledgeSearchTool` 按 `Citation.sourceType` 分流即可，无需新增工具或改 `SubAgentRunner` 的取数逻辑。

## 3. 权威分档（F-R4）

信源注册表 `authority_tier ∈ {official, industry, media, ugc}` → confidence：

| tier | confidence | 例 |
|---|---|---|
| official | 0.9 | BYD 官方、工信部、乘联会 |
| industry | 0.7 | 盖世等 |
| media | 0.5 | 行业媒体 |
| ugc | 0.5 | 论坛/自媒体 |

- **默认不启用分档**（`DEEP_SOURCE_AUTHORITY_ENABLED=false`）：全部 `SOURCE` 走单一保守档 0.7，
  避免配置缺失导致误判。
- 启用后按 `authorityTier` 取分；缺档回退 0.7。

## 4. 融合优先级（F-R2）

在 `FactSheetService.merge` 的置信分支（现状：`hasKb && hasWeb` → KB 胜；`sourceCount>=2` → MULTI；KB→0.9；else 0.4）**增量插入**：

1. **SOURCE 存在** → 作为本地权威来源参与：
   - `hasSource && hasWeb`：SOURCE 胜（本地为权威正文），WEB 降为 `alternatives` + warning（照 KB 胜 WEB 范式）；
   - `hasSource && hasKb`：**KB 仍胜**（通用知识库为清洗权威），SOURCE 记为来源之一（不删除）；
   - 纯 SOURCE：confidence = 权威档（§3）。
2. **`sourceCount>=2` 的 MULTI 判定**：`distinctSources` 仍按 `url+modelName` 去重；
   同源多通道（本地 BYD + 外部同篇 Tavily 命中）**只算 1 源**；同 provider 双端点（10-05-tavily）也只算 1 源。
   **盖世两形态须分计（实测 2026-10-05）**：`gasgoo-announce`（车企官宣）与乘联会**独立**，可参与交叉；
   `gasgoo-ranking`（排行页，来源基础可能派生自乘联会/上险）**不可计独立交叉**。

**`crossCounted` 如何到达 `FactSheetService`（P2，接线缺口 — 评审新增）**：`FactSheetService` 构造器现仅有 `ObjectMapper`
（:28），**无法访问信源注册表**。为使「`gasgoo-ranking` 不计独立交叉」可实现，须选一条：
- (a) F 注入信源注册表/一个只读 `CrossCountPolicy`，按 fact 的 `sourceType` 查 `crossCounted`；
- (b) E 在落库/注入时把 `crossCounted` 作为 fact `source` 的**布尔字段**透传（随 `sourceType` 一起），F 直接读取，**无需依赖注册表**。
- **推荐 (b)**：与 `sourceType` 同一条透传链（`UnifiedHit→Citation→SearchHit→fact.source`），保持 `FactSheetService` 无新增依赖，零回归最简。
无论 (a)/(b)，`distinctSources` 在计算前先剔除 `crossCounted=false` 的来源。

## 5. 跨源同 URL 去重（F-R3）

- 本地信源与外部 WEB 命中同一 URL（本地原文 vs 外部搜到同一篇）→ 按 `normalizeUrl` 去重，
  保留一次、`sourceCount` 不虚高、LLM 不重复注入。
- 落点：`FactSheetService.distinctSources` 已有 `url+modelName` 去重；补一条「同一 URL 跨 type」合并
  （避免本地 `SOURCE` + 外部 `WEB` 因 type 不同被当两条）。

## 6. 可观测（F-R5）

- `SearchMeta`（或 `fact_sheet`）增量：`localSourceCount` / `webSourceCount` / `dedupedSameUrl` / `authorityTierCounts`。
- 前端 `FactSheetSummary.vue` / `CitationList.vue` 补「本地/外部」徽标（增量，旧前端不读不报错）。

## 7. 风险

| 风险 | 缓解 |
|---|---|
| `SOURCE` 被拒或误当 KB | §2 白名单扩展 + 类型新增 + 反例单测（AC-F7） |
| 双端点/同源被算独立交叉 | §4 MULTI 判定按 url+modelName，多通道只 1 源（AC-F3） |
| 权威分档配置缺失误判 | 默认 off，统一保守档 0.7 |
| 本地优先误伤 KB | KB 仍胜 SOURCE（§4.1），仅 SOURCE 胜 WEB |
| 前端读取新字段报错 | 增量字段，旧逻辑不读（AC-F4） |

## 8. 回滚

默认不启用自建信源 → 无 `SOURCE` 命中，融合行为等价现状。融合开关回默认即回滚；无 DB 迁移。
