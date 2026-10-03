# Implement — E3 KB 数据模型规范化

> 执行计划。依赖 E1（已完成）。范围 = **仅数据模型**（不含运营/导入/审核流/前端管理页的重构，
> 仅最小前端适配：domain 改下拉、新增 source/tags/生效期输入）。

## 设计决策（主会话已定，实现遵循）

- **D-a `domain` 受控词表**：新增代码常量类 `com.sparkora.kb.KbDomain`（包级受控词表，
  同 `NewsImageClassifier` 先例）。取值：`通用/充电/保养/政策/技术科普/安全/驾驶`。
  写入侧 normalize：trim；空白 → `通用`；**非词表值 → 400 拒绝**（附允许列表）。
  存量行由迁移按「精确匹配，否则归 `通用`」回填。
- **D-b `source` 来源**：`VARCHAR(200)` 可空；trim、空串 → null。
- **D-c `tags` 标签**：独立关联表 `sparkora_kb_doc_tag(doc_id, tag_name, created_by, created_at,
  UNIQUE(doc_id,tag_name))`，镜像 `sparkora_image_tag`（应用层维护、无强 FK、UNIQUE 防重）。
  入口统一 normalize（trim/去空/去重保序/≤50）。不做全局标签维度，仅 doc 维度。
- **D-d 生效期**：`effective_from`/`effective_to` `DATE` 可空（null = 不限）。
  检索可用性由 store metadata `active` 承载：`active = enabled && 今天∈[from,to]`；
  **不改变检索 SQL 语义**（仍只过滤 active），避免切分 CAR+KB 合并窗口破坏 parity。
- **D-e store metadata 扩展**：`upsert` metadata 增 `source`/`effectiveFrom`/`effectiveTo`/`tags`(列表)，
  供未来检索过滤；`active` 按 D-d 计算。
- **D-f 生效期对账**：新增 `KbEffectiveWindowReconciler`（`@Scheduled` 每日 + 启动一次），
  按当前日期重算 KB 行 `active`（经 `VectorStoreService.setActive`），使未来生效/到期自动翻转。
  异常仅 warn 不阻断。

## 执行清单

- [ ] `KbDomain` 受控词表 + `normalize/validate`（纯静态、可单测）。
- [ ] Flyway `V6__kb_normalize.sql`：`ALTER sparkora_kb_doc ADD source/effective_from/effective_to`；
      存量 domain 回填（精确匹配否则 `通用`）；建 `sparkora_kb_doc_tag` + 索引。
- [ ] `KbDocEntity` 加 `source`/`effectiveFrom`/`effectiveTo`；`KbDocTagEntity` 新增。
- [ ] `KbDocTagMapper`（BaseMapper + 按 doc 查/删）；`KbDocSaveDto` 加 `source`/`tags`/生效期 + 校验。
- [ ] `KbDocService`：create/update 落新列 + tag 关联（先清后插差集）；list/get 回填 source/tags/生效期；
      `rebuild` 时 store metadata 带上新字段 + 计算 active；domain 经 `KbDomain.normalize` 校验。
- [ ] `VectorStoreService.upsert` 增可选 metadata 字段（source/effectiveFrom/effectiveTo/tags）——
      保持旧调用签名兼容（重载或 builder）。
- [ ] `KbEffectiveWindowReconciler`（每日 + 启动，重算 active）。
- [ ] 前端 `KbLibrary.vue` 最小适配：domain 改 `el-select`（受控值）；新增 source 输入、tags 输入、生效期选择器。
- [ ] `docs/spec/knowledge/kb.md` 字段级表格 + `.trellis/spec/backend/ai-rag-guidelines.md` 同步。
- [ ] 测试：`KbDomainTest`、`KbDocService` 新字段/tag 用例、store metadata 含新字段、生效期 active 计算、reconciler 用例。

## 验证命令

```bash
mvn -q -DskipTests compile
mvn test
cd frontend && npm run build
```

## 回退点

- R-E3 = E3 提交；新列可空/默认、关联表独立，`git revert` 无数据破坏。
