# 车型库泛化为通用汽车知识库

## Goal

新建通用汽车知识文档表与向量化链路,`CarRagService` 升级为「车型域 + 通用域」双源统一检索;文章生成(简报/正文)不再以「项目关联车型」为知识检索前提,未关联车型也必查通用知识库,支撑非车型对比类主题。

## 背景(代码取证 2026-09-04)

- 全链路以 `model_id` 为主键贯穿:`sparkora_car_doc(_embedding)` 表结构、`CarDocService` 切块、`CarRagService.searchTopK(modelId, …)`、`retrieveForGeneration(modelIds, …)`。
- `BriefService`/`VersionService` 均为 `modelIds.isEmpty() → RagResult.EMPTY`:未关联车型 = 零知识注入,通用主题无知识可用。
- S6.2 已有可复用资产:块类型分层配额、参数级子查询、覆盖度声明、RagStatus 语义(spec §6b)。

## Requirements

- **R1 数据层**:`schema.sql` 幂等新增 `sparkora_kb_doc`(原始知识文档)/`sparkora_kb_chunk`(切块)/`sparkora_kb_chunk_embedding`(向量)三表;entity + mapper 同步。
- **R2 入库服务**:`KbDocService` — 手工知识(标题/领域标签/正文)→ 切块(首行带标题,段落优先、长度兜底)→ 逐块 embedding 入库;更新/删除/重建向量幂等(先清后插)。
- **R3 API**:`/api/kb/docs` 列表/详情/新建/编辑/删除/重建向量;`@PreAuthorize` ADMIN/EDITOR 写、VIEWER 只读;`R<T>` 包装;`@Valid` DTO。
- **R4 前端**:知识库管理页(列表/新建/编辑/删除/重建向量,展示领域标签与块数);路由 + 菜单入口;移动端单列、触控目标 ≥44px。
- **R5 统一检索**:`CarRagService` 新增通用域检索(全库 topK,无 model_id 约束);`retrieveForGeneration` 双源合并——关联车型走既有分层配额链路,通用域独立配额;上下文块来源可区分。
- **R6 生成链路**:`BriefService`/`VersionService` 改为「车型对象存在与否都必查」:未关联车型 → 仍检索通用域;状态判定按设计文档的多源规则演进,对外四态语义不变。
- **R7 配置**:新增 KB 检索 topK 等环境变量占位,同步 `.env.example`。

## Acceptance Criteria

- [ ] AC1 未关联车型的项目生成简报与正文时发起知识库检索;命中时上下文注入且前端展示「知识库 · 已引用」(沿用 S6.1 展示位)。
- [ ] AC2 KB 文档新增/编辑/删除后重建向量,检索结果正确反映变化(幂等,不留旧块)。
- [ ] AC3 已关联车型项目:车型参数块与通用 KB 块可同时进上下文,且注入文本能区分「车型数据」与「通用知识」来源。
- [ ] AC4 降级语义与 S6.1 一致:检索失败 → FAILED 降级不阻断;整体低置信 → LOW_CONFIDENCE 全部抛弃;无任何命中 → NO_KNOWLEDGE。
- [ ] AC5 `mvn -q -DskipTests compile` 绿;`CarRagServiceTest` 扩展(双源合并/来源标注/多源失败判定)全绿;新增 `KbDocServiceTest`(切块/幂等)绿。
- [ ] AC6 `npm run build` 绿;知识库页移动端可用。

## Constraints

- 不改 `sparkora_car_*` 既有表结构与清洗链路(归 `09-04-kb-clean-audit`)。
- S6.1「必查但降级可见」与 S6.2 检索策略保持向后兼容:仅扩展,不回退既有行为。
- 建表走 `schema.sql` 幂等写法;spec 同步 §6b/新增 §6c 字段级表格。

## Notes

- 技术方案见 `design.md`;执行清单见 `implement.md`。
- 现状链路调研见 `research/current-chain.md`。