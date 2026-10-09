# design.md — 乘联会与盖世信源采集计划

> 父任务：`10-05-self-hosted-sources`。本文只覆盖本任务技术设计；基座（B/E）契约见父 `design.md` §2.1/§2.5.1/§5.5/§6。

## 1. 边界与目标

把两个真实站点（乘联会 / 盖世）打通到既有采集基座，并补齐下列**基座能力缺口**（均在既有抽象上扩展，不新建体系）：

| # | 缺口 | 归属组件 |
|---|---|---|
| G1 | Crawl4AI wantHtml 未渲染（`/html` 拿不到 JS 生成链接） | `Crawl4aiFetchTransport` / `Crawl4aiClient` |
| G2 | `SiteSourceClient.detail` 只认 `<p>` → `section>span` 站取空 | `SiteSourceClient` |
| G3 | `SourceTableParser` 只认 `<table>`，不支持 `div.data li` | `SourceTableParser` |
| G4 | 无「新建信源」API/栏目写入 | `SourceService` / `SourceController` / DTO |
| G5 | 盖世海报需过滤图标/logo/二维码 | `SiteSourceClient` 图片抽取 + `parse_rules.images` |

**不改**：`FetchTransport`/`SourceClient` 接口签名、`SourceCollectService` 编排、入库/切块/嵌入链路（E）、图库设施。全部为「在既有抽象内扩展 + 配置驱动」。

> **前端增量（R11/AC-11，用户 2026-10-09）**：U 已交付的 `SourceManagePanel`（排期列/最近采集/启停/手动采集）与 `SourceJobPanel`（任务列表/进度/失败明细/重试/2s 轮询）**已覆盖「抓取状态」**；本任务前端只补**「计划可视化」——下次运行时间**（见 §13）。**不做前端新建信源入口**（用户明确同意）。

## 2. G1 渲染 HTML 抓取路径

**问题**：`Crawl4aiClient.fetchHtml` 调 `POST /html`，返回的是**未执行 JS** 的原始 DOM（乘联会列表 `<a>` 无 href）。实测 **`POST /crawl {urls:[url]}`** 返回 `results[0].cleaned_html`，是**渲染后**的 DOM（列表 20 条真实 href）。

**设计**：
- `Crawl4aiClient.fetchHtml(url)` 改走 `POST /crawl {urls:[url]}`，取 `results[0].cleaned_html`（回退 `html`）；解析 `results` 数组取首元素。
- 保留 `fetchMarkdown` 走 `/md`（正文抓取继续用 markdown 不受影响；本任务的 SITE 站点走 HTML 路径）。
- **容错**：`/crawl` 返回结构 `{results:[{success,cleaned_html,html,error_message,...}]}`；`success=false` 或 `results` 空 → `Result.failure(...,"EMPTY")`；沿用「异常只记类名」。
- **零回归**：未配置 `CRAWL4AI_BASE_URL` → 仍 `UNCONFIGURED`；`Crawl4aiFetchTransport` 逻辑不变（只是 client 内部换端点）。
- **代价**：`/crawl` 比 `/html` 略重（渲染）。仍在**同 host ≤2/天**红线内；乘联会为月度+周度采集，量可控。

> 兼容：如担心 `/crawl` 版本差异，`Crawl4aiClient.fetchHtml` 可对 `/crawl` 失败再回退 `/html`（best-effort），保证不劣化现状。

## 3. G2 正文容器优先抽取

**问题**：`SiteSourceClient.detail` 现逻辑：容器= `rules.contentSelector() ?: body`；然后**只取容器内 `<p>`**，`<p>` 为空才取整段文本。乘联会 `div.text` 内是 `section>span`（无 `<p>`）→ 取整段文本兜底**其实能拿到**，但盖世有 54 个 `<p>` 会按段落拼（现状正确）。

**真正的风险点**：乘联会走「整段文本兜底」时会把导航/相关阅读一起吞进（容器若选到 body）。修法：**容器选择器必选**（栏目 `parse_rules.detail` 指向 `div.read_content` 或 `div.text`），并在容器内：
1. 先取 `<p>` 段落（≥1 且拼接后长度达阈值）→ 用段落；
2. 否则取容器 `.text()`（含 `section/span`）→ 按块拆分；
3. 容器选择器缺失 → 现状（body + `<p>`），零回归。

