# implement.md — E: 信源入库与检索接入

> 依赖：`10-05-source-crawl-base`（先完成 B 的 `sparkora_news*` 落库）。
> 共享契约以父 `../10-05-self-hosted-sources/design.md` 与本文 `design.md` 为准。

## 实施顺序

1. **前置核对**：确认 B 的 `sparkora_news.source_id` / `sparkora_news_doc` / `_embedding` 已就绪；
   确认 `news_doc.id` 是 `domain=NEWS` 向量的 `refId`。
2. **切块重载**：`TextChunker` 新增 `preserveNewlines` 重载（默认 false，旧行为不变）。先写单测锁定旧行为。
3. **入库**：`SourceDocService`（仿 `NewsDocService`：切块→`_doc`→embedding→`vector_store.upsert(domain=NEWS, refId=news_doc.id)`）；
   `metadata` 写 `sourceType`/`category`/`publishDate`。embedding 在事务外、`REQUIRES_NEW` 持久化。
   **同时改 `NewsDocService`（BYD 路径）写 `sourceType=byd-news`**——否则上线后新增 BYD 块无 sourceType，配额二分失效（V14 只回填存量）。检索二分把 `sourceType==null` 兜底为 `byd-news`。
4. **metadata 迁移 `V14__news_source_metadata.sql`**：回填 `vector_store` NEWS 行（仅补缺键，**不动 id**）。
5. **透传字段（P0，两处都要）**：`UnifiedHit`（`CarRagService:69`）**与 `Citation`（:87）**都加 `sourceType`/`category`（可空 + 兼容构造器）；
   `SearchStore`/`VectorStoreService` 从 metadata 读出；组 `cites`（:418）与 `toUnified`（:466）同步读。
   **核对点**：改造后从 `CarRagService.retrieveForGeneration` 拿到的一条 NEWS user-source citation，其 `sourceType` 能一路传到 `KnowledgeSearchTool`（否则 F 失效）。
6. **检索二级隔离**：`CarRagService` NEWS 候选按 `sourceType` 二分 + 独立配额（BYD 原配额、user 新配额默认 0）。
7. **标注**：按 category 细分行内来源 + citations `source`；BYD 分支逐字保留。
8. **配置**：`AI_RAG_SOURCE_TOPK`（默认 0）等进 `.env.example`。
9. **文档**：`news.md` §5、`kb.md` §5、`retrieval.md` §4.2/§11 字段级同步。
10. **测试**（见下）→ 全绿后交 check。

## 实现期第一件核对事项

- **[核对] 向量 id**：确认 `SourceDocService` 调用 `upsert` 时 `domain="NEWS"`、`refId=news_doc.id`；
  **没有任何地方**改 `domain` 名或用独立 id。用一条断言锁定 `VectorStoreService.docId("NEWS", n)` 与 B 落库一致。
- **[核对] BYD 等价**：先跑 BYD 检索对拍基线（改造前），改造后逐位比对命中/标注/配额。

## 验证命令

```bash
mvn -q -DskipTests compile
mvn test                                    # 现有 510 例全绿
```

## 测试清单

- `TextChunkerTest`（增）：`preserveNewlines=false` 逐字等价旧行为；`true` 保留行结构。
- `SourceDocServiceTest`：切块→入库；`refId=news_doc.id`；metadata 含 sourceType/category；embedding 失败不阻断。
- `CarRagServiceTest`（增）：NEWS 二级隔离（BYD 不被 user 源挤占）；`AI_RAG_SOURCE_TOPK=0` 零回归；category 标注正确；BYD 逐字等价。
- `VectorStoreServiceTest`（增）：V14 回填后 `domain=NEWS` 行有 sourceType/category；id 不变。
- 回归：四态、`ragMinScore`/`ragRejectScore` 判定不变。

## 风险检查点（实现期）

| 检查点 | 何时 | 判定 |
|---|---|---|
| 向量 id 不失配 | 迁移+入库后 | `docId("NEWS", n)` 与 B 的 news_doc.id 对应；无重嵌 |
| BYD 不被挤占 | 二级隔离后 | 构造 BYD+user 并发命中，BYD 配额取满 |
| 表格不丢 | `SourceDocServiceTest` | `preserveNewlines=true` 行结构保留 |
| 四态不变 | 回归 | 门槛分仍作用于原分 |
| 未启用零回归 | `AI_RAG_SOURCE_TOPK=0` | 选择结果逐位等价 |

## 回滚点

- 运行时：`AI_RAG_SOURCE_TOPK=0` + 停采集 → 零回归。
- 代码/迁移：`git revert` V14（metadata 残留无害）；`TextChunker` 新重载默认 false 可独立回退。

## 交付物

- `TextChunker` 重载、`SourceDocService`、`UnifiedHit` 字段、`CarRagService` 二级隔离、`V14` 迁移、spec 同步、单测全绿。
- 不包含：采集（B）、UI（U）、融合规则（F）。
