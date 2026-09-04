# 设计:统一检索(unified-retrieval)

## 1. 总体

检索层从「两个隔离源 + 手动关联门禁」改为「**一个向量空间 + 类型元数据 + 锚点加权**」:

```
改造前: modelIds 非空? ──否──> 仅 KB(独立配额) ──低分──> LOW_CONFIDENCE 全抛弃
        └─是─> 车型域(model_id 过滤) ──> 分层配额   (两个独立结果合并)

改造后: query ─> searchTopKUnified(全库 UNION) ─> 锚点加权(可选) ─> 统一配额选择 ─> 来源行内标注
        关联车型仅影响权重(锚点),不再决定「能不能查」
```

## 2. 统一检索 SQL

```sql
SELECT * FROM (
  -- 车型域
  SELECT 'CAR' AS "source", e.doc_id AS "docId", d.model_id AS "modelId",
         d.chunk_type AS "chunkType", d.chunk_text AS "chunkText",
         1 - (e.embedding <=> #{queryVec}::vector) AS "score", m.name AS "modelName"
  FROM sparkora_car_doc_embedding e
  JOIN sparkora_car_doc d ON d.id = e.doc_id AND d.deleted = 0
  JOIN sparkora_car_model m ON m.id = d.model_id
  -- KB 域
  UNION ALL
  SELECT 'KB' AS "source", e.chunk_id AS "docId", NULL AS "modelId",
         'KB_CHUNK' AS "chunkType", c.chunk_text AS "chunkText",
         1 - (e.embedding <=> #{queryVec}::vector) AS "score", d2.title AS "modelName"
  FROM sparkora_kb_chunk_embedding e
  JOIN sparkora_kb_chunk c ON c.id = e.chunk_id
  JOIN sparkora_kb_doc d2 ON d2.id = c.doc_id AND d2.deleted = 0 AND d2.enabled = TRUE
) u
ORDER BY "score" DESC LIMIT #{limit}
```

- 性能:两域各自走 ivfflat 索引,UNION ALL 后排序;库量级(车型块 ~380 + KB 块 <1k)无压力;若后续量级上 10k 再改两阶段召回(先各取 topM 再合并)。
- 追加列 `modelName`(车型名/知识标题)供来源行内标注,免二次查询。

## 3. 服务层(`CarRagService`)

```java
public RagResult retrieveForGeneration(String query, int topK, List<Long> anchorModelIds)
// 内部:
//   1) 主查询 searchTopKUnified(query, EXPAND=64)  // 过采样,配额/加权后再截
//   2) 参数级子查询 deriveSubQueries(query) 每个子查询同走 unified,chunkText 去重合并
//   3) 锚点加权:CAR 块 modelId∈anchor → score *= aiProps.getRagAnchorBoost()
//   4) minScore 逐块过滤 → rawHit/maxScore → 四态判定(语义不变)
//   5) 配额:核心块(PARAM_GROUP/MODEL_INFO)优先 + RIGHTS/FEATURE ≤1/3 + KB 独立配额 kbTopK
//   6) 行内来源标注:CAR→「【车型数据:<modelName>】」、KB→「【通用知识:<title>】」;
//      首行「知识来源:…」按命中构成生成(车型数据/通用知识库/两者)
```

- 旧签名 `retrieveForGeneration(modelIds, query, topK)` 保留:`return retrieveForGeneration(query, topK, modelIds)`(BriefService/VersionService B 阶段才改,本任务不动调用方——**直接改主签名让调用方传 anchor**:`listModelIds(projectId)` 结果作为 anchor 传入,一行改动)。
- `/api/car/rag` 内部问答:按 modelId 精确问答仍走原 `searchTopK(modelId,…)`,不动。
- `AI_RAG_KB_ENABLED=false` 回退语义:统一检索照跑,仅 KB_CHUNK 块在配额层被排除(等价旧行为)。

## 4. 前端

- `ProjectEdit.vue` / `ProjectDetail` 车型关联区文案:「写作锚点(可选):关联后相关车型数据优先引用」;交互不变。
- 无其他前端改动(Brief/Version 展示位复用 S6.1 状态标签)。

## 5. 配置

| 变量 | 默认 | 说明 |
|---|---|---|
| `AI_RAG_ANCHOR_BOOST` | `1.15` | 锚点车型块分数加权系数 |
| `AI_RAG_KB_TOPK` / `AI_RAG_KB_ENABLED` | 4 / true | 沿用 |

## 6. 测试设计(CarRagServiceTest 扩展)

- 统一检索返回混合源 → 合并/排序/来源行内标注正确。
- 锚点加权:同分块锚点车型排前;无锚点行为与 S7 一致。
- 旧签名委托:结果与新签名(锚点=modelIds)一致。
- 四态回归:FAILED/LOW_CONFIDENCE/NO_KNOWLEDGE 用例不动仍绿。
- FakeMapper 增加 `searchTopKUnified` 假实现(返回带 source 行)。

## 7. 回滚

- 纯查询层:revert 单提交即回 S7 行为;无数据迁移。
- 风险:锚点加权可能让低相关高权重块挤占配额——配额仍按分数排序,加权仅重排;观察文章 18/17 回归效果再调系数。