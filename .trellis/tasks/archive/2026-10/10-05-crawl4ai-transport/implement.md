# implement.md — Crawl4AI 抓取通道

> 无前置依赖，可独立开工。契约与实测见 `design.md`（本机 Crawl4AI v0.9.3，2026-10-07 探活）。
> 后继：`10-05-source-crawl-base` 经 `FetchTransport` 接口消费（B 级源需本通道；未配置时降级 HTTP）。

## 实施顺序

1. **配置 `Crawl4aiProperties`（前缀 `sparkora.crawl4ai`）**：`baseUrl`/`apiKey`/`maxConcurrency`(2)/`perHostDailyLimit`(2)/`timeoutMs`(20000) + `SOURCE_HTTP_MIN_INTERVAL_MS`(2000)；`.env.example` 补全（URL 类键 `_BASE_URL` 结尾）。
2. **契约类型**：`FetchTransport` 接口 + `FetchOptions`/`FetchResult`（record，含 `limited`）；`Kind{HTTP,CRAWL4AI}`。
3. **`FetchRateLimiter`**：Crawl4AI 同 host 日计数（按本地日期键重置）→ 到限返回 `limited`；HTTP 同 host 最小间隔。
4. **`Crawl4aiClient`**：封装 `POST /md {url,f:"fit"}`（Bearer）+ `POST /html`；超时/非 2xx/空正文分类；异常只记类名。
5. **`Crawl4aiFetchTransport`**：非阻塞 `Semaphore.tryAcquire`（并发≤2）→ 频控 → 调用 → `FetchResult`；`configured()` 判 base 非空；`lastCallOk` 健康态。
6. **`HttpFetchTransport`**：普通 GET + 最小间隔；`configured()=true`。
7. **文档**：`docs/spec/brief-generation.md` §8 把 `CRAWL4AI_BASE_URL` 从「预留」改为「已实装」，补端点/配置/降级。
8. **测试**（见下）→ `mvn test` 全绿后交 check。

## 实现期第一件核对事项

- **[核对] 接口契约**：实现前再 `curl` 一次本机 `POST /md {f:"fit"}` 与 `/health`，确认版本未变（当前 v0.9.3）；若 `f` 枚举或端点变化，改 `Crawl4aiClient` 单点。
- **[核对] 非阻塞限流**：确认并发/频控到限返回 `limited=true` 而**不排队**（父 §2.2）。
- **[核对] 零回归**：未配置 `CRAWL4AI_BASE_URL` 时 `configured()=false`，A 级源走 HTTP 行为不变。

## 验证命令

```bash
mvn -q -DskipTests compile
mvn test                                    # 现有 842 例全绿 + 新增
```

可选联调（需本机 Crawl4AI）：
```bash
./dev.sh restart backend   # 或对应联调入口
# 触发 B 级源采集，观察 /md 返回与降级态
```

## 测试清单

- `Crawl4aiFetchTransportTest`（mock `Crawl4aiClient`）：
  - `configured()=false`（未配 base）→ `UNCONFIGURED`，不抛异常（AC-C1/C5）；
  - 正常 `/md` → `FetchResult.ok()`，`content` 非空；
  - 失败/超时/空正文 → 明确 `error`，调用方拿不到异常（AC-C4）。
- `FetchRateLimiterTest`：
  - Crawl4AI 同 host 第 3 次 → `limited`；换 host 不受影响；跨日期重置（AC-C3）；
  - HTTP 同 host 相邻间隔不足 → 等待/限速；**无日上限**（AC-C3）。
- `Crawl4aiConcurrencyTest`：并发 >2 时在途 ≤2，超出 `limited` 快速返回不排队（AC-C2）。
- 回归：未配置时全库行为等价（AC-C5）。

## 风险检查点（实现期）

| 检查点 | 何时 | 判定 |
|---|---|---|
| 并发红线 | 并发探针 | 在途 ≤2，超出 `limited=true` |
| 频控分通道 | 限流单测 | Crawl4AI 日限、HTTP 仅间隔 |
| 不抛穿 | 失败注入 | 调用方拿到 `FetchResult` 非异常 |
| 未配置零回归 | 不设 base | `configured()=false`，HTTP 路径不变 |
| 契约单点 | 版本核对 | `/md` 失败可回退 `/html` |

## 回滚点

- 运行时：不配 `CRAWL4AI_BASE_URL` → 通道禁用，B 级源降级跳过。
- 代码：revert transport 改动；无 DB 迁移。

## 交付物

- `FetchTransport` 抽象 + HTTP/Crawl4AI 两实现 + 按通道频控 + 并发 ≤2 + 降级可见 + 配置 + spec 同步 + 单测全绿。
- 不包含：信源注册/解析/调度（B）；把通道接入外部搜索 `extract`（预留）。
