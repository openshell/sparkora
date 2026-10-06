# design.md — Tavily 双端点：中转优先 + 官方兜底 + 质量门

> 依赖：`../10-04-serper-provider`（A 的 `effectiveTavilyApiBase()`）。
> 与父任务 `../10-04-brief-retrieval-sources/design.md` §2.4 的 `usedProviders` 契约兼容。

## 1. 边界

| 层 | 文件 | 改动 |
|---|---|---|
| 配置 | `config/DeepProperties.java` | 中转 base/key、端点超时、质量门阈值 + `effective*()` |
| 工具 | `deep/tool/TavilySearchTool.java` | 双 `RestClient`（按端点超时）+ relay→official failover + 质量门 |
| 契约 | `deep/tool/SearchHit.java` / `WebSearchOutcome.Attempt` | 端点 witness（可空，兼容） |
| 观测 | `toolHealth` 展示层 | 端点可用性（增量） |
| 文档 | `docs/spec/brief-generation.md`、`retrieval.md`、`.env.example` | 同步 |

**不做**：fanout/预算/补检索；不新增 provider 枚举；extract 不做双端点（保持官方）。

## 2. 端点模型

Tavily 工具内部持有两个端点：`relay`（中转，可选）与 `official`（官方，可选）。
不引入新 `WebProvider`——**两者对外都是 `TAVILY`**（T-R4）。

```
relayBase   = DEEP_TAVILY_API_BASE_URL   (默认空=未配置)
relayKey    = DEEP_TAVILY_API_KEY_HIKARI
officialBase = effectiveTavilyApiBase()  (默认 https://api.tavily.com)
officialKey  = effectiveTavilyKey()
```

每个端点一个 `RestClient`：
- relay：connect 5s / read `DEEP_TAVILY_RELAY_READ_TIMEOUT_MS`（默认 8000）
- official：connect 5s / read `DEEP_TAVILY_OFFICIAL_READ_TIMEOUT_MS`（默认 30000）

> 关键：**不能共用同一超时**。实测中转失败恒定 16.5s，若用官方 30s 超时会把失败拖长；若用统一 15s，
> 中转失败必超时且官方也受牵连。独立超时是「快速失败 + 快速兜底」的前提（AC-T3）。

**`extract` 的超时须独立（P2 修正）**：现 `TavilySearchTool` 的 `search` 与 `extract` **共用同一 `RestClient`**
（`:53-57`）。若把官方 read 由 15s 改 30s，会**顺带拉长 `/extract` 的超时**（extract 是付费慢调用，行为改变）。
故官方 `search` 端点与 `extract` **各持一个 `RestClient`**：
- `extract` 沿用原 read `15s`（或新增 `DEEP_TAVILY_EXTRACT_READ_TIMEOUT_MS` 默认 15000），**不被 30s 影响**；
- 本任务只改 `search` 的双端点；`extract` 仍走官方、超时保持 15s（不做双端点，见 Out of Scope）。

## 3. failover 流程

```
search(query, maxResults):
  for endpoint in [relay, official]:
    if !endpoint.configured(): continue           # 未配置跳过
    try:
      raw = call(endpoint, query, maxResults)
      hits = normalize(raw, maxResults)
      if qualityPass(hits):                        # T-R5 端点级质量门
        lastEndpoint = endpoint.id; return hits
      else: reason = LOW_QUALITY
    except Timeout/Error:
      reason = TIMEOUT/ERROR
    # 记录 Attempt(endpoint=TAVILY, usedEndpoint=endpoint.id, reason)
  return []                                        # 交 WebSearchRouter 继续下一 provider
```

- 每端点每次研究最多一次调用（不重试），避免无限额下失控。
- **不限额度**：不做调用数封顶；成本由「每端点每轮一次」+ 质量门控制。

## 4. 质量门（T-R5）

**端点级**（决定是否切下一端点）：
- 至少 1 条结果 `title` 与 `content` 不同时为空；
- `url` 可被 `WebResultNormalizer.normalizeUrl` 规范化（主流 scheme）；
- 非噪声域（可配 `DEEP_TAVILY_DENY_DOMAINS`，默认含已知跳转/聚合域）。

**结果级**（决定是否注入）：
- `content` 长度 ≥ `DEEP_TAVILY_MIN_CONTENT_CHARS`（默认 0=off，零回归）；
- 非法 URL 沿用既有丢弃。

> 默认阈值保守：端点级质量门对中转开；结果级长度门槛默认 off，避免破坏现有召回。

## 5. provider 身份与去重（T-R4）

- 命中统一 `SearchHit.web("TAVILY", ...)`。
- `FactSheetService.distinctSources` 按 `url+modelName` 去重 → 双端点同 URL 只 1 源。
- 新增 `witnessEndpoints: List<String>`（`relay`/`official`）仅供观测，**不参与 confidence/`MULTI`**。
- 该约束与 `.env:119-121` 既有注释一致，本任务把它实现化。

## 6. 可观测

- `Attempt` 增量字段 `usedEndpoint`（可空，兼容构造器）。
- 日志：`provider=TAVILY endpoint=relay|official reason=... latencyMs=...`（不落 key/URL 全量）。
- `toolHealth`：Tavily 项可显示 `relay`/`official` 可用性（增量）。

## 7. 风险

| 风险 | 缓解 |
|---|---|
| 中转慢失败拖累整轮 | 独立短超时（8s）+ 快速切官方（AC-T3） |
| 双端点被误算两来源 | provider 名固定 TAVILY；仅 witnessEndpoints 观测（AC-T4） |
| 质量门过严误杀有效结果 | 结果级阈值默认 off；端点级仅过滤空/非法/噪声域 |
| 官方限额被中转故障打爆 | 官方只在中转失败时调用（AC-T1/T2）；额度不作硬门控 |
| 共享超时 | 每端点独立 RestClient（§2） |
| 改官方 read 连带改变 `extract` 超时 | `extract` 独立 `RestClient` 保持 15s（§2） |
| 未配置中转回归 | 默认走官方，行为等价现状（AC-T6） |

## 8. 回滚

不配置 `DEEP_TAVILY_API_BASE_URL` 即回到单官方端点，行为等价现状。全量回滚 = revert 工具/配置改动。
