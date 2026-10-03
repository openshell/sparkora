# E3 KB 数据模型规范化

## Goal

把 KB 域从「欠发育」补齐到可检索过滤的数据模型：受控 `domain` 词表 + 来源/标签/生效期。

## Depends On

- **E1**（store 迁移）——新列要同步进 store metadata。

## Requirements

- 父 R6（#2）。
- `domain` 改受控词表（代码常量，同 `NewsImageClassifier` 先例；允许一词多标签）。
- 新增列：`source`（来源）、`tags`（标签，关联表或受控前缀）、生效期（`effective_from`/`effective_to` 或等价）。
- 写入侧统一 normalize；检索侧可按新维度过滤（metadata filter）。
- **不含**批量导入/审核流/版本管理/前端管理页。

## Acceptance Criteria

- [ ] 受控 domain 词表落地，非法值拒绝或归并（明确策略 + 单测）。
- [ ] 新列 Flyway 迁移 + entity/mapper + `docs/spec/**` 三处同步。
- [ ] 新维度写入并反映到 store metadata；检索可按其过滤。
- [ ] 既有 KB 数据向后兼容（默认值/可空）。
- [ ] `mvn test` 全绿。

## Out of Scope

- KB 运营能力、批量导入、审核流、前端管理页。