**实现**：把「段落 vs 整段」判定从「`<p>` 是否存在」改为「容器优先 + 段落优先 + 整段兜底」，且容器选择器命中时**不再回退 body**。保留 `SourceTableParser` 在容器内先转表格的既有顺序。

## 4. G3 非 table 结构化抽取

**问题**：盖世 `/qcxl` 排行是 `div.data > ul > li`（无 `<table>`）。现有 `SourceTableParser.parseTables(root, selector)` 只处理 `<table>`。

**设计**：扩展 `SourceTableParser`（纯静态、可单测），新增 **列表型结构化**：
- `parseRules` 新增可选字段 `listRows`（CSS 选择器，如 `div.data ul li`）与 `rowCells`（行内单元格选择器，如 `span`）；命中时逐行取单元格文本，用既有 `CELL_SEP`（" | "）拼成一行文本块，`\n` 连接。
- 与 `tables` 字段并列：先转 `tables`，再转 `listRows`，都并入正文块序列（保留行列语义 → 下游 `TextChunker preserveNewlines=true` 生效，数值不丢）。
- `SourceParseRules` 增加两字段 + 兼容构造器（既有 8 参构造保留）。
- **零回归**：未配置 `listRows` 时行为与现状逐字一致。

## 5. G4 信源注册闭环

**API**（`SourceController`）：
- `POST /api/sources`：body `SourceCreateDTO{name,type,vertical,cron,windowStartDay,windowEndDay,authorityTier,needCrawl4ai,enabled,channels:[ChannelDTO{name,listUrl,detailBaseUrl,category,parseRules,needCrawl4ai,enabled}]}`；ADMIN/EDITOR；事务内插入 source + channels，返回 `get(id)`。
- `POST /api/sources/{id}/channels`、`PUT /api/channels/{id}`、`DELETE /api/channels/{id}`（栏目增改删）；ADMIN/EDITOR。改后触发 `scheduleService.register`。
- **兼容**：`SourceService` 现有 `update` 不动；新增 `create(dto)` / channel CRUD 方法。栏目写路径需与 `SourceChannelEntity` 对齐（部分更新/置空沿用 database-guidelines 范式）。
- **校验**：`listUrl` 必填、`name` 必填、`type ∈ {RSS,SITE}`、`parseRules` 为合法 JSON（非法 400）。
- 前端「新建信源」入口**不在本任务**（UI 属 U，其 AC-U1 已登记 PARTIAL）——本任务只保证 API 可达，供集成测试与后续 UI 复用。**若你希望连 UI 一起做，请在评审时说明**。

## 6. G5 盖世销量海报入图库

**现状**：B/E 已实现正文图片转存（`SiteSourceClient` 抽 `<img>` → `SourceImageService.transfer` → `saveExternalImage(source="source")`），图库/嵌入/检索链路齐备。缺的是**图片过滤**（否则图标/logo/二维码会被转存进图库，污染）。

**设计**：
- `parseRules.images` 定为**正文容器内**的选择器（如 `div.articletab img`），缩小范围；
- 新增 `parseRules.imageAllow` / `imageDeny`（可选，逗号分隔子串匹配）；`SiteSourceClient` 抽取时按 deny 过滤（默认 deny：`/common/`、`/companyLogo/`、`qrcode`、`160_110`、`logo`）；
- 或**内置默认 deny 规则**（不依赖配置）——推荐：默认 deny 常量 + 配置可扩展；
- 图片经 `SourceImageService.transfer`（`sourceRef`=派生 news_id）；`ImageEmbeddingTextBuilder` 的 `source` 分支已用来源标题（10-05 已做），无需改；
- **单图失败 warn 跳过**（既有）；无图源行为不变。

**不做**：图像内容识别（视觉）——海报入库沿用文本代理嵌入（来源标题+标签），可检索。

## 7. 源与栏目配置（种子）

