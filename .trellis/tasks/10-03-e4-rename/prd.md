# E4 命名规范化：car_doc → 块语义

## Goal

`sparkora_car_doc` 实为车型**块**表（含 `chunk_text`/`chunk_type`），命名失真；重命名为块语义。

## Depends On

- **E1**（store 迁移）——迁移期间不宜同时改表名，避免对拍噪声。

## Requirements

- 父 R6（#1）。
- 新增 Flyway 迁移重命名表（`ALTER TABLE ... RENAME TO`）+ 索引名同步。
- 同步 entity 类名/mapper/所有引用/`docs/spec/**`/`.trellis/spec/**`。
- **对外契约不变**（仅内部命名）。

## Acceptance Criteria

- [ ] 表/索引重命名迁移落地，`flyway_schema_history` 正常。
- [ ] entity/mapper/引用全部更新，`mvn test` 全绿。
- [ ] 规格文档同步。
- [ ] 无外部 API/前端可观察变化。

## Out of Scope

- KB/新闻/图片侧命名（本任务仅 car_doc 块表）。
