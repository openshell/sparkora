# 知识域 · 通用汽车知识库（KB）

> 回链：[系统说明总览](../../README.md) ｜ 上级：[知识库检索](../retrieval.md)

职责：手工录入的通用汽车知识（KB 域）的入库/切块/向量化，以及**三域统一检索**（CAR+KB+NEWS 同向量空间 UNION）的实现契约。

> S7「KB 双源检索」（2026-09-04）；S8 统一检索升级（2026-09-04）。三域状态机与降级语义见 [retrieval.md](../retrieval.md)。

---

## 1. 语义

知识来源从「仅车型域」扩展为**双源**——车型域（BYD 同步，既有）+ 通用域（手工录入知识，新增）。项目**未关联车型时生成（简报/正文）仍必查通用域**，不再零注入；已关联车型时双源独立配额合并。四态状态机与降级语义（[retrieval.md](../retrieval.md)）不变。

---

## 2. 数据层（schema.sql S7 区块，幂等）

| 表 | 字段 | 说明 |
|---|---|---|
| `sparkora_kb_doc` | id / title(≤200) / domain(默认「通用」) / content / enabled / created_by / 审计字段 / deleted | 手工知识条目；逻辑删 |
| `sparkora_kb_chunk` | id / doc_id FK / seq / chunk_text / created_at | 检索块；`chunk_text` 首行固定「知识：<title>（<domain>）」 |
| `sparkora_kb_chunk_embedding` | id / chunk_id FK / embedding vector(1024) / created_at | 向量；**C1 起 HNSW cosine（`idx_kb_chunk_emb_vec_hnsw`，与车型域 `idx_car_doc_emb_vec` 统一；旧 IVFFLAT 索引已幂等 DROP）** |

---

## 3. 服务与切块

`com.sparkora.kb.service.KbDocService` — create/update/delete/list/get/rebuild：

- 切块：空行分段、单段 ≤500 字符、超长按句读（。；；！？）切分合并、段内换行转空格。
- 重建幂等（先物理清 chunk+embedding 再重嵌）；embedding 单块失败 warn+计数（`EmbedStats` total/success/failed），块缺失用 rebuild 补齐。
- `enabled=false` 时清块。

---

## 4. API（`/api/kb`，@PreAuthorize：读=三角色，写=ADMIN/EDITOR）

| Method | Path | 说明 |
|---|---|---|
| GET | `/api/kb/docs` | 列表（含 `chunkCount`） |
| GET | `/api/kb/docs/{id}` | 详情（含 `content`） |
| POST | `/api/kb/docs` | 新建（`@Valid KbDocSaveDto`，自动切块向量化） |
| PUT | `/api/kb/docs/{id}` | 编辑（自动重建；`enabled=false` 清块） |
| DELETE | `/api/kb/docs/{id}` | 删除（逻辑删文档 + 物理清块） |
| POST | `/api/kb/docs/{id}/rebuild` | 手动重建，返回 `{total, success, failed}` |

- 前端：`views/KbLibrary.vue`（列表卡片/新建编辑抽屉/删除确认/重建向量含失败提示；移动端单列），TopBar「知识库」入口；`api/index.js` 的 `kbApi`。

---

## 5. 统一检索契约（S8，2026-09-04；S7 双源语义由本节取代）

| 项 | 行为 |
|---|---|
| 统一检索 | `searchTopKUnified(queryVec, limit)`：车型域与 KB 域（及 NEWS 域）**UNION ALL 同向量空间全库检索**，按余弦分排序；返回行带 `source(CAR/KB/NEWS)`/`modelId`/`chunkType`/`modelName`。「项目关联车型」**不再是检索门禁**——未关联车型也全库检索（修复文章18 类误伤：数据在库却因未关联查不到） |
| 锚点加权 | 项目关联车型降为**写作锚点**：CAR 块 `modelId ∈ anchor` → `score × AI_RAG_ANCHOR_BOOST`（默认 1.15，上限 1.0 截断）重排；~~前端项目编辑页改「写作锚点车型」文案~~（**2026-09-09：创建页车型选择入口已移除**——创作不与车型绑定，知识库停用期间该字段无生效点；后端关联逻辑与锚点加权保留，存量项目不受影响；新项目无 anchor 即全库无加权） |
| 配额 | 核心块（`PARAM_GROUP`/`MODEL_INFO`）优先、`RIGHTS`/`FEATURE` ≤1/3、`KB_CHUNK` 独立配额 `AI_RAG_KB_TOPK`；`AI_RAG_KB_ENABLED=false` 时 KB 块在配额层排除（等价 S6 行为，检索仍跑） |
| 来源标注 | 行内前缀「【车型数据：名称】」/「【通用知识：标题】」/「【官方新闻：标题】」；首行「知识来源：…」按命中构成生成 |
| 子查询 | S6.2 参数级子查询保留，子查询同走统一检索 |
| 状态判定 | 检索异常（单路统一检索）→ `FAILED`；`rawHit==0` → `NO_KNOWLEDGE`；`maxScore<reject` → `LOW_CONFIDENCE`；其余 `OK`（S6.1 四态语义不变） |
| 覆盖度声明 | `coveredText` 仅统计 CAR 域 `PARAM_GROUP` 块 |

- **候选窗口按域隔离（C2 check 修复）**：CAR+KB 合并取 top-`limit`（与 C2 前完全一致），NEWS 单独取 top-`limit`；不可三者共用一个全局 `LIMIT`——新闻块（≈1300+）与车型/KB 同向量空间且语义邻近时会占满整个窗口，把 CAR/KB 完全挤出候选（实测 BYD 新闻类 query CAR 候选从 32 掉到 0），使下游独立配额失效。详见 [news.md §5](news.md)。
- **图片域（第四域）是同空间但独立检索**：`sparkora_image_embedding` 与三域同模型同维度，但**不并入 `searchTopKUnified`**（图片查询是独立入口 `POST /api/images/search`）。

**配置**：`AI_RAG_KB_TOPK`（默认 4）/ `AI_RAG_KB_ENABLED`（默认 true），见总览[配置总览](../../README.md)与 [retrieval.md §4](../retrieval.md)。

---

## 6. 关键实现路径

- 后端：`com.sparkora.kb.service.KbDocService`、`web.controller.KbDocController`、`domain.entity.KbDocEntity`/`KbChunkEntity`、`mapper.KbChunkEmbeddingMapper`、`car.service.CarRagService.retrieveForGeneration`（统一检索消费方）、`mapper.CarDocEmbeddingMapper.searchTopKUnified`（UNION SQL）。
- 前端：`views/KbLibrary.vue`。
- 表：`sparkora_kb_doc` / `sparkora_kb_chunk` / `sparkora_kb_chunk_embedding`。

---

## 7. 已知限制

- KB 块不参与锚点加权（无 `modelId`）。
- `AI_RAG_KB_ENABLED=false` 只是配额层排除，检索仍会跑（浪费一次向量查询）。
- 「项目关联车型」入口已从创建页移除，存量项目 anchor 仍生效。