用 **V17 迁移**（幂等 `INSERT ... WHERE NOT EXISTS`）预置两个源 + 栏目，便于开箱验证（也可走 `POST /api/sources`）：

| 源 | type | need_crawl4ai | 栏目 | listUrl | category | fetchMode |
|---|---|---|---|---|---|---|
| 乘联会 | SITE | true | 车市解读 | `news.php?types=csjd` | 销量数据 | CRAWL4AI |
| 乘联会 | SITE | true | 乘联分会论坛 | `news.php?types=yjsy` | 行业资讯 | CRAWL4AI |
| 盖世汽车 | SITE | false | 销量资讯 | 文章列表页 | 官方新闻 | HTTP |
| ~~盖世汽车~~ | ~~SITE~~ | ~~false~~ | ~~销量排行~~ | ~~`/qcxl`~~ | ~~销量数据~~ | **退役（V18）** |

- 乘联会 `parse_rules`：`{list:".list_d li.q", link:"a", title:"a", date:"span", detail:"div.read_content", tables:"", images:""}`。
- 盖世文章 `parse_rules`：`{list:..., link:"a", detail:"div.articletab", images:"div.articletab img", listRows:""}`。
- 盖世排行 `parse_rules`：`{list:"div.data ul li", link:"a", detail:"", listRows:"div.data ul li", rowCells:"span"}`。
- 默认 `enabled=false`（避免误触发采集）；`SOURCE_COLLECT_ENABLED` 仍默认 false → 零回归。
- **盖世「销量排行」真机不可采（2026-10-09 复核）**：`/qcxl` 排行详情页被**腾讯 WAF 验证码**拦截（HTTP 200/1543B，Crawl4AI 无法渲染），故该栏目由 **V18 退役**（V17 已应用不可改）。**`parseListRows` 能力保留**（纯静态、已单测、零回归），未来接入可达排行源可复用。
- **种子迁移 vs API 二选一**：迁移给「开箱即得」（幂等、可跳过）；API 给「可运营」。二者都做（迁移预置 + API 可改）。

> 排期：乘联会 `cron` 月度窗口（`window_start_day=8,window_end_day=11`），或栏目级独立 cron；盖世 `cron` 每日。复用 B 的发布窗口语义（成功才标 batch）。

## 8. 数据流（端到端）

```
[配置] V17 种子 / POST /api/sources  → sparkora_source + source_channel
   │
   ▼ 手动 POST /sources/{id}/collect  或 TaskScheduler 定时
SourceJobService.createJob ──> SourceCollectService.collect(source, channel)
   │  遍历 enabled channels
   │  transport = needCrawl4ai ? CRAWL4AI : HTTP
   │  ├─ 乘联会: Crawl4AI /crawl → cleaned_html（渲染）→ SiteSourceClient.list（.list_d li.q）
   │  └─ 盖世:   HTTP GET → SiteSourceClient.list
   │  逐条 detail:
   │  ├─ 容器优先正文（G2）+ 表格/列表结构化（G3）
   │  └─ 正文图 → SourceImageService.transfer（G5，过滤后）→ 图库
   ▼ 幂等 upsert → sparkora_news(source="source", source_id, channel_id, category)
   ▼ SourceDocService.rebuildForNews → sparkora_news_doc + vector_store(domain=NEWS, sourceType, category)
   ▼ 检索：CarRagService NEWS 域（sourceType=user-source/gasgoo-* 二级隔离）
   ▼ 图库：ImageEmbeddingService.semanticSearch（source=source 图）
```

## 9. `sourceType` 映射（销量全收）

- 用户明确「销量全收，不限 BYD」→ `parse_rules.bydOnly=false`（default），R1/R2 不做 BYD 过滤。
- `SourceCatalog.sourceTypeOf`：盖世栏目含「排行」→ `gasgoo-ranking`（`crossCounted=false`）；含「销量/官宣」→ `gasgoo-announce`；乘联会/其他 → `user-source`（`crossCounted=true`）。
- 乘联会与盖世官宣可构成独立交叉（车企自报 vs 协会统计），排行不计独立——已在 SourceCatalog 定义，本任务只需保证 channel 名匹配关键词。

