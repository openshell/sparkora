# prd.md — A: Serper provider 接入

> 父任务：`10-04-brief-retrieval-sources`（需求集与设计论证持有者）
> 设计论证见父任务 `design.md` §0.1（R1 硬性约束）与 §5.1（垂直路由）。
> **本任务是 `brief-retrieval-sources` 的第一个交付物，无前置依赖，可独立开工。**

## Goal

让深度研究链路能通过 **Serper** 检索外部信息，且端点可配置、垂直可路由、地域默认中文；**在不改变任何现有默认行为**的前提下把 provider 契约扩展到三源。

用户价值：Serper 是本项目当前唯一可用的**第二真实搜索源**（`name()` 不同 → `FactSheetService.distinctSources` 可识别为 2 源 → 能产出 `MULTI`/`0.85` 交叉验证），是后续 B（多源聚合）与 C（补检索）的前置基座。

## 依赖（显式声明，不依赖任务树位置隐含）

- **前置**：无。可立即实现。
- **后继**：`10-04-web-fanout-merge` 需要本任务提供的 `WebProvider.SERPER` 枚举、`WebSearchRouter` 构造器注册点、`toolHealth` 的 SERPER 分支、`SearchTool.searchVertical` 签名。**若本任务的 `SearchTool` 垂直签名与 B 的假设不一致，B 需同步调整。**
- **契约约定**（B、C 必须遵守，不得各自另立）：
  - `WebProviderOrder.strategyLabel()` 扩为三值，新增 `PRIMARY_FANOUT`。
  - `SearchTool.searchVertical(query, vertical, maxResults)`，`vertical ∈ {web, news}`；**`search(query, maxResults)` 保留为 `vertical=web` 的默认实现**，不得删除（`SearxngSearchTool` 等现有实现依赖）。

## Requirements

- **A-R1 `SerperSearchTool`**：实现 `SearchTool`，`name()="SERPER"`，`configured()` 依 `effectiveSerperKey()` 非空，`lastCallOk` 语义照 `TavilySearchTool`（乐观初值、失败不闩锁、仅供健康展示）。认证走 **Header `X-API-KEY`**（**不是** body `api_key`——与 Tavily 不同，须在 `SearchTool` 接口注释写明该差异）。
- **A-R2 `apiBase` 可配置**（实测硬约束）：新增 `DeepProperties.serperApiBase` + `effectiveSerperApiBase()`（property→env→字段兜底链，照 `effectiveTavilyKey()` 写法）。默认官方 `https://google.serper.dev`。**理由**：中转端点为 `https://search.604020.xyz/serper`，路径前缀不同，硬编码会导致换端点必须改代码重编。
- **A-R3 垂直支持**：`searchVertical(query, vertical, maxResults)`，`web` → `POST {apiBase}/search`（body `{q,num,gl,hl}`，解析 `organic[].link/title/snippet/position`）；`news` → `POST {apiBase}/news`（解析 `news[].link/title/snippet/date/source/imageUrl`）。`search(query,maxResults)` 委托 `searchVertical(q, web, n)`。
  - `news` 垂直额外承载 R4b 时效能力的字段（`date`/`source`），本任务只负责**取到并保留**这些字段，**不做新鲜度计算**（R4b 默认 off）。
- **A-R4 地域参数默认中文**：默认发 `gl=cn`、`hl=zh-cn`，可配覆盖。**理由**：实测英文默认会削弱中文召回（`gl=cn` 命中 autohome / byd.com / 新浪财经 / news.cn）。
- **A-R5 单次结果上限**：per-provider 请求条数 clamp 到该 provider 上限（Serper 实测 `num=20` 只回 10 条）。`WebSearchRouter` 的 `attempts.resultCount` 必须记**实际**返回条数而非请求条数。
- **A-R6 Tavily 侧对称**：新增 `DeepProperties.tavilyApiBase` + `effectiveTavilyApiBase()`，`TavilySearchTool` 改用之（替换 `:30` 硬编码的 `DEFAULT_API_BASE`），默认 `https://api.tavily.com`。**本任务只做「单端点可配置」**；双端点（中转优先 + 官方兜底 + 独立超时 + 质量门）是后继子任务 `10-05-tavily-endpoint-priority`（用户 2026-10-05 指令），A 不实现该编排。
- **A-R7 契约扩展**：`WebProvider` 加 `SERPER`（`from(String)` 大小写不敏感、未知值抛 `IllegalArgumentException` 的既有语义不变）；`WebSearchRouter` 构造器注册 SERPER；`toolHealth` 支持 SERPER（未配置 → `UNCONFIGURED`）；`WebProviderOrder.strategyLabel()` 扩三值；`WebProviderOrder.DEFAULT_RAW` **保持 `TAVILY,SEARXNG` 不变**（默认值不变是零回归的前提）。
- **A-R8 文档同步**：`docs/spec/brief-generation.md`（搜索 provider 章节）与 `docs/spec/retrieval.md` 补 SERPER 字段级契约；`.env.example` 新增 `SERPER_API_BASE_URL` / `SERPER_API_KEY` / `DEEP_SERPER_API_BASE_URL` / `DEEP_SERPER_API_KEY` / `DEEP_TAVILY_API_BASE_URL` / `DEEP_WEB_VERTICAL_NEWS`（URL 类键一律 `_BASE_URL` 结尾，否则被 `secret-guard` 误判为凭据）。
- **A-R9 设置面放开 SERPER（评审新增 2026-10-06，实现期发现的集成缺口）**：运行时 provider order 由 `sparkora_setting.web_provider_order` 决定且**非空即优先于** `.env` 的 `DEEP_WEB_PROVIDER_ORDER`（`DeepResearchService.resolveSnapshot:206-210`）；`SettingService.get():42` 在行缺失时插入默认 `TAVILY,SEARXNG`（**永远非空**）。而 `SettingUpdateDto:23` 的 `@Pattern` 只接受 `TAVILY|SEARXNG`、`SettingsView.vue:42-43` 只有两个选项——**导致 Serper 在任何受支持的配置面都无法被排进 provider 顺序**（AC-A2 不成立）。故：
  - `SettingUpdateDto` 的 `@Pattern` 放开为 `TAVILY|SEARXNG|SERPER` 的任意顺序组合；
  - 新增 Flyway 迁移把 `web_provider_order` 由 `VARCHAR(20)` 放宽到 `VARCHAR(50)`（三源串 `TAVILY,SERPER,SEARXNG` = 21 字符，超现值）；
  - `SettingsView.vue` 增补含 Serper 的顺序选项（存量两选项保留，默认仍 `TAVILY,SEARXNG` 零回归）；
  - `docs/spec/settings.md` 同步字段值与枚举。
  - **注意**：默认值 `TAVILY,SEARXNG` 不变（SERPER 默认不启用）；本项只提供「用户按需启用」的受支持路径。

