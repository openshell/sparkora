# prd.md — Crawl4AI 抓取通道

> 父任务：`10-05-self-hosted-sources`（需求集与设计论证持有者）
> 调研依据：`docs/汽车资讯信源调研报告-2026-10-05.md`
> **本任务无前置依赖，可独立开工。**

## Goal

把 `CRAWL4AI_BASE_URL` 从「预留配置」变为**实装的抓取通道**：为信源采集提供能过指纹 WAF、渲染 SPA 的无头浏览器抓取能力，并强制资源红线（并发 ≤2、同站频控、串行调度），供 B 级信源（乘联会）使用。

用户价值：没有它，乘联会等 B 级权威数据源无法采集；同时它是「外部搜索正文补抓」的潜在复用通道（`docs/spec/brief-generation.md` §8 一直将其列为 Tavily `/extract` 的替代/补充）。

## 依赖（显式声明，不依赖任务树位置隐含）

- **前置**：无。可立即实现。
- **后继**：`10-05-source-crawl-base` 的采集器在有 Crawl4AI 时可用于 B 级源；未接入时仅 HTTP。

## Requirements

- **C-R1 抓取 transport 抽象**：定义抓取通道接口（如 `FetchTransport`），返回规范化抓取结果（最终 URL / HTML 或 markdown 正文 / HTTP 状态 / 耗时）；提供两个实现：`HttpFetchTransport`（普通 GET，现有 `RestClient`/jsoup 能力）与 `Crawl4aiFetchTransport`（POST Crawl4AI）。
- **C-R2 Crawl4AI 客户端**：`Crawl4aiClient`，读 `CRAWL4AI_BASE_URL`（默认空 = 未配置），`configured()` 判空；调用 Crawl4AI 抓取接口取正文/HTML；超时、非 2xx、空正文分别可诊断；不抛穿调用方（失败经异常或结果态返回）。
- **C-R3 资源红线（硬约束，评审修正：频控按通道区分）**：全局并发 ≤2（`Semaphore` 或等价），可配 `CRAWL4AI_MAX_CONCURRENCY`（默认 2）。
  - **Crawl4AI 通道**：同 host 每天请求 ≤2（`CRAWL4AI_PER_HOST_DAILY_LIMIT` 默认 2）——指纹敏感且昂贵，乘联会为月度单页够用；达到上限快速返回「限流」态供上层降级，不排队。
  - **HTTP 通道不设硬日上限**：改用**同 host 最小请求间隔**（`SOURCE_HTTP_MIN_INTERVAL_MS` 默认 2000）+ 单轮条数上限控速。理由：用户目标为「BYD 申报全收」，工信部一次公示含多条子页，统一 `≤2/天` 会锁死采集（评审 2026-10-05）。
- **C-R4 降级可见**：未配置 `CRAWL4AI_BASE_URL` 时 `configured()=false`、`available()=false`；调用失败置健康失败态（照 `SearchTool.lastCallOk` 语义：乐观初值、失败不闩锁），不影响 HTTP 通道。
- **C-R5 超时与重试**：连接/读超时独立可配（照现有 `NEWS_TIMEOUT_MS` 口径）；**默认不自动重试**（无头浏览器重试昂贵），由上层按源决定。
- **C-R6 配置与文档**：`DeepProperties` 或独立 `Crawl4aiProperties`（前缀 `sparkora.crawl4ai`）；`.env.example` 新增 `CRAWL4AI_BASE_URL`（补全说明）、`CRAWL4AI_MAX_CONCURRENCY`、`CRAWL4AI_PER_HOST_DAILY_LIMIT`、`CRAWL4AI_TIMEOUT_MS`、`SOURCE_HTTP_MIN_INTERVAL_MS`；同步 `docs/spec/brief-generation.md` §8（把它从「预留」改为「已实装」）与相关 spec。

## Acceptance Criteria

- [x] **AC-C1 可抓取**：配置 `CRAWL4AI_BASE_URL` 后，对乘联会 `www.cpcaauto.com` 抓取能返回 200 正文（复现调研报告：curl 403 → Crawl4AI 200）；未配置时优雅返回 `UNCONFIGURED`，不抛异常。→ 契约经本机 v0.9.3 探活复验（`/md {f:"fit"}` 200 / 含正文）；`Crawl4aiFetchTransportTest.未配置base_返回UNCONFIGURED_不调用客户端不抛异常` 证未配置零调用不抛。
- [x] **AC-C2 并发红线**：并发发起 >2 个抓取时，实际在途 ≤2（构造并发单测/探针验证）；超出部分快速返回限流态而非无限排队。→ `Crawl4aiConcurrencyTest` 断言 `maxInFlight==2`、第 3 个 `limited=true` 3s 内返回（不排队），连跑稳定。
- [x] **AC-C3 频控按通道**：**Crawl4AI** 同一 host 当日第 3 次请求被拒（计数按日期重置），不同 host 互不影响；**HTTP** 通道无硬日限，但同一 host 连续请求间隔 ≥ `SOURCE_HTTP_MIN_INTERVAL_MS`（工信部多子页可一轮采完）。→ `FetchRateLimiterTest` 6 例：第 3 次拒 / 换 host 独立 / 跨本地日重置 / HTTP 连发无日限 / 间隔等待。
- [x] **AC-C4 降级不阻断**：Crawl4AI 不可达/超时/空正文时，调用方拿到明确失败态而非抛穿；`HttpFetchTransport` 行为不受影响。→ `Crawl4aiFetchTransportTest` 覆盖 TIMEOUT/EMPTY/HTTP 非2xx/异常分类；异常只记类名不外抛；HTTP 实现独立。
- [x] **AC-C5 零回归**：不配置时全库行为与现状等价；`mvn test` 全绿。新增配置项在 `.env.example` 有说明，URL 类键以 `_BASE_URL` 结尾。→ `mvn test` 859/0/0/0；`.env.example` 六键，`CRAWL4AI_BASE_URL` 以 `_BASE_URL` 结尾；`configured()` 不掺健康态。

## Out of Scope

- 信源注册/调度/解析（→ `10-05-source-crawl-base`）。
- 把本通道接入外部搜索正文补抓（`TavilySearchTool.extract` 替换）——本轮不做，仅预留接口。
- Crawl4AI 服务端部署/扩缩容（本机已就位，服务端不在本仓范围）。
- 反爬对抗升级（代理池/指纹伪装等）——依赖 Crawl4AI 自身能力。

## Notes

- **抓取接口契约已探活确认（2026-10-07，本机 Crawl4AI v0.9.3）**：`GET /health` 200；抓取主用 `POST /md`（入参 `{url, f}`，`f∈{raw,fit,bm25,llm}`），另备 `POST /html`、`POST /crawl`、`POST /crawl/job`；鉴权 `Authorization: Bearer <CRAWL4AI_API_KEY>`。**决定性验证**：乘联会 `curl` 直连 403 → Crawl4AI `/md {f:"fit"}` 200 / 7.0s / 正文含「行业新闻」。详见 `design.md` §0。
- 资源红线来自调研报告「本机 swap 偏紧、无头浏览器 300-400MB」；不得放宽默认值。
