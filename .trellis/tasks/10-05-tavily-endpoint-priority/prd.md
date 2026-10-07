# prd.md — Tavily 双端点：中转优先 + 官方兜底 + 质量门

> 父任务：`10-04-brief-retrieval-sources`（外部搜索 provider 契约持有者）。
> 依赖：`10-04-serper-provider`（A 已把 `TavilySearchTool` 的 `apiBase` 改为可配置 `effectiveTavilyApiBase()`）。
> 本文是用户 2026-10-05 指令的落点：Tavily 统一为一个来源；**中转优先、官方兜底**；不限额度；保障有质量的结果进简报。

## Goal

让 Tavily provider 在**端点层**做故障切换：优先走中转端点（无限额、可多调），失败/超时/质量不足时自动切官方端点（限额度、但稳）；
**两个端点仍共享 provider 身份 `TAVILY`**（不因多端点提升独立来源计数）；为两个端点各配独立超时，并对进入证据池的结果做质量门。

用户价值：在不牺牲「有质量结果进入简报」的前提下，用便宜且不限量的中转承担主流量，官方只作兜底。

## 依赖（显式声明）

- **前置**：`10-04-serper-provider`（A 的 `tavilyApiBase` 可配置 + `SearchTool` 契约）。
- **后继**：`10-05-source-web-fusion` 依赖本任务的「provider 身份固定为 TAVILY」契约。
- **共享契约**：`usedProviders`/`SearchMeta.providers` 由 `10-04-web-fanout-merge` 定义；本任务**不新增** provider 名。

## Requirements

- **T-R1 双端点配置**：`DeepProperties` 支持 **Tavily 中转端点**（`DEEP_TAVILY_API_BASE_URL` + `DEEP_TAVILY_API_KEY_HIKARI`）
  与官方端点（`TAVILY_API_KEY`）；两个端点均可选、可单独禁用。**两者 `name()` 均为 `TAVILY`**。
  - 依据：`.env:119-121` 已写明「两个 Tavily 端点 `name()` 同为 TAVILY，`FactSheetService` 按来源去重会合并」——本任务把它从注释变成实现。
- **T-R2 端点优先级与 failover（中转优先）**：Tavily 工具内部按 `relay → official` 顺序尝试：
  - 中转返回**有效命中** → 采用，不调用官方；
  - 中转**失败/超时/空/全部非法 URL** → 记录原因后**自动降级到官方**；
  - 两都不可用 → 返回空（交由 `WebSearchRouter` 既有策略继续下一个 provider）。
  - **不限额度**：中转与官方均不做调用次数封顶（用户明确「不用在乎额度消耗」）；但仍**不得无脑重试**——
    每个端点每次研究最多调用一次（沿用 `WebSearchRouter` 既有「每 provider 每轮一次」语义）。
- **T-R3 端点独立超时（关键）**：实测中转失败**恒定约 16s**，而 `TavilySearchTool` 现为统一 `readTimeout=15s`
  （`:55-56`）→ 中转失败时客户端**必先超时**，永远等不到 `554` 状态，排查被误导。
  - 中转端点用**较短 readTimeout**（默认 8s，可配），快速失败后切官方；
  - 官方端点用**较长 readTimeout**（默认 30s，官方稳定但 `/publish`/`extract` 可能慢）；
  - connectTimeout 统一 5s。
  - 实现方式：每个端点一个 `RestClient`（或按端点选择 requestFactory），不共用同一超时。
  - **`extract` 超时不得被牵连**：`extract` 与 `search` 现共用 `RestClient`，须为其保留独立 `RestClient`（read 15s，或 `DEEP_TAVILY_EXTRACT_READ_TIMEOUT_MS` 默认 15000），使官方 `search` 的超时调整不影响 `/extract`。
- **T-R4 provider 身份不变（防虚高）**：中转与官方命中均 `SearchHit.web("TAVILY", ...)`。
  `FactSheetService.distinctSources` 按 `url+modelName` 去重 → **同一 URL 被两端点命中只算 1 源**，不得抬升 `MULTI`。
  仅可另记 `witnessEndpoints`（中转/官方）供**可观测**，**不参与** confidence。
