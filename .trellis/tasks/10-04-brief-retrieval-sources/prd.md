# 简报检索来源增强（Serper + 多源质量编排）

## Goal

在现有深度简报研究链路（`SubAgentRunner`：KB + WEB）基础上接入 **Serper** 作为新增 WEB provider，并引入「有规划、有质量」的搜索编排层，提升外部信息的**覆盖广度、正文深度、来源可信度与可追溯性**，从而提高简报与正文质量。

用户明确授权：**可大量调用** Tavily 与 Serper（第三方付费额度充足），但要求**调用有规划、有质量**，不得无脑堆量。

> 本任务是「检索来源增强」三条路线中 **WEB 抓取源升级 + 多源调用质量编排** 的落地。另两条路线（混合检索 BM25+RRF、自有无形资产 ARCHIVE 域）不在本任务范围，见 Out of Scope。

## Background / Confirmed Facts（仓库证据）

深度研究链路现状（`docs/spec/brief-generation.md` §4–§5、`docs/spec/retrieval.md`）：

- **WEB provider**：`WebSearchRouter`（`src/main/java/com/sparkora/deep/search/WebSearchRouter.java:26`）按 `WebProvider` 顺序逐个尝试，**首个有效命中即采信停止**；枚举仅 `TAVILY`/`SEARXNG`（`WebProvider.java:7-12`），MVP **不支持双源聚合**。
- **工具抽象**：`SearchTool`（`src/main/java/com/sparkora/deep/tool/SearchTool.java:10`）；`TavilySearchTool` 覆写 `extract()` 走 `/extract` 补正文；`SearxngSearchTool` 无 extract。
- **调用配额**：每子代理 `webQuota=max(1,8/n)`（单 provider 返回条数上限，非全程预算），`DEEP_MAX_AGENTS` 默认 6。
- **gap 驱动**：KB 命中车型域权威块且非背景题→跳过 WEB；**仅背景题**对 top 1–2 URL 调 `/extract` 补正文（工具层截断 `DEEP_WEB_CONTENT_MAX_CHARS`，默认 2000）；参数题不补抓。
- **结果治理**：`WebResultNormalizer` 做协议校验/URL 规范化/去重/截断/分配 `sourceId`；`SubAgentRunner.validateFacts` 只接受引用本次输入 `sourceId` 的事实。
- **事实聚合**：`FactSheetService.merge` 按 claim 近似归并；纯 WEB 单源置信 0.4+warning，多源交叉 0.85，KB 0.9。
- **配置**：`DeepProperties`（prefix `sparkora.deep`）：`searchWebEnabled`/`tavilyApiKey`/`webProviderOrder`/`webContentMaxChars`/`maxAgents`/`researchTimeoutMs`；运行时覆盖 `sparkora_setting`（`webSearchEnabled`/`webProviderOrder`）。`SearchToolCallbacks` 能力层（`deep/tool/SearchToolCallbacks.java:37`）**尚未接入主流水线**。
- **外部情报**：Bing Search API v7 已于 2025-08-11 下线；Spring AI 官方 hybrid search / rerank 仍为 open issue，无现成 API。
- **第三方中转实测（2026-10-04，`curl` 直测）**：
  - **Serper 中转 `https://search.604020.xyz/serper` —— 可用**（key 已在 `.env` 的 `SERPER_API_KEY`，值不在此复述）。`/search` 连续 10 次 10/10 成功、1.3–1.5s；`/news`、`/scholar`、`/images` 均 200；`gl=cn`/`hl=zh-cn` 生效。**该中转仅授权 serper**，`/tavily/*` 返回 403 `{"detail":"this API key is not permitted to use tavily"}`；错 key 401 `{"detail":"invalid API key"}`。**`num=20` 实测只回 10 条**（`credits=1`）→ 单次硬上限 10 条。中文召回质量良好（秦PLUS 价格 → autohome.com.cn / byd.com 官方；销量 → 新浪财经 / news.cn / stcn.com / d1ev.com）。
  - **Tavily 中转 `https://tavily.ivanli.cc/api/tavily` —— 不可用，不采用**（key 已注释在 `.env`，未启用）。首两次 `/search`(200,3.27s) 与 `/extract`(200,2.10s) 响应结构与官方 Tavily 完全兼容（`results[].title/url/content` + `raw_content`），随后持续 **554 空响应体恒定 16.2s**（含 `query=test`），最终 **443 连接被拒**（DNS 117.139.140.63 EdgeOne 仍解析）。**注意与本仓超时的交互**：中转需 16.2s 才失败，而 `TavilySearchTool.java:56` readTimeout=15s → 客户端必然先超时，表现为 `REASON_ERROR`，排查时易误判为本地网络问题。
  - **Serper `/news` 垂直每条带 `date`（相对时间，如「2小时前」）+ `source`（发布方，如「新浪网」）+ `imageUrl`；但 `/search` 的 `organic[]` 只有 `link/position/snippet/title`，无 `date`。** → 时效/来源维度**仅在 news 垂直下可得**，不需自建域名白名单。
  - 结论：primary 组 = **Tavily 官方 key（`.env` 中现有 `TAVILY_API_KEY`，值不在此复述）+ Serper 中转**，仍是两个真交叉源（`name()` 不同 → `FactSheetService.distinctSources` 可识别为 2 源）。

