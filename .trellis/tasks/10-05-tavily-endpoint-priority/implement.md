# implement.md — Tavily 双端点：中转优先 + 官方兜底 + 质量门

> 依赖：`10-04-serper-provider`（A 的 `TavilySearchTool.apiBase` 可配置）。
> 共享契约：provider 名固定 `TAVILY`；与 `10-04-web-fanout-merge` 的 `usedProviders` 兼容。

## 实施顺序

1. **配置 `DeepProperties`**：新增 `tavilyRelayApiBase` / `effectiveTavilyRelayKey()` / `effectiveTavilyRelayBase()`、
   `tavilyRelayReadTimeoutMs`(8000) / `tavilyOfficialReadTimeoutMs`(30000)、`tavilyDenyDomains`、`tavilyMinContentChars`(0)
   + `.env.example` 对应项（URL 类 `_BASE_URL` 结尾）。
2. **`TavilySearchTool` 双端点**：构造两个 `RestClient`（各自超时）；抽出 `callEndpoint(base, key, query, n)`；
   `search` 按 `relay → official` failover。
3. **质量门**：`qualityPass(hits)`（端点级）+ 结果级 `minContentChars` 过滤；噪声域过滤。
4. **契约增量**：`Attempt` 加 `usedEndpoint`（兼容构造器）；`SearchHit` 保持 `web("TAVILY", ...)` 不变。
5. **可观测**：日志端点/原因；`toolHealth` 增量展示。
6. **文档**：`brief-generation.md`、`retrieval.md` 同步。
7. **测试**（见下）→ 全绿后交 check。

## 实现期第一件核对事项

- **[核对] provider 名**：确认两 `SearchHit.web(...)` 都用 `"TAVILY"`；`WebProvider` 枚举**未新增**条目。
- **[核对] 超时隔离**：确认 relay/official 不是同一个 `RestClient`，构造延迟端点时短超时确实先生效。
- **[核对] 零回归**：未配置 relay 时 `search` 直接走 official，路径/参数与现状一致。

## 验证命令

```bash
mvn -q -DskipTests compile
mvn test                                    # 现有 842 例全绿
```

## 测试清单

- `TavilySearchToolRelayTest`：
  - relay 有效命中 → official **零请求**（AC-T1）；
  - relay 超时/空/全非法 → 调 official 并采用（AC-T2）；
  - 短超时端点在 8s 内失败并切换（AC-T3，用延迟 mock / 本地 stub）；
  - 质量门：空 title+content、非法 URL、噪声域 → 视为无效切换（AC-T5）；
  - 未配置 relay → 行为等价现状（AC-T6）。
- `FactSheetServiceTest`（增）：双端点同 URL 只 1 源、`MULTI` 不触发（AC-T4）。
- `DeepPropertiesTest`：新字段默认值、`effectiveTavilyRelay*` 兜底链。

## 风险检查点（实现期）

| 检查点 | 何时 | 判定 |
|---|---|---|
| official 零请求 | relay 成功时 | 单测断言 |
| 短超时生效 | 延迟端点 | <9s 返回并切换 |
| 身份不虚高 | 融合测试 | 同 URL 1 源 |
| 零回归 | 未配 relay | 路径/参数等价 |

## 回滚点

- 运行时：不配 `DEEP_TAVILY_API_BASE_URL` → 回单官方端点。
- 代码：revert `TavilySearchTool`/`DeepProperties` 改动。

## 交付物

- 双端点 `TavilySearchTool`、配置项、质量门、观测增量、spec 同步、单测全绿。
- 不包含：fanout/预算/补检索；extract 双端点化。
