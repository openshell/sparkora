# P1 架构一致性整改（父任务）

## Goal

管理 2026-09-27 设计评审 P1 六项架构一致性问题的任务映射、拆分决策与整合验收。父任务本身无直接实现工作。

## Background（评审证据）

| # | 问题 | 关键事实 |
|---|---|---|
| ④ | 生成链路同步/异步双轨 | 深度研究已 async+轮询（ClarifyService 占位行范式），但 deep/generate、imitation/analyze、generate/versions、publish、qa ask 仍同步阻塞（前端 300s 超时硬扛） |
| ⑤ | 状态机推进逻辑散落四处 | VersionService/DeepController/PublishService/schema.sql 启动回填；09-10-versions-page-fix 修过的 bug 与 09-27 P0-②（8 处 updateById 回写）皆为其分散产物 |
| ⑥ | schema.sql 兼职迁移工具 | 每次启动执行 + 全表 UPDATE 回填；无版本号、无回滚 |
| ⑦ | JSON 全存 TEXT | fact_sheet/rag_citations/citations/image_refs 等未用 JSONB；body_image_ids VARCHAR(1000) 逗号列违反 1NF |
| ⑧ | 知识域写入侧三套重复 | CAR/KB/NEWS 检索已统一 searchTopKUnified，写入侧（切块/embedding/删除级联）三套重复；1024d 同空间不存模型名，换模型旧向量静默污染 |
| ⑨ | 巨石组件 | ArticleProjectController 547 行注入 10 服务；前端 StepPreview.vue 994 行、ImageLibrary.vue 907 行 |

## Task Map（子任务）

| 子任务 | 状态 | 说明 |
|---|---|---|
| 09-27-state-machine-service（⑤） | ✅ 已归档（2026-09） | 状态机推进收敛到 `ProjectStatusService`（commit dcedf49 + 05900ae）；10 个转换逐字等价，345 测试全绿 |
| 09-27-gen-async（④） | ✅ 已归档（2026-09） | 三条项目状态链路异步化（imitation/analyze、generate/versions、deep/generate）+ deep 批量 styleIds[] 补 claim；移除 advanceVersionsReadyFromReady；370 测试全绿 |
| 09-27-flyway-migration（⑥） | ✅ 已归档（2026-09） | 引入 Flyway 替代 schema.sql 兼职：V1 基线逐字固化、既有库 BSLN@1 跳过、空库 V1 自举；隔离 pgvector 容器端到端验证 AC3/AC4/AC5 + R4 前向迁移；370 测试全绿 |
| 09-27-jsonb-normalize（⑦） | ✅ scope A 实现完成（待 check/归档） | **JSONB 复核判定「有意约定，不予处置」**；只做 `body_image_ids` 规范化为 `sparkora_article_version_image` 关联表（Flyway V2：建表+保序回填+DROP 列）；`LIKE` 粗筛与两份 `split(",")` 解析消除；JSONB 部分不处置（理由见 database-guidelines.md）；381 测试全绿 |
| 09-27-knowledge-write-unify（⑧） | ✅ 已归档（2026-09） | 切块/并发嵌入/事务边界统一（`TextChunker`/`EmbeddingBatchRunner`/REQUIRES_NEW）+ 向量模型名防护（V3 `embedding_model` 列、检索过滤、维度校验、NEWS 重建端点）；424 测试全绿 |
| ⑨ 拆巨石 | 未建 | 独立；ArticleProjectController 拆分应在 ⑤④ 落地后做（避免拆两次） |

## 依赖与顺序（写进各子任务 prd，不靠树形隐含）

1. ⑤ 先行（正确性核心，无前置依赖）。
2. ④ 依赖 ⑤（异步链路须调用统一状态推进接口）。
3. ⑨ 中 ArticleProjectController 的拆分依赖 ⑤+④（避免拆两次）。
4. ⑥ 先于 ⑦（⑦ 的 DDL 需要 ⑥ 提供的版本化迁移能力）；⑧ 的模型名列可与 ⑦ 同批。

## Cross-child Acceptance Criteria（整合验收，父任务收口时执行）

- [ ] 全部子任务归档后：`grep -rn "projectMapper.update" src/main/java` 仅命中统一状态服务一处直接调用（其余均为服务委托）。
- [ ] 六项问题各自的模块 spec（docs/spec/**、.trellis/spec/**）同步无矛盾。
- [ ] `mvn test` + `npm run build` 全绿。
- [ ] AGENTS.md 当前阶段描述更新。

## Out of Scope

- P0（已由 09-27-p0-hardening 完成）、P2/P3 项（另行任务）。

## Notes

- 父任务不直接实现；每个子任务独立走 plan→implement→check→archive。