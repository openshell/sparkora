# 状态机推进逻辑收敛

## Goal

把散落在 5 个类 + schema.sql 的 `sparkora_article_project.status` 推进逻辑收敛到单一 `ProjectStatusService`，消灭重复的抢占/回退/守卫代码。历史教训：09-10-versions-page-fix（深度链路漏推状态机）与 09-27 P0-②（8 处 updateById 并发回写）都是状态逻辑分散的直接产物。

## Background（现状证据，P0 修复后）

状态写入分布（`set("status")` 全量）：

| 位置 | 写入 | 语义 |
|---|---|---|
| `ArticleProjectController.java:124` | INSERT 初始 DRAFT | 创建，非更新 |
| `BriefService.java:99/135/148` + claimGenerating | GENERATING_BRIEF 抢占 / READY 成功 / DRAFT 失败 | 深度简报链路 |
| `ImitationService.java:96/148/161` | 同上（内联复制） | 仿写分析链路 |
| `VersionService.java:114/158/165/178` | GENERATING_VERSIONS 抢占 / VERSIONS_READY 两拆分 / READY 失败回退 | 多版本链路 |
| `DeepController.java:164/170` | VERSIONS_READY 两拆分（READY/DRAFT 白名单） | 深度单版链路 |
| `PublishService.java:109` | PUBLISHED_DRAFT | 发布 |
| `schema.sql:441` | 启动回填 READY/DRAFT→VERSIONS_READY | 存量数据修复 |

重复代码（3 份拷贝）：
- `STALE_GENERATING_MS = 10min`：`VersionService:74`、`ImitationService:56`、`BriefService:47`
- `stuckGenerating(p)`：`VersionService:77`、`BriefService:50`（ImitationService 内联 L85-88）
- 原子抢占条件更新：`VersionService:108-117`、`ImitationService:90-100`、`BriefService.claimGenerating:148-160`
- 「首版才设 current」两拆分：`VersionService:152-171`、`DeepController:158-172`
- `projectStatusGuardMsg`：BriefService 静态方法，被三个服务引用

状态机语义（docs/README.md 4.2，不可变）：6 态；下游已触发禁止回退；陈旧自愈 10min；PUBLISHED_DRAFT 终态可重发。

## Requirements

- R1 新建 `com.sparkora.service.ProjectStatusService`，唯一持有项目 status / last_brief_error / last_version_error / last_publish_error 的写权（INSERT 初始态与 schema.sql 启动回填除外）。
- R2 吸收全部重复逻辑：陈旧阈值常量、stuckGenerating、brief/versions 两条原子抢占、成功推进（含首版 current 两拆分）、失败回退（生成中白名单）、发布终态落库、错误列写入/清空、projectStatusGuardMsg。
- R3 调用方改造为委托：BriefService、ImitationService、VersionService、DeepController、PublishService（成功落库 + markFailure）、ClarifyService（异步错误列写入/清空）。
- R4 语义保持（P0 修复后口径为基线）：抢占 WHERE 白名单 + 陈旧自愈；成功推进条件更新防回退；失败回退仅生成中状态；首版 current 两拆分；409 提示语与错误码不变。
- R5 业务列不进状态服务：imitation_analysis 等业务列由调用方作为 extra columns 参数传入同一条 UPDATE（保持原子性），服务不感知业务语义。

## Acceptance Criteria

- [ ] AC1 `grep -rn 'set("status"' src/main/java` 仅 ProjectStatusService 与非项目状态机（CarSyncJob/NewsSyncJob/ArticleProjectController INSERT）命中。
- [ ] AC2 `STALE_GENERATING_MS` 仅 ProjectStatusService 一处定义。
- [ ] AC3 `mvn -q -DskipTests compile` + `mvn test` 通过（325 既有 + 新增状态服务单测）。
- [ ] AC4 行为不变：各转换的 WHERE 条件/SET 列与 P0 修复后实现逐项一致（单测断言）；对外 409/400 提示语不变。
- [ ] AC5 新增 `ProjectStatusServiceTest`：每个转换方法断言 WHERE 白名单、两拆分 current 语义、错误列截断。
- [ ] AC6 docs/README.md 4.2 + `.trellis/spec/backend/error-handling.md`（状态机服务段）同步。

## Out of Scope

- schema.sql 启动回填（存量数据修复，随 ⑥ Flyway 子任务处置）
- brief 侧 plan_status（异步占位索引，不属于项目状态机）
- CarSyncJob/NewsSyncJob 的 job status（独立状态机，同步链路）
- 前端 `constants/project.js`（展示映射，唯一事实源地位不动）
- 生成链路异步化（④，待本任务接口定型后做）
- ArticleProjectController 拆分（⑨，待 ⑤④ 后做）

## Key Decisions

- **服务而非组件库**：用 Spring `@Service`（项目惯例 service 包），不做状态机框架（状态枚举 + 转换表是 P1 收敛不是重写，枚举收敛可后续加）。
- **extra columns 参数而非 Consumer\<UpdateWrapper\>**：业务列以 `Map<String,Object>` 传入服务拼同一条 UPDATE，避免泄漏 wrapper API、保持一次 UPDATE 原子性。
- **两拆分等价实现**：advanceVersionsReady 内部保留「先 isNull(current_version_id) 命中即 return，否则第二条仅推状态」的语义（与 P0 修复逐字等价）。
- **DeepController 两白名单差异显式化为两个方法**：多版本链路源态 GENERATING_VERSIONS；深度单版源态 READY/DRAFT——语义不同不合并。

## Open Questions

（无——全部由代码证据与父任务排序决策解决）