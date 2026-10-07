# design.md — Crawl4AI 抓取通道

> 父任务：`../10-05-self-hosted-sources`。共享契约见父 `design.md` §2.2（`FetchResult.limited`）、§3.4（通道降级）。
> 本任务无前置依赖，可独立开工；是 B 级信源（乘联会）的必要前置。
> 实测依据：2026-10-07 对本机 **Crawl4AI v0.9.3**（`CRAWL4AI_BASE_URL`）探活与真实抓取。

## Ø. 实测契约（2026-10-07，非推断）

- `GET /health` → `200 {"status":"ok","version":"0.9.3"}`（探针用）。
- 鉴权：`Authorization: Bearer ${CRAWL4AI_API_KEY}`（`.env` 已配置）；`POST /token` 亦可换取。
- 抓取端点（OpenAPI `Crawl4AI API 1.0.0`）：
  - `POST /md`：入参 `{url*, f, q, c}`，`f ∈ {raw, fit, bm25, llm}`（**注意不是** `fit_markdown`）；返回 `{url, filter, query, cache, markdown}`。**本任务主用**。
  - `POST /html`：入参 `{url}`，返回 HTML。
  - `POST /crawl`：入参 `{urls*, browser_config?, crawler_config?, crawler_configs?}`，一次多 URL 同步抓取。
  - 异步：`POST /crawl/job` → `GET /crawl/job/{task_id}`；`POST /llm/job`。
  - 监控：`GET /monitor/health`、`/monitor/browsers`、`/monitor/errors`。
- **决定性验证（AC-C1 依据）**：乘联会 `https://www.cpcaauto.com/` 直连 curl（真实浏览器 UA）= **403 / 33B**；Crawl4AI `POST /md {f:"fit"}` = **200 / 7.0s / 含「行业新闻」**。证明通道价值成立。

## 1. 边界

| 层 | 文件（新增/改） | 改动 |
|---|---|---|
| 抽象 | `source/fetch/FetchTransport.java`（新增） | `fetch(url, opts) → FetchResult`；枚举 `kind()=HTTP|CRAWL4AI` |
| HTTP | `source/fetch/HttpFetchTransport.java`（新增） | 普通 GET（jdkserver `HttpClient`/既有 `RestClient`），同 host 最小间隔 |
| Crawl4AI | `source/fetch/Crawl4aiFetchTransport.java`、`source/fetch/Crawl4aiClient.java`（新增） | `POST /md {f:"fit"}` → markdown；健康态 |
| 频控 | `source/fetch/FetchRateLimiter.java`（新增） | 按通道：Crawl4AI 同 host ≤/天；HTTP 同 host 最小间隔 |
| 配置 | `config/Crawl4aiProperties.java`（新增，前缀 `sparkora.crawl4ai`） | base/apiKey/并发/频控/超时 + `.env.example` |
| 文档 | `docs/spec/brief-generation.md` §8 | 从「预留」改「已实装」 |

**不做**：信源注册/解析/调度（B）；Crawl4AI 服务端部署；反爬对抗升级；把通道接外部搜索 `extract`（预留接口，本轮不做）。

## 2. 共享契约（B 消费）

```java
public interface FetchTransport {
    Kind kind();                                   // HTTP | CRAWL4AI
    boolean configured();                          // CRAWL4AI: base 非空;HTTP: true
    FetchResult fetch(String url, FetchOptions opts);   // 永不抛出，失败经 error 表达
}

public record FetchOptions(String httpMethod, Map<String,String> headers, boolean wantHtml, long timeoutMs) {}

public record FetchResult(String finalUrl, int status, String html, String content,
                          long latencyMs, String error, boolean limited) {
    // limited=true：被并发/频控拒绝（B 据此降级跳过，不无限重试）——父 §2.2
    boolean ok() { return error == null && status >= 200 && status < 300; }
}
```

## 3. 并发与频控（父 §2.2 / §3.3 红线）

**并发（硬约束）**：Crawl4AI 通道全局并发 ≤2。`Crawl4aiFetchTransport` 持一个 `Semaphore(2)`（`CRAWL4AI_MAX_CONCURRENCY` 默认 2）。
- 采用**非阻塞 `tryAcquire`**（或带超时）：取不到许可 → 立即返回 `FetchResult(limited=true, error="CONCURRENCY_LIMIT")`，**不排队**（父 §2.2「快速返回限流态供上层降级」）。

**频控（按通道区分，评审 2026-10-05）**：