## Requirements

**MVP 主抓手（Q1 已定：采纳 B 方案）**

- **R1 Serper provider 接入**：新增 `SerperSearchTool`（实现 `SearchTool`，`name()="SERPER"`，`POST {apiBase}/search`，认证走 **Header `X-API-KEY`**（与 Tavily 的 body `api_key` 不同，须在 `SearchTool` 接口注释写明，避免后来者误以为 body 通用）；沿用 `ClientHttpRequestFactoryBuilder.jdk()` + 5s 连接 / 15s 读超时口径）；纳入 `WebProvider` 枚举、`WebProviderOrder` 解析与 `WebSearchRouter` 路由；密钥走 `DeepProperties.effectiveSerperKey()`（property→env→字段兜底链，`.env` 新增 `SERPER_API_KEY`/`DEEP_SERPER_API_KEY`，**值已落 `.env`，不在本仓复述**）。
  - **R1a `apiBase` 必须可配置**（实测硬约束，非可选）：官方 `https://google.serper.dev` vs 中转 `https://search.604020.xyz/serper`，中转特有的 `/serper` 路径前缀意味着换端点必须改配置而非改代码。新增 `sparkora.deep.serperApiBase` + `effectiveSerperApiBase()`，默认官方端点。
  - **R1b 地域参数默认中文**（实测 `gl=cn&hl=zh-cn` 回显生效，中文召回命中 autohome/byd.com/新浪财经/news.cn；英文默认会削弱中文召回）→ 默认带 `gl=cn`/`hl=zh-cn`，可配覆盖。
  - **R1c 垂直支持**：`searchVertical(query, vertical, maxResults)`，`vertical ∈ {web, news}`，默认 `web` 保持现状。时效题（命中「最新/近期/现在/今年/当前/动态/发布」等信号）路由 `news`，其余走 `web`；R3 的补检索目标（`confidence<=0.4` 的 param 类）默认走 `web`。`/news` 与 `/search` 同价（`credits=1`），无额外费用。
  - **R1d per-provider 结果上限**：实测 `num=20` 只回 10 条（单次硬上限 10）→ `attempts.resultCount` 必须记**实际**返回数而非请求数，否则预算核算失真。
  - **R1e Tavily 侧对称**：`TavilySearchTool.java:30` 同样硬编码 `DEFAULT_API_BASE`（仅包级测试构造器可注入，`:49`）→ 同批加 `sparkora.deep.tavilyApiBase` + `effectiveTavilyApiBase()`。纯对称性收益（实测唯一 Tavily 中转已挂），非必需，但两 provider 配置方式不一致会成为长期维护陷阱。
- **R2 有规划的多源调用**：新增 `SearchStrategy { FIRST_HIT, PRIMARY_FANOUT }`（默认 `FIRST_HIT`）；`PRIMARY_FANOUT` 下仅对 **primary 组**（非 SEARXNG 的已配置 provider）并行调用，**fallback 组（自建/公共实例）保持短路兜底**，primary 全空时才按原逻辑兜底。跨源合并按 `normalizeUrl` 去重、累加 `witnessCount`、按 order 位次稳定排序，**`maxResults` 不放大**，合并后统一分配全局唯一 `sourceId`（否则 `validateFacts` 的 URL/provider 严格比对会误剔）。设计论证见 `design.md` §2。
- **R3 覆盖驱动的多轮补检索**：Round 1 现状不变 → `fact_sheet.merge` 后由**纯函数零 LLM** 的 `selectFollowupTargets(fact_sheet, maxFollowups)` 按三类规则（检索类 gap / `confidence<=0.4` 且 `kind=param` 的 entry / 既无 entry 也无 gap 的 keyQuestion）识别缺口 → 每目标 1 次多源 fanout + ≤1 次 LLM 增量抽取，**复用同一 Note 增量写回**（不新建子代理、不重跑 plan），query 复用 `webQuery` 确定性拼装。合并完成后才调 `generateFromFactSheet`。设计论证见 `design.md` §3。
- **R5 调用预算与治理**：`WebCallBudget` 三级预算（per-round / per-brief，per-provider 沿用 `maxResults` 语义）；跨轮次按规范化 URL 去重（防虚高 `sourceCount` + 省 token）；批次内进程内 TTL 缓存（**不做跨批次持久缓存**，避免返回陈旧证据反噬质量）；`SearchMeta.attempts` 增量 `dedupedCount`/`cacheHit`/`budgetExhausted` 可观测；超限或异常**降级不阻断**，已获证据照常入册。设计论证见 `design.md` §4。

