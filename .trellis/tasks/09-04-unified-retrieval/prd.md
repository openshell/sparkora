# 统一知识库检索:去车型关联门禁(阶段 A)

## Goal

`car_doc` 与 `kb_chunk` 两个向量域合并为**同空间全库检索**,「项目关联车型」从检索门禁降级为写作锚点加权——文章 18 类「数据在库却查不到」的误伤消除,同时为阶段 B 子代理研究提供统一检索底座。

## 背景(research/current-state.md 有完整取证)

- 文章 18(未关联车型)生成时 KB 最高分 0.316<0.5 → LOW_CONFIDENCE 全抛弃;而海狮08EV/DM-i(车型 55/56)48 个含价格/尺寸的块在库内未被检索。
- 现状检索入口:`retrieveForGeneration(modelIds, query, topK)`,modelIds 空且 KB 无命中 → 低置信;检索 SQL 按 model_id 过滤。

## Requirements

- **R1 统一检索 SQL**:`CarDocEmbeddingMapper.searchTopKUnified(queryVec, limit)` — 车型域与 KB 域 UNION ALL 同空间排序,返回 {source(CAR/KB), chunkType, modelId?, docId, chunkText, score};仅含 deleted=0 / enabled 块。
- **R2 检索服务重构**:`CarRagService.retrieveForGeneration(query, topK, anchorModelIds)`(anchorModelIds 可空):
  - 全库检索一次(S6.2 参数级子查询保留,子查询同走统一检索);
  - 锚点加权:source=CAR 且 modelId∈anchorModelIds 的块分数 ×1.15(可配 `AI_RAG_ANCHOR_BOOST`);
  - 配额沿用:PARAM_GROUP/MODEL_INFO 优先、RIGHTS/FEATURE ≤1/3、KB_CHUNK 独立配额 `AI_RAG_KB_TOPK`;
  - 来源标注改为按块行内标注(`【车型数据:海狮08EV】`/`【通用知识】`),上下文首行来源行按实际命中构成生成;
  - 四态判定语义不变;coveredText 仅统计 PARAM_GROUP。
- **R3 旧签名兼容**:`retrieveForGeneration(modelIds, query, topK)` 保留为委托(锚点=modelIds),`/api/car/rag` 内部问答接口行为不变。
- **R4 前端文案**:项目编辑/详情页车型关联改「写作锚点(可选)」,不强制;不删功能。
- **R5 配置**:`AI_RAG_ANCHOR_BOOST`(默认 1.15)入 AiProperties/application.yml/.env.example。

## Acceptance Criteria

- [ ] AC1 未关联车型项目(复用项目 18 主题「深度分析海狮08定价逻辑」)生成简报:ragStatus=OK,上下文含海狮08 价格/尺寸块,来源行标注车型数据。
- [ ] AC2 已关联车型项目(项目 17 或新建关联海狮08EV)生成:锚点车型的块排序优先(对比不加权顺序)。
- [ ] AC3 检索失败→FAILED、低置信→LOW_CONFIDENCE、无命中→NO_KNOWLEDGE 语义不变(单测覆盖)。
- [ ] AC4 `mvn -q -DskipTests compile` 绿 + `CarRagServiceTest` 扩展(统一检索/锚点加权/来源标注/旧签名委托)全绿。
- [ ] AC5 spec §6b/§6c 检索契约更新为统一检索语义。

## Constraints

- 不改 `sparkora_car_*`/`sparkora_kb_*` 表结构(纯查询层改动)。
- S6.2 子查询、S6.1 四态、KB 开关(`AI_RAG_KB_ENABLED`)全部保留语义。
- 不动 B 阶段范围(多代理流程/澄清/事实手册)。

## Notes

- 复杂度评估:中等(检索 SQL + 服务重构 + 测试),PRD+design.md+implement.md 三件套。
- 现状调研见父任务 `research/current-state.md`。