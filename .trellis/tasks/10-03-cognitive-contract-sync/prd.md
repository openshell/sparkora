# C6 契约/文档/迁移同步

> 父任务:`../10-03-gen-cognitive-redesign`。
> **依赖:贯穿 C1~C5**(随各子任务同步,不阻塞其开工)。

## Scope

认知层重构的契约与文档同步:Flyway 迁移、`docs/spec/**` 权威契约、端到端流程图、配置项。

## Acceptance Criteria

- [ ] 新增 Flyway `V10__cognitive_layer.sql`(clarify_session/task_brief/writing_blueprint/blueprint_status/quality;旧混合列收敛),未改已应用基线。
- [ ] `docs/spec/brief-generation.md` 重写认知层章节(澄清会话/研究规划/蓝图字段级契约表)。
- [ ] `docs/article-generation-flow.md` 流程图与状态机更新。
- [ ] `.env.example` 若新增配置同步;`docs/README.md` 模块索引按需更新。
- [ ] 三处同步(迁移脚本 + entity/mapper + spec 字段表)。

## Out of Scope

- 具体功能实现(归 C1~C5)。