**预留增强项（本轮只留配置位与接口，默认 `off`，不实现业务逻辑）**

- **R4a 来源权威度分档**：`sparkora.deep.web-source-grading=off`。**维持默认 off**——实测发现 `/news` 的 `source` 是**媒体名**（新浪网）而非域名，做权威分档仍需人工维护「名称→分档表」，与原域名白名单是同一易腐问题；分档表设计为外部配置而非硬编码。详见 `design.md` §5.2。
- **R4b 时效性新鲜度**（**由实测上调为「可选实现，成本极低」**）：解析 `/news` 每条自带的 `date`（相对时间，如「2小时前」）做新鲜度偏好。**关键约束：`/serper/search` 的 `organic[]` 无 `date`，仅 `/news` 垂直可得**，故只在 R1c 的 news 路由下成立，不得宣称全局时效能力。详见 `design.md` §5.0。
- **R6 正文级抓取扩展**：`sparkora.deep.web-extract-policy` 取 `off`/`background-only`(默认,现状)/`param-cross`；不做「全部补抓」——extract 额外付费 + 延迟，参数型 snippet 通常已含数值，`param-cross` 精准命中 R3 交叉验证目标即可。实测依据：官方 Tavily `/extract` 返回 `raw_content` 正常（首测拿到 2381 字符），但唯一 Tavily 中转已不可用，故 extract 仍走官方端点。

**贯穿项**

- **R7 契约与前后端兼容**：`toolHealth` 扩展 `SERPER`；`WebSearchOutcome.usedProvider` 保留 + 新增 `usedProviders`；`SearchMeta.provider` 保留 + 新增 `providers`；`WebProviderOrder.strategyLabel()` 从两值扩为三值（新增 `PRIMARY_FANOUT`，否则加 SERPER 后 order 首元素非 SEARXNG 一律显示 `TAVILY_FIRST`，运维健康视图被误导）；前端来源展示改读 `attempts`。record 加字段一律用兼容构造器（沿用 `Note` 4 参、`WebHit` 5 参既有做法）。
- **R8 顺带修既有缺陷**：`WebSearchRouter.extract` 现按 `WebProvider.values()` 枚举声明序遍历且**不受 `webAllowed` 约束**（`WebSearchRouter:110-124`）→ 改为按快照 order 遍历 + 尊重 `webAllowed`。默认配置（order=`TAVILY,SEARXNG`）下行为等价，但因触及付费路径必须配单测。

**明确不做（本轮范围外）**

- **R9 运行时开关面**：只放开 provider order（沿用现状），**不放开** fanout / followup / budget——这三项直接决定成本，运营误开即账单失控。

## Acceptance Criteria

- [ ] **AC1（零回归基线）** 不设任何新配置时，深度研究行为与现状**逐位等价**：单 provider 短路、每 agent 1 条上限、仅背景题补正文、`rag_status` 四态语义不变；`mvn test` 现有 510 例全绿 + `npm run build` 通过。
- [ ] **AC2（Serper 可用）** `SERPER_API_KEY` + `SERPER_API_BASE_URL` 配置后，深度研究可经 `WebSearchRouter` 命中 Serper 结果（`name()="SERPER"`、Header `X-API-KEY` 认证、5s/15s 超时口径与 Tavily 一致）；未配置时 `toolHealth` 显示 `UNCONFIGURED`，不影响 Tavily/SearxNG；**仅改配置即可在官方端点与中转之间切换**，无需改代码。
- [ ] **AC2a（垂直路由）** 时效信号问题走 `/news` 垂直并能拿到 `date`/`source` 字段；非时效问题走 `/web`；两者均为默认行为向后兼容（不配置时全走 `web`）；`/news` 与 `/web` 同价（`credits=1`），调用量不因此翻倍。
- [ ] **AC3（多源聚合正确性）** `PRIMARY_FANOUT` 下：primary 组并行且单 provider 异常不影响其他；primary 为空回落 `FIRST_HIT`；跨源去重生效且 `witnessCount` 正确；**合并后 `sourceId` 全局唯一**，LLM 引用任意 `sourceId` 都能通过 `validateFacts` 的 URL+provider 严格比对（构造反例单测）；`maxResults` 不放大。
- [ ] **AC4（交叉验证收益可观测）** 在同一项目上以 `FIRST_HIT` vs `PRIMARY_FANOUT` 各跑一次，`fact_sheet` 中 `MULTI` entry 占比 / `sourceCount>=2` 簇数**应呈上升趋势**；`SearchMeta.providers` 与 `attempts[].witnessTotal` 能完整列出参与 provider 与交叉见证数。**本项为观测性验证而非强断言**——单源短路解除只是抬升了「不同 URL 同 claim 同时召回」的概率，该概率还取决于 query 措辞多样性（由 R3 提供），故不得作为 R2 的独立交付门槛（理由见 `design.md` §2.3）。
- [ ] **AC5（覆盖补检索）** Round 1 有缺口时，`selectFollowupTargets` 正确选出 ≤`maxFollowups` 个目标并发起补检索，结果并入 `fact_sheet`；无缺口时不发起任何额外调用；补检索**不新建子代理、不重跑 plan**（`research_plan` 与 Round 1 落地的 notes 不被覆盖）；轮次与总预算有上限，超限停止。
- [ ] **AC6（预算与去重）** `WebCallBudget` 三级上限生效，超限后拒绝新调用并置 `budgetExhausted`；`per-round` 生效值取 `max(配置值, maxAgents × |primary 组|)`，**保证 n=6 + primary=2 时 Round 1 的 12 次调用不被预算掐死**；跨轮重复 URL 不重复注入 LLM 且不重复计入 `sourceCount`；批次内相同 `provider+query+maxResults` 第二次命中缓存（`cacheHit` 计数可见）；批次结束缓存全部释放；`attempts.resultCount` 记**实际**返回条数（覆盖 provider 返回少于请求数的情形）。
- [ ] **AC7（降级不阻断）** provider 超时 / rate limit / 空结果 / extract 失败 → 相应 warning + 回落，**不中断研究**；`webAllowed=false` 时不发任何 search/extract 请求（含 `extract`）；`budgetExhausted` 场景下已获证据照常进入 `fact_sheet` 并正常生成简报。
- [ ] **AC8（契约与前端）** `toolHealth` 含 `SERPER`；`strategyLabel()` 三值正确；`research` 启动返回体与 `SearchMeta` 的新增字段为**增量**（旧字段名与语义不变）；前端来源展示读取 `attempts`，旧前端不读新增字段也不报错。
- [ ] **AC9（默认全关）** `webFanout=FIRST_HIT`、`webFollowup=off`、`webSourceGrading=off`、`webExtractPolicy=background-only`、`maxTotalWebCalls=20`、`webCallBudgetPerRound=12` 为默认值；`.env.example` 已同步新增全部 `SERPER_*` / `DEEP_SERPER_*` / `DEEP_TAVILY_API_BASE_URL` / `DEEP_WEB_*` 配置项，且 **URL 类配置键一律以 `_BASE_URL` 结尾**（否则被 `secret-guard` 插件误判为凭据）；运行时 `sparkora_setting` 未新增 fanout/followup/budget 开关。

## Out of Scope

- 国内搜索 provider（博查 Bocha / 秘塔 Metaso / Search1API / 智谱 web 搜索）——用户本轮只选 Serper。
- 混合检索（pgvector `tsvector` + BM25 + RRF）与本地 cross-encoder 重排（Spring AI 官方 hybrid/rerank 仍为 open issue，须自写原生 SQL）。
- 自有无形资产 ARCHIVE 向量域（已发布文章入库）。
- DailyHotApi 热榜接入（热榜聚合非事实检索源，只适合选题热点）。
- `CRAWL4AI_*` 独立抓取服务接入（正文能力由 provider `extract` 承担）。
- 现有 `rag_status` 四态语义与 `retrieveForGeneration` 本地检索打分/配额规则变更。
- R4 来源分级（权威度子项）、R6 补抓扩展的**业务逻辑实现**（本轮仅留配置位，默认 off）。
- **Tavily 中转（结论已修正，2026-10-05 复测）**：`tavily.ivanli.cc` 早前实测 554→连接拒绝；**复测为「可连通、间歇性慢失败」——失败恒定约 16.5s、`http=554`、0B**，且与 `TavilySearchTool` 的 15s readTimeout 冲突（客户端必先超时）。故本轮仍**不启用**（官方 key 足够），但结论从「不可用」修正为「**可连通但受并发限制，只作低并发 fallback**」。`.env` 注释保留 key 与端点备查。若后续启用，须为其配独立短 timeout 与并发度 1（见下方 provider 分层）。

