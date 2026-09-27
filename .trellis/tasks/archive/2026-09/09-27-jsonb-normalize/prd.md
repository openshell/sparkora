# P1-⑦ body_image_ids 规范化（scope A）

## Goal

将 `sparkora_article_version.body_image_ids`（VARCHAR(1000) 逗号列，违反 1NF、靠 `LIKE` 粗筛做引用检查、两份重复 `split(",")` 解析）规范化为一对多关联表 `sparkora_article_version_image`，走 ⑥ 落地的 Flyway V2 迁移。

**JSONB 部分经复核判定为「项目明文约定，不予处置」**（理由见 Background C），本任务只做规范化，并在 spec 记录裁定。

## Background（代码证据）

### A. TEXT 存 JSON 是项目明文约定（故不转 JSONB）
- `.trellis/spec/backend/database-guidelines.md:299`：「JSON 字段统一 TEXT 列存字符串（后端写入、前端解析），**不引入 JSON 类型处理器**」。
- `ArticleBriefEntity.java:13-14` 类注释同款声明。
- 全实体 JSON 列均为 `String`，全库无 `TypeHandler`/`autoResultMap`（grep 零命中）。

### B. 全库零 JSON-path 查询（故 JSONB 零收益）
- `grep -rn '\->>\|jsonb\|json_array_elements\|stringtype' src/` 仅命中迁移 README 示例命名。
- JSON 列是纯不透明载荷（ObjectMapper 写 / 前端 `JSON.parse` 读），从不参与查询、索引、过滤。

### C. JSONB 迁移成本（拒绝理由）
- JDBC URL 须加 `?stringtype=unspecified`（当前 `application.yml:8` 无任何参数），否则 insert 报 varchar vs jsonb 类型错。
- ~20 个 TEXT JSON 列需 `USING ::jsonb` 迁移 + 空串防御。
- 收益为零（B），纯 churn + 迁移风险。→ **判定不予处置**。

### D. body_image_ids 现状（本任务修复对象）
- 列：`V1__baseline.sql:115` `body_image_ids VARCHAR(1000)`；语义为**有序**图库 id 列表。
- 实体：`ArticleVersionEntity.java:34` `private String bodyImageIds;`（无 `@TableLogic`，该表为物理表）。
- 写：`ImageService.modifyBodyImage`（L735-746）add/remove 后 Java 拼串 + `updateById` 全量回写。
- 读：`ImageService.bodyIdListOf`（L770-776）与 `PreviewService.bodyIdListOf`（L321-325）**各写一份** `split(",")` 解析。
- 引用检查：`ImageService.delete`（L659-670）`like("body_image_ids", id)` 粗筛（注释自认会误匹配 15/51）+ Java 精确过滤。
- 快照 API：`ImageService.projectImages`（L687-714）返回 `bodyImageIds: List<Long>`（前端消费点）。
- 前端消费：`StepPreview.vue:521/537`、`StepPublish.vue:216` 只读 `bodyImageIds` 长度/包含，**不关心存储形态**（快照 API 契约可原样保留）。
- **同项目已有范式**：`database-guidelines.md:124` 明说「多对多关系用独立关联表承载，不往主表加逗号列（`body_image_ids` 那类有序小集合才用逗号列）」；`sparkora_image_tag` 已示范关联表 + 应用层外键 + 无强 FK 的写法。

## Requirements

- R1 新增迁移 `V2__article_version_image.sql`（幂等单语句、无 `DO $$`）：
  - `CREATE TABLE IF NOT EXISTS sparkora_article_version_image`（`id`/`version_id`/`image_id`/`sort_order`/`created_at`；`UNIQUE(version_id,image_id)`；两索引：`version_id`、`image_id`；**不建强 FK**，应用层维护）。
  - 回填：`regexp_split_to_table(body_image_ids, ',') WITH ORDINALITY` → 按原顺序写入 `sort_order`，`ON CONFLICT DO NOTHING`。
  - `ALTER TABLE sparkora_article_version DROP COLUMN IF EXISTS body_image_ids;`
- R2 新增 `ArticleVersionImageEntity` + `ArticleVersionImageMapper`（BaseMapper + 有序查询/按图查引用/删除方法）。
- R3 `ArticleVersionEntity` 移除 `bodyImageIds` 字段。
- R4 `ImageService`：改读写关联表；`modifyBodyImage` 改为 insert（追加 `sort_order`）/ delete（幂等）；`bodyIdListOf` 删除，改查关联表；`delete(id)` 引用检查改 `SELECT version_id ... WHERE image_id=?`（消除 LIKE hack）；`projectImages` 仍返回 `bodyImageIds`（由关联表按 order 计算，**对外契约不变**）。
- R5 `PreviewService`：删除本地 `bodyIdListOf`，改注入 mapper 查关联表；渲染输出不变。
- R6 spec/文档同步：`database-guidelines.md` 的 1NF/JSON 段记录「⑦ 复核：JSONB 为有意约定不处置；body_image_ids 已规范化为关联表」；`docs/spec/image.md` / `docs/spec/preview.md` 字段级表格同步。
- R7 测试：新增/更新关联表读写、顺序保持、add/remove 幂等、引用检查（不再误匹配 15/51）、快照 `bodyImageIds` 顺序。

## Acceptance Criteria

- [ ] AC1 `mvn -q -DskipTests compile` 通过。
- [ ] AC2 `mvn test` 全绿（既有 370 + 新增）。
- [ ] AC3 `npm run build` 通过（前端零改动，回归确认）。
- [ ] AC4 V2 迁移在「空库（V1 后）」与「既有库（V1 baselined + 有逗号存量数据）」两路径均成功；回填后关联表行数 = 逗号列非空元素总数，且顺序与原串一致。
- [ ] AC5 迁移后 `body_image_ids` 列不存在；全库 grep `body_image_ids`/`getBodyImageIds`/`setBodyImageIds` 在 `src/main` 零残留（迁移脚本与 spec 历史描述除外）。
- [ ] AC6 引用检查：图片被版本引用时删除仍被拒（提示不变），且 id=5 不再因 15/51 误匹配（行为等价或更精确）。
- [ ] AC7 JSONB 判定写入 spec（`database-guidelines.md`）与任务记录，父任务 TaskMap ⑦ 标注处置结论。

## Out of Scope

- JSONB 迁移（有意约定不处置）。
- 其他 TEXT JSON 列（fact_sheet/rag_citations/citations/image_refs 等）形态变更。
- ⑥ 已归档、⑤/④ 已归档、⑧、⑨。

## Key Decisions

- **scope A**：只规范化 body_image_ids；JSONB 判定不予处置（Background C）。
- **单 V2 迁移内含回填 + DROP**：数据是「搬移」非「丢失」，回填为确定性 SQL 变换，实施时校验行数/顺序一致；回滚=重加列并从关联表聚合（manual，文档化）。
- **不建强 FK、应用层维护外键**：沿用 `sparkora_image_tag` / embedding 表范式；`version_id`/`image_id` 直接 WHERE，不 JOIN 逻辑删除表。
- **对外契约不变**：`projectImages.bodyImageIds: List<Long>` 原样返回，前端零改动。

## Notes

- 复杂任务，需 design.md + implement.md。
- 前向迁移路径已由 ⑥ check 验证（V2 在 baselined 库成功应用）。