| 通道 | 规则 | 理由 |
|---|---|---|
| Crawl4AI | 同 host **每天 ≤ `CRAWL4AI_PER_HOST_DAILY_LIMIT`（默认 2）**，按本地日期重置 | 指纹敏感 + 昂贵；乘联会月度单页够用 |
| HTTP | **不设硬日限**；同 host 相邻请求间隔 ≥ `SOURCE_HTTP_MIN_INTERVAL_MS`（默认 2000） | 工信部一次公示含多条 BYD 子页，`≤2/天` 会锁死「BYD 申报全收」 |

落点：`FetchRateLimiter`（进程内 `Map<host, ...>`；Crawl4AI 计数带日期键）。到限 → `limited=true`。

## 4. `Crawl4aiFetchTransport` 数据流

```
fetch(url, opts):
  if !configured(): return FetchResult(url, 0, null,null,0,"UNCONFIGURED",false)
  if !rateLimiter.acquire(host, CRAWL4AI): return ...(limited=true, "PER_HOST_DAILY_LIMIT")
  if !semaphore.tryAcquire(): return ...(limited=true, "CONCURRENCY_LIMIT")
  try:
    body = {"url": url, "f": "fit"}                       # /md 主路径
    resp = POST {CRAWL4AI_BASE_URL}/md  Authorization: Bearer <key>
    if 2xx && markdown 非空: lastCallOk=true; return FetchResult(url,200,null,markdown,latency,null,false)
    else: lastCallOk=false; return FetchResult(...status, error="HTTP_"+status / "EMPTY", false)
  catch Timeout: lastCallOk=false; return ...(error="TIMEOUT")
  catch e: lastCallOk=false; return ...(error="ERROR")   # 只记类型，不记 message/key/url 全量
  finally: semaphore.release()
```

- `wantHtml=true` 时改调 `POST /html`（B 需要 HTML 解析选择器时用；默认走 `/md` 取正文）。
- 健康态照 `SearchTool.lastCallOk` 语义：乐观初值、失败不闩锁、不参与 `configured()`。
- **超时**：`CRAWL4AI_TIMEOUT_MS` 默认 20000（实测乘联会 `/md` 7s，留余量）；连接超时 5s。
- **不自动重试**（无头浏览器重试昂贵），由上层按源决定。

## 5. `HttpFetchTransport`

- 普通 `GET`（复用既有 `RestClient`/jsoup 能力），返回 HTML/正文。
- 同 host 最小间隔由 `FetchRateLimiter` 保证（默认 2s，可配）；**无日上限**。
- 无 `configured()` 门槛（恒 true）。

## 6. 配置（`.env`，URL 类键 `_BASE_URL` 结尾）

| 键 | 默认 | 说明 |
|---|---|---|
| `CRAWL4AI_BASE_URL` | 空 | Crawl4AI 地址（已实装；`.env` 现为 `http://192.168.3.108:11235`） |
| `CRAWL4AI_API_KEY` | 空 | Bearer 令牌 |
| `CRAWL4AI_MAX_CONCURRENCY` | `2` | 全局并发（红线） |
| `CRAWL4AI_PER_HOST_DAILY_LIMIT` | `2` | Crawl4AI 同 host/天（红线） |
| `CRAWL4AI_TIMEOUT_MS` | `20000` | 读超时 |
| `SOURCE_HTTP_MIN_INTERVAL_MS` | `2000` | HTTP 同 host 最小间隔 |

## 7. 风险

| 风险 | 缓解 |
|---|---|
| Crawl4AI 版本升级改契约（`f` 枚举、端点） | 首次实现以探活为准；`/md` 失败可回退 `/html`+本地解析；契约写死在 `Crawl4aiClient` 单点 |
| 无头浏览器拖垮本机（swap 紧） | 并发 ≤2 硬 `Semaphore` + 同 host ≤2/天 + 非阻塞限流 |
| 长任务占满并发 | `/md` 单页为主，默认 20s 超时；B 级源串行调度（父 §3.3） |
| 未配置时影响 A 级源 | `configured()=false`，B 的 A 级源走 HTTP 不受影响 |
| 密钥/URL 泄漏进日志 | 异常只记 `e.getClass().getSimpleName()`；不打 base/key |

## 8. 回滚

未配置 `CRAWL4AI_BASE_URL` 即 `configured()=false`，全库行为等价现状（B 级源降级跳过）。无 DB 迁移。