## Key Decisions（已全部决策，无阻塞项）

| # | 决策 | 内容 | 决策者 |
|---|---|---|---|
| D1 | MVP 编排范围 | **B 方案**（核心编排优先）：R2 多源并行聚合 + R3 覆盖驱动多轮补检索 + R5 预算/去重/缓存为主抓手；R4a/R4b/R6 只留配置位默认 off。A（全量）会把摊薄重点并放大回归面；C（仅 R1+R2）解不掉「缺口从不补搜」。论证见 `design.md` §1 | 用户 |
| D2 | 搜索 provider 选择 | **Serper**（中转）+ 现有 Tavily（官方端点）。不接博查/秘塔/Search1API/智谱 | 用户 |
| D3 | 调用预算档位 | **保守档**：`maxTotalWebCalls=20` / `webCallBudgetPerRound=12` / `maxFollowups=2`。`per-round` 由初版提案 8 修正为 12——若用 8，Round 1 在 n=6 时提前 `budgetExhausted`，主抓手 R2 反被自己的预算掐死；生效值取 `max(配置值, maxAgents × |primary 组|)` 保护性下限 | 用户（档位）+ 实测修正（数值） |
| D4 | 交付拆分 | **父任务 + 3 个可独立验收子任务**，见下方 Delivery | 用户 |
| D5 | Tavily 中转 | **采用，中转优先、官方兜底**（用户 2026-10-05 指令反转原「不采用」）。中转可连通但间歇性慢失败（恒定约 16.5s），故配**独立短超时 + 快速兜底 + 质量门**；Tavily **统一为一个来源**（provider 名恒 `TAVILY`）；不限额度。落地见子任务 `10-05-tavily-endpoint-priority` | 用户指令 + 实测证据 |
| D6 | provider 身份 × endpoint 实例 | **同一 provider 的多个 endpoint 共享 provider 身份，不得提升独立来源计数**（仅记 `witnessEndpoints` 供调度/健康）。`usedProviders`/`SearchMeta.providers` 为跨树契约，`10-05-source-web-fusion` 依赖之 | 评审（2026-10-05），须回填 design |
| D7 | SearXNG 参与召回 | **参与 primary fanout（可配，默认含）**，但须过质量门（域名黑名单/正文页过滤）后才进合并池；不提升独立交叉计数。用户 2026-10-05：不介意多召回，但不得污染生成 | 用户 2026-10-05 |

## Delivery（任务地图）

父任务 `10-04-brief-retrieval-sources` 持有需求集、设计论证与跨子任务验收，**本身不是实现目标**。

| 子任务 | 交付内容 | 前置依赖（显式） |
|---|---|---|
| [`10-04-serper-provider`](../10-04-serper-provider/prd.md) | R1 + R1a–R1e：Serper provider、可配置端点、垂直路由、中文地域参数、契约扩展 | **无，可立即开工** |
| [`10-04-web-fanout-merge`](../10-04-web-fanout-merge/prd.md) | R2：`SearchStrategy` + primary/fallback 分组并行 + `merge` 跨源合并 + `usedProviders` 契约 | `10-04-serper-provider` |
| [`10-04-web-followup-budget`](../10-04-web-followup-budget/prd.md) | R3 + R5 + R8：缺口识别纯函数 + Round 2 增量写回 + 三级预算 + 跨轮去重 + 批次内缓存 + `extract` 缺陷修复 | `10-04-web-fanout-merge`（并间接依赖 A） |
| [`10-05-tavily-endpoint-priority`](../10-05-tavily-endpoint-priority/prd.md) | Tavily 双端点：中转优先 + 官方兜底 + 独立超时 + 质量门 + provider 身份固定 `TAVILY`（用户 2026-10-05 指令） | `10-04-serper-provider`（A 的 `apiBase` 可配置） |

依赖关系已写入各子任务 `prd.md` 的「依赖」小节，**不依赖任务树位置隐含**。每个子任务各自持有可单测的验收标准与 `implement.jsonl` / `check.jsonl` 上下文清单。

## Notes

- 各 provider 中转的完整实测证据见上方「第三方中转实测」条目与 `design.md` §0.1；**任何真实密钥都不写入本仓**（已在 `.env`，其路径被 `.gitignore` 覆盖）。
- 变更 Tavily/Serper 相关文档时须同步 `docs/spec/brief-generation.md`、`docs/spec/retrieval.md` 与 `.env.example` 三处。