- **T-R5 质量门（用户强调：保障有质量的结果进简报）**：
  - **端点级**：中转命中需通过最低质量校验才算「有效」，否则视为失败并切官方。校验至少含：
    ① `url` 可规范化且为主流 scheme；② `title` 与 `content` 非同时为空；③ 非已知噪声域（如与主站无关的聚合/跳转域）；
  - **结果级**：沿用既有 `WebResultNormalizer`（非法 URL 丢弃 / 规范化去重 / 截断），新增可配最低 `content` 长度阈值
    （默认 0=off，避免回归）；低于阈值的结果丢弃而非注入。
  - 质量门**默认全开**（对中转生效），但阈值保守，确保「宁可切官方也不注入低质」。
- **T-R6 可观测**：`Attempt`/日志记录本次 Tavily 实际用的端点（`relay`/`official`）与降级原因；
  `toolHealth` 对 Tavily 可显示端点可用性（增量，旧前端不报错）。
- **T-R7 配置与文档**：`.env.example` 新增/更新 `DEEP_TAVILY_API_BASE_URL`、`DEEP_TAVILY_API_KEY_HIKARI`、
  中转/官方超时项、质量门项（URL 类键 `_BASE_URL` 结尾）；同步 `docs/spec/brief-generation.md`、`docs/spec/retrieval.md`。

## Acceptance Criteria

- [ ] **AC-T1 中转优先**：配置中转 + 官方后，中转有有效命中时**不调用**官方（单测断言官方端点零请求）。
- [ ] **AC-T2 官方兜底**：中转失败/超时/空/全部非法 URL 时自动调用官方并采用其命中；两都不可用时返回空且不影响其他 provider。
- [ ] **AC-T3 独立超时**：中转端点按短超时快速失败（构造 16s 延迟端点，`readTimeout=8` 时 <9s 返回并切官方），官方不被同一超时约束。
- [ ] **AC-T4 身份不虚高**：中转与官方命中同一 URL 时 `FactSheetService` 只计 1 源、`MULTI` 不因双端点触发；`witnessEndpoints` 可见。
- [ ] **AC-T5 质量门**：中转返回空 `title`+空 `content` 或非法 URL 时视为无效并切官方；结果级长度阈值生效（默认 off 零回归）。
- [ ] **AC-T6 零回归**：未配置中转端点时，Tavily 行为与现状逐位等价（走官方，provider=TAVILY）；**`extract` 超时仍为 15s、不被官方 search 的 30s 连带改变**；`mvn test` 全绿。
- [ ] **AC-T7 密钥卫生**：两端点 key 均来自 `.env`，仓库无真实 key；URL 键 `_BASE_URL` 结尾。
- [ ] **AC-T8 契约**：provider 名仍为 `TAVILY`（不新增枚举）；`toolHealth`/`SearchMeta` 兼容增量。

## Out of Scope

- 多 provider fanout / 预算 / 补检索（→ `10-04-web-fanout-merge`、`10-04-web-followup-budget`）。
- 新增 provider 名或把两端点算作两个来源（**明确不做**）。
- extract 的双端点化（当前只做 `search` 的双端点；`extract` 保持官方，若需要留后续）。
- 跨批次持久缓存。

## Notes

- 实测：中转失败 `http=554`、`0B`、恒定约 16s、**间歇性**（早前并发 10 路仅 3/10；复测有连续成功段，也有整段 554）；官方稳定 5/5、8 路并发 8/8。
  故「中转优先」的真实风险是中转不稳定 → **T-R3 独立短超时 + T-R2 快速兜底**是设计核心，而非可选优化。
- 与 A 的边界：A 只把 `apiBase` 变成可配置（单端点）；本任务实现**双端点 + failover + 质量门**。
- 官方额度上报（`/usage`）实测调用后不增长，不可作实时门控；故额度不作硬限制，由质量门与「每端点每轮一次」控成本。
