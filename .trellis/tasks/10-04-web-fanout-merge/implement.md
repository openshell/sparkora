# implement.md — B: 多源并行聚合（PRIMARY_FANOUT + 跨源合并）

> 前置：`10-04-serper-provider` 已完成。前置未完成不得开工（否则 fanout 里只有 TAVILY 可并行）。
> 共享契约：`WebSearchOutcome.usedProviders` / `SearchMeta.providers` / `strategyLabel()`；C 依赖本任务的 `merge` 与 `PRIMARY_FANOUT`。

## 实施顺序

1. **策略枚举与配置**：新增 `SearchStrategy { FIRST_HIT, PRIMARY_FANOUT }`；`DeepProperties` 增 `webFanout`(默认 `first_hit`)、`webPrimaryProviders`(默认含 `SEARXNG`)、`webDenyDomains`、`webAllowDomains` + `.env.example`（URL 键不涉及；新增 4 个开关项）。
2. **`WebResultNormalizer.merge`**：跨源去重 / `witnessCount` 累加 / order 位次稳定排序 / 截断到 `maxResults` / **合并后统一分配 `W1..Wn`**。`normalize` 保留给单源路径。
3. **`WebSearchRouter.searchInternal` 分支**：`FIRST_HIT` 原逻辑用 `if` 包住；`PRIMARY_FANOUT` 按 §2 分组 → 虚拟线程并行 → `merge` → primary 全空回落 fallback 短路。抽出共用的单 provider 调用/异常隔离代码，避免两路径逻辑漂移。
4. **SearXNG 质量门**：在 merge 前对 primary 组结果按 §4 过滤。
5. **契约增量**：`WebSearchOutcome` 加 `usedProviders`（兼容构造器）；`SearchMeta` 加 `providers`；`Attempt` 加 `witnessTotal`。
6. **前端**：`ResearchProgress.vue` 来源展示改读 `attempts`（新增字段为增量）。
7. **文档**：`docs/spec/brief-generation.md`、`retrieval.md` 补 fanout 策略与合并契约。
8. **测试**（见下）→ `mvn test` 全绿 + `npm run build` 通过后交 check。

## 实现期第一件核对事项

- **[核对] 零回归**：不设 `DEEP_WEB_FANOUT` 时 `FIRST_HIT` 路径与现状**逐位等价**（短路、`Math.min(5, webQuota)`、attempts 语义不变）。用一个「既有行为快照测试」锁死。
- **[核对] sourceId**：确认两 provider 结果合并后 `sourceId` 全局唯一，且构造反例（同一 URL 被两源命中、同一 title 不同 URL）验证 `validateFacts` 仍通过。
- **[核对] 免费源不计预算**：本任务**不含**预算逻辑，但须确认 fanout 不引入对 SearXNG 的封顶（预算归 C）。

## 验证命令

```bash
mvn -q -DskipTests compile
mvn test                                    # 现有 842 例全绿 + 新增
npm run build
```

## 测试清单

- `WebResultNormalizerMergeTest`：
  - 跨源去重：同一 URL 两 provider 只保留 1 条，首次出现的 provider 胜出；
  - `witnessCount` 正确累加；order 位次排序稳定；
  - 截断：2 provider × 10 结果、`maxResults=5` → 输出 5 条（未放大）；
  - **sourceId 全局唯一**；反例：同一 URL 双源命中 / 同一 title 不同 URL。
- `WebSearchRouterFanoutTest`：
  - primary 分组按 `DEEP_WEB_PRIMARY_PROVIDERS` 正确（默认含 SearXNG；可移除）；
  - primary 为空回落 `FIRST_HIT`（SearxNG-only 逐位不变）；
  - 单 provider 异常不影响其他（断言其余 provider 仍产出，异常项记 `REASON_ERROR` 且不含 message）；
  - `UNCONFIGURED` 跳过；primary 全空 → fallback 兜底；
  - `FIRST_HIT` 与现状逐位等价（回归）。
- `WebSearchRouterQualityGateTest`：`bilibili.com`/`weixin.sogou.com`/`/video/`/`link?url=` 被过滤；过滤后仍保留正文页；`sourceCount` 不受质量门影响。
- `WebSearchOutcomeTest` / `SubAgentRunnerTest`（增）：`usedProviders`/`providers` 为增量，旧字段名与语义不变；旧 3 参构造器仍可用。

## 风险检查点（实现期）

| 检查点 | 何时 | 判定 |
|---|---|---|
| 零回归 | 不设 fanout | 逐位等价单测通过 |
| sourceId 唯一 | fanout 合并 | 反例单测 + validateFacts 通过 |
| 异常隔离 | 单 provider 抛错 | 其余仍产出，无 message 泄漏 |
| 条数不放大 | 2×10 输入 | 输出 = maxResults |

## 回滚点

- 运行时：`DEEP_WEB_FANOUT=first_hit`（默认）→ 回单源短路。
- 代码：revert 路由/合并改动；无 DB 迁移。

## 交付物

- `SearchStrategy`、`merge`、fanout 路由、质量门、契约增量、前端读数、spec 同步、单测全绿。
- 不包含：补检索/预算/缓存/跨轮去重（→ C）。