## 10. 兼容与迁移

- **迁移 V17**：只 `INSERT ... WHERE NOT EXISTS`（源/栏目），不改既有表结构；幂等可重入。既有最大 V16。
- **配置**：`.env.example` 无需新增密钥；`SOURCE_COLLECT_ENABLED` 保持默认 false。
- **回滚**：删除 V17 预置源（或 `enabled=false`）+ `SOURCE_COLLECT_ENABLED=false` → 等价现状；G1–G5 均为向后兼容扩展，未配置时零回归。

## 11. 风险

| 风险 | 缓解 |
|---|---|
| 乘联会站点改版 → 选择器失效 | `parse_rules` 集中配置，改一条数据即可（父设计原则） |
| `/crawl` 比 `/html` 慢/重 | 乘联会采集频率低（月/周）；同 host ≤2/天红线内 |
| 盖世海报误过滤/漏过滤 | 默认 deny 常量 + 可配置扩展；先测再固 |
| 深度分析报告数据在附件 | 本任务不解析附件；正文有则采，无则记限制 |
| 采集内容污染 BYD 配额 | E 已做 NEWS 域 sourceType 二级隔离；默认 user 源配额 0 |

## 12. 测试策略

- G1：`Crawl4aiClient` mock `/crawl` → 断言取 `cleaned_html`；`/crawl` 失败回退 `/html`；未配置 UNCONFIGURED。
- G2：构造 `section>span` HTML → 容器优先正文非空；构造多 `<p>` → 与现状等价。
- G3：`div.data li` HTML → 行文本含数值、`CELL_SEP` 分隔；未配置 `listRows` 零回归。
- G4：`SourceControllerContractTest` 增 `POST /sources` 创建/回读/权限；channel CRUD。
- G5：含图标+海报的 HTML → 只转存海报（deny 命中过滤）；单图失败不阻断。
- 端到端：以真实站点做一次手动采集（集成/手工验证，非 CI）。

## 13. G6 运维面板「计划可视化」（R11/AC-11）

**现状**：U 已交付 `SourceManagePanel.vue`（「排期」列显示 `每月 8-11 日`/`cron` 原文；「最近采集」列显示状态+进度；启停；手动采集）与 `SourceJobPanel.vue`（任务历史/进度/失败明细/重试/RUNNING 2s 轮询）。**抓取状态已可视化**；缺「计划下次何时运行」。

**设计**：
- **后端**：`SourceEntity` 增**非持久化**字段 `nextRunAt`（`LocalDateTime`，不映射列）；`SourceService.list()`/`get()` 回填。
  - 计算：`SourceScheduleService` 暴露 `nextRunAt(SourceEntity src)`（返回 `LocalDateTime` 或 null）。
    - 源 `enabled=false` 或总开关 `collectEnabled=false` → `null`（面板显示「已停用」/「未启用调度」）。
    - 有发布窗口：下次运行 = 今日（窗口内且本批未完成、且当前早于每日 03:30）→ 今日 03:30；否则下一个窗口起始日 03:30。
    - 无窗口：用 `CronExpression.parse(src.getCron())` 计算 `next(now)`。
  - `CronExpression` 来自 `org.springframework.scheduling.support`（Spring 自带，无需新依赖）。
  - **只读计算，不改调度注册**；异常降级为 null（面板显示「—」），不阻断列表。
- **前端**（`SourceManagePanel.vue`）：
  - 「排期」列或新增「下次运行」列展示 `nextRunAt`（格式 `MM-dd HH:mm`；空则「—」；停用源显示「已停用」）。
  - 复用既有列与样式，不重写表格结构；`npm run build` 通过。
- **契约**：`GET /api/sources`、`GET /api/sources/{id}` 返回体新增 `nextRunAt` 字段（增量、可空，向后兼容）；`docs/spec/knowledge/sources.md` 同步。
- **零回归**：`nextRunAt` 纯增量字段；`mvn test` 契约测试断言旧字段不变 + 新字段存在性。

**不做**：前端新建/编辑栏目（沿用「后端预置 + 现有编辑源级字段」）；日历/甘特图等重 UI。
