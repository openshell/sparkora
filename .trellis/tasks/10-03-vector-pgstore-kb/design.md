# Design — 向量层 PgVectorStore 迁移 + 知识库规范化

> 配套 `prd.md`（需求/验收/决策）。本文记技术设计：边界、契约、数据流、兼容/迁移、权衡、回退。
> 子任务各自另有 `design.md`（如需）。

## 1. 已决前提（来自 prd 决策）

- D1 单张 `vector_store` + `metadata.domain`；D2 KB 仅数据模型；D3 切块重叠纳入；D4 两阶段对拍；
  D5 rerank 不做。

## 2. 目标架构与边界

### 2.1 分层

```
web.controller（CarModel/News/KbDoc/Image/Deep/Qa …，零改动）
      │
domain.service（CarRagService.retrieveForGeneration、CarDocService、KbDocService、
                NewsDocService、ImageEmbeddingService、FactSheetService、QaService …）
      │  经 VectorStore 抽象（新）+ 业务层（保留：锚点/配额/门槛/四态/覆盖度）
      ▼
Spring AI PgVectorStore（单表 vector_store，metadata filter）
      │
      ▼
PostgreSQL + pgvector（VECTOR(1024)、HNSW cosine）
```

- **替换层**：仅「存储 + 相似度检索」。`content` = `chunk_text`；`metadata` 承载一切过滤/回查维度。
- **保留层**：`CarRagService` 的业务规则（锚点加权 / 分层配额 / 门槛 / 四态 / 子查询 / 覆盖度）
  与 `FactSheetService` 合并算法，**语义不变**。
- **关系源保留**：doc/chunk 主表仍是权威；store 是「带 content 的向量索引」，`metadata.refId` 回指。

### 2.2 组件映射（现状 → 目标）

| 现状 | 目标 | 子任务 |
|---|---|---|
| `CarDocEmbeddingMapper.searchTopKUnified`（4 段 UNION + JOIN 活表） | `VectorStore.similaritySearch` 每域一次 + Java 合并 | E1 |
| `sparkora_{car_doc,kb_chunk,news_doc,image}_embedding`（4 表） | 单张 `vector_store`（`metadata.domain`） | E1 |
| JOIN `deleted`/`enabled` 活表过滤 | metadata `active` + 同步层 | E1 |
| `embedding_model` 列过滤 | metadata `embeddingModel` filter | E1 |
| `TextChunker`（≤500，无重叠） | + 滑动重叠参数 | E2 |
| KB `domain` 自由文本 | 受控词表 + source/tags/生效期 | E3 |
| `sparkora_car_doc`（实为块表） | 块语义命名 | E4 |
| `coveredText` 仅 CAR PARAM_GROUP | 三域统一 | E5 |
| 相同 chunk 重复嵌入 | content_hash 去重/缓存 | E5 |

## 3. 关键契约与数据流

### 3.1 Store metadata schema（E1 核心契约）

```jsonc
{
  "domain": "CAR" | "KB" | "NEWS" | "IMAGE",
  "refId": 123,              // 域内 id：CAR=car_doc.id / KB=kb_chunk.id / NEWS=news_doc.id / IMAGE=image_asset.id
  "modelId": 45,             // 仅 CAR：锚点加权用；其余 null
  "chunkType": "PARAM_GROUP" | "KB_CHUNK" | "NEWS_BODY" | "IMAGE",
  "name": "海狮08EV" | "知识标题" | "新闻标题",   // 行内来源标注 / 回查
  "active": true,            // 活表态：软删/停用同步为 false
  "embeddingModel": "Qwen3-Embedding-8B"          // 模型过滤
}
```

- `content` = `chunk_text`（行内注入正文）。
- **域隔离候选窗口**：`retrieveForGeneration` 对 CAR/KB 合并窗、NEWS 独立窗各调一次
  `similaritySearch(filter: domain in [...], topK=limit)`，Java 侧合并——复现「按域隔离」语义
  （避免 NEWS 1339 块挤占 CAR/KB 候选）。
- **活表同步（R3）**：软删/停用/重建时按 `refId` 更新 metadata `active`。**关键风险点**：
  Spring AI `VectorStore` 无「按 metadata 更新」API → 需 `delete` + `add` 重建该块，或直接 SQL
  更新 `vector_store.metadata`（用 `getNativeClient()` JdbcTemplate）。**E1 需在 design 定案并测试**。

### 3.2 两阶段对拍（D4）

- **阶段 A**（E1，切块不变）：旧路径 vs store 路径，同 query 集逐条比
  候选集/分数/排序/四态/配额后 selected。允许的差异仅：浮点末位、UUID 顺序。
  Harness：固定 query 集（三域代表 + 锚点 + 子查询）× 固定期望，落 `research/parity-A.md`。
- **阶段 B**（E2，切块重叠）：不逐条对拍，验语义合理（命中集合理包含旧命中、分数分布不崩、四态不恶化）
  + 改进观测，落 `research/parity-B.md`。

### 3.3 命名/规范化/覆盖度（E3/E4/E5）

- E4：`ALTER TABLE sparkora_car_doc RENAME TO sparkora_car_doc_chunk`（或等价块语义名）+ 索引名；
  同步 entity/mapper/引用/文档。对外 API 不变。
- E3：`sparkora_kb_doc` 加 `source`/`effective_from`/`effective_to`（可空）；`domain` 受控词表
  （代码常量，同 `NewsImageClassifier` 先例）；写侧 normalize、检索侧 metadata filter。
- E5：`coveredText` 三域统一；`content_hash` 去重/嵌入缓存（同模型才复用）。

## 4. 兼容 / 迁移批次

1. **E1 建表 + 回填**：Flyway 迁移建 store 表（含 GIN/index）；4 域回填 runner。
2. **E1 对拍**：阶段 A harness 通过 → 切读路径（`CarRagService` 走 store）。
3. **E2 切块重叠**：`TextChunker` 改 + 全库重嵌 + 阶段 B 验收。
4. **E3/E4/E5**：在 E1（必要时 E2）之上分头落地。
5. **父任务集成**：跨域端到端回归 + 旧表删除（**最后**，且需再次确认）。

**回退**：旧 4 表全程保留；每批独立 `git revert`。store 切读前旧路径仍可运行。

## 5. 权衡与回退

- **单表 vs 多 store**：单表统一 ETL/对账/换模型重建，但 metadata 需冗余活表态与回查字段；
  业务 JOIN 消失，改为 metadata filter + 应用层回查。
- **维度固化**：`vector_store` 维度建表固化；换模型若变维需重建表（现方案加列过滤即可）。
  缓解：保持 1024 维约定；变维走重建流程并文档化。
- **同步层是最大风险**：`active` 一致性依赖写入路径全覆盖；漏一处 → 失效块仍被检索。
  缓解：E1 用「回填即全量置 active + 软删/停用/重建三路径显式同步 + 对拍含活表用例」。
- **rerank/运营能力**：明确不做，避免范围失控。

## 6. 运维 / 回退要点

- Flyway 新迁移；不得改已应用脚本。store 表由 Flyway 建，不依赖 `initialize-schema=true`。
- 回填异步 + `REQUIRES_NEW` + 异常全吞（复用 `EmbeddingBatchRunner` 范式）。
- 密钥 `.env`；不打印向量/密钥。
- 旧表删除放最后且需显式确认；回滚 = 保留旧表 + revert 切读提交。
