# Flyway 版本化迁移

> 建表/改表的唯一入口。`spring.flyway.*` 见 `src/main/resources/application.yml`。

## 约定

- 命名：`V<n>__<desc>.sql`(版本号递增，两个下划线)，如 `V1__baseline.sql`、`V2__article_jsonb.sql`。
- **已应用的迁移脚本不得修改**(checksum 校验，`validate-on-migrate: true` 会阻止启动)；修正只能新增更高版本脚本。
- 结构变更三处同步：新增 `V<n>__<desc>.sql` + 对应 entity/mapper + `docs/spec/**` 对应模块字段级表格。
- 迁移脚本**无需**再写 `IF NOT EXISTS` 兜底幂等——Flyway 按版本只执行一次；但既有基线 V1 保留原幂等写法(历史结构 + 对既有库标记跳过的语义)。
- 不使用 `DO $$` 块(Spring/Flyway ScriptUtils 不支持 dollar-quote，会按 `;` 截断)；列搬数用「补列 → UPDATE 搬数据 → DROP 旧列」三条单语句。
- `clean` 保持 Flyway 10 默认禁用(`cleanDisabled=true`)，**不得**开启；迁移中不执行破坏性 DROP(索引类型切换的 `DROP INDEX IF EXISTS` 先例除外)。

## V1 基线

`V1__baseline.sql` = 改造前 `src/main/resources/db/schema.sql` 全文(逐字节固化，勿改)：

| 库状态 | Flyway 行为 |
|---|---|
| 既有库(已有表、无 `flyway_schema_history`) | 建历史表 → 插 `BSLN`@1(=`baseline-on-migrate` + `baseline-version=1`) → 跳过 V1 |
| 空库 | 执行 V1 全量 DDL/seed/回填 → 插 `V1` 记录 |
| 再次启动 | 历史表已存在、无 pending → 空跑 |

## 后续变更入口

- `V2__article_version_image.sql`（P1-⑦）：`body_image_ids` 逗号列规范化为 `sparkora_article_version_image` 关联表（建表 + 回填 + DROP 旧列，单迁移内完成）。
- 后续结构变更一律新增 `V3+` 脚本，不再触碰 V1/V2。
- **JSON 存 TEXT 为有意约定**（P1-⑦ 复核裁定，不转 JSONB），理由见 `.trellis/spec/backend/database-guidelines.md`「JSON 存 TEXT 是有意约定」。
