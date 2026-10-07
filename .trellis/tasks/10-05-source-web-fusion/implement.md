# implement.md — F: 外部搜索与本地信源融合

> 依赖：`10-05-source-domain-retrieval`（E 本地信源可检索）+ `10-04-brief-retrieval-sources` 至少 A 落地。
> 共享契约以父 `../10-05-self-hosted-sources/design.md` §2 与本文 `design.md` 为准。

## 实施顺序

1. **依赖核对（卡点）**：确认 E 的 `Citation` **已带 `sourceType`**（AC-E9）——这是本任务的硬前置；
   若 E 只改了 `UnifiedHit`，`KnowledgeSearchTool` 拿不到字段，本任务无法实现。确认 10-04 的 `usedProviders`/`SearchMeta.providers` 已就位。
2. **`SearchHit` 加 `SOURCE`**：新增工厂 + 字段（`sourceType`/`authorityTier`，兼容构造器保持旧调用）。
3. **`KnowledgeSearchTool`**：NEWS 域 `user-source` 返回 `SOURCE`，`byd-news` 保持 `KB`。
4. **`validateFacts` 白名单**：`KB|WEB|SOURCE`；SOURCE 无 url/sourceId → 直接接受（同 KB 路径）；带 url/sourceId → 按 WEB 严格核验（拒绝自造 URL）。
5. **`FactSheetService`**：插入 SOURCE 分支（§4）、权威档（§3，默认 off）、跨 type 同 URL 去重（§5）；
   `distinctSources` 计算前**剔除 `crossCounted=false` 的来源**（盖世排行页），该标志随 `source` 透传（design §4）。
6. **可观测**：`SearchMeta`/`fact_sheet` 增量字段；前端徽标（增量）。
7. **配置**：`DEEP_SOURCE_AUTHORITY_ENABLED` 等进 `.env.example`。
8. **文档**：`brief-generation.md` §5、`retrieval.md` 同步。
9. **测试**（见下）→ 全绿后交 check。

## 实现期第一件核对事项

- **[核对] 零回归**：未启用自建信源时无 `SOURCE` 命中；`hasKb&&hasWeb`/MULTI/KB/else 四分支逐位等价现状。
- **[核对] 不虚高**：构造「本地 SOURCE + 外部 WEB 同 URL」与「Tavily 双端点同 URL」，`sourceCount` 均为 1。
- **[核对] BYD**：`byd-news` 命中的 type 仍为 `KB`（保持现状），不因新增分支改变。

## 验证命令

```bash
mvn -q -DskipTests compile
mvn test                                    # 现有 842 例全绿
npm run build                               # 前端徽标改动
```

## 测试清单

- `SubAgentRunnerTest`（增）：`SOURCE` 事实**不被拒**；`type` 误标 `KB` 的 URL 事实仍按 WEB 校验（反例）。
- `FactSheetServiceTest`（增）：
  - SOURCE 胜 WEB、进 `alternatives` + warning（AC-F1）；
  - KB 胜 SOURCE（不误伤）；
  - 跨 type 同 URL 去重、`sourceCount` 不虚高（AC-F2）；
  - 同源多通道 / Tavily 双端点不触发 MULTI（AC-F3）；
  - 权威档 official 0.9 / industry 0.7 / ugc 0.5；默认 off 保守档（AC-F7）；
  - 未启用零回归（AC-F5）。
- `KnowledgeSearchToolTest`（增）：`user-source` → SOURCE；`byd-news` → KB。
- 前端：`npm run build` 通过；徽标为增量字段。

## 风险检查点（实现期）

| 检查点 | 何时 | 判定 |
|---|---|---|
| SOURCE 不被拒 | validateFacts 改后 | 反例单测通过 |
| 不误当 KB 0.9 | FactSheet 改后 | SOURCE 走权威档 |
| 不虚高 | 去重改后 | 双通道 1 源 |
| KB 不被 SOURCE 压 | 优先级改后 | KB 仍胜 |
| 零回归 | 默认关 | 四分支逐位等价 |

## 回滚点

- 运行时：不启用自建信源 + 权威档 off → 等价现状。
- 代码：revert `SearchHit`/`FactSheetService`/`validateFacts` 改动（无 DB 迁移）。

## 交付物

- `SOURCE` 类型、白名单扩展、融合分级、权威档、可观测增量、前端徽标、spec 同步、单测 + build 全绿。
- 不包含：provider/fanout/补检索（10-04）、采集入库（B/E）。