## Acceptance Criteria

- [ ] **AC-A1 零回归**：不设任何新配置时，`webProviderOrder` 仍为 `TAVILY,SEARXNG`、单 provider 短路、每 agent 1 条上限、仅背景题补正文、`rag_status` 四态语义全部不变；`mvn test` 现有 510 例全绿 + `npm run build` 通过。
- [ ] **AC-A2 Serper 可用**：配置 `SERPER_API_KEY` + `SERPER_API_BASE_URL` 后，`toolHealth` 显示 SERPER 就绪，深度研究可命中 Serper 结果；**仅改配置**即可在官方端点与中转之间切换（构造两个 `apiBase` 的单测或手工验证其一）。
- [ ] **AC-A3 未配置优雅降级**：未配置 key 时 SERPER 为 `UNCONFIGURED`，`WebSearchRouter` 跳过它，Tavily/SearxNG 行为不变。
- [ ] **AC-A4 垂直**：`web` 返回 `organic[]` 映射正确；`news` 返回 `news[]` 且 `date`/`source` 字段**被保留到 `SearchHit.content` 或等价载体**（供 R4b 使用）；不配置时全走 `web`；`/news` 与 `/web` 同价（`credits=1`），调用量不翻倍。
- [ ] **AC-A5 地域默认**：默认请求体含 `gl=cn`/`hl=zh-cn`；配置可覆盖。
- [ ] **AC-A6 条数核算**：`attempts.resultCount` 记实际返回数；请求 `num=20` 而 provider 只回 10 时，`resultCount=10`（构造单测）。
- [ ] **AC-A7 认证隔离**：`SearchTool` 接口注释明确记载「Serper 用 Header `X-API-KEY`、Tavily 用 body `api_key`」，避免后来者误以为 body 通用。
- [ ] **AC-A8 密钥卫生**：`.env.example` 中所有密钥项留空；仓库任何被跟踪文件中**不含真实 key**（提交前用 secret-guard 等价扫描确认）。
- [ ] **AC-A9 契约**：`strategyLabel()` 三值；`toolHealth` 含 SERPER；旧 `search(query,maxResults)` 签名保留且行为等价。
- [ ] **AC-A2a 设置面可启用 SERPER（评审新增）**：`PUT /settings` 接受含 `SERPER` 的顺序组合（如 `SERPER,TAVILY`、`TAVILY,SERPER,SEARXNG`）并落库；`web_provider_order` 列容纳三源串（`VARCHAR(50)`）；设置页可选择含 Serper 的顺序；默认仍 `TAVILY,SEARXNG`（SERPER 默认不启用）。非法值仍 400。

## Out of Scope

- 多源并行聚合与跨源合并 → `10-04-web-fanout-merge`。
- 补检索、预算/去重/缓存 → `10-04-web-followup-budget`。
- R4a 来源权威度分档、R4b 时效性计算、R6 补抓扩展的业务逻辑——本任务只**取到字段**，不做计算。
- Tavily 双端点编排（中转优先/官方兜底/独立超时/质量门）→ `10-05-tavily-endpoint-priority`。本任务只把 `apiBase` 变成可配置。

## Notes

- 实测证据（curl 直测，2026-10-04）见父任务 `prd.md` 的「第三方中转实测」条目，**不复述任何真实 key**。
- 密钥已在 `.env`（`SERPER_API_KEY` / `SERPER_API_BASE_URL`），本任务实现时直接读，无需向用户索要。
