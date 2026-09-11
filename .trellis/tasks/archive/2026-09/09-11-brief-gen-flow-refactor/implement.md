# 执行计划:简报生成流程重构

> 前置:本任务为复杂任务,需 `prd.md` + `design.md` + `implement.md` 齐备并经用户批准后方可 `task.py start`。
> 后端改动后至少跑 `mvn -q -DskipTests compile`;前端改动后跑 `npm run build`(见 AGENTS.md)。

## 实施顺序(按依赖)

### A. 后端:异步 clarify + PLANNING 态

- [ ] A1 `schema.sql` 幂等增列与部分唯一索引(design §3.1)。放在 S9 迁移段附近。
- [ ] A2 `ArticleBriefEntity` 增 `planStatus` 字段(design §3.1)。
- [ ] A3 `ClarifyService` 重构(design §3.2):
  - 抽出 `generatePlan(projectId, topic, extraInfo)`(原 `clarify` 的 LLM 主体,不含 insert);
  - 新增同步 `start(...)`(清理陈旧 PLANNING → insert PLANNING 行 → self.runAsync → 返回);
  - 新增 `@Async runAsync(briefId, topic, extraInfo)`(成功回写 plan/questions + READY;失败删行 + 写 project.last_brief_error);
  - 自注入 `@Lazy ClarifyService self`;保留 `lockAnswers`/`normalizeQuestions`。
- [ ] A4 `DeepController`(design §3.3/§3.4):
  - `clarify` 改为调 `start(...)`,返回 `{briefId, stage:"PLANNING"}`,加 `IllegalStateException → R.fail(409,...)`;
  - `stageOf` 首判 `planStatus == PLANNING → "PLANNING"`;`status` 输出增 `planStatus`。
- [ ] A5 编译:`mvn -q -DskipTests compile`(默认 maven 仓库只读时加 `-Dmaven.repo.local=/tmp/m2repo`)。

### B. 前端:创建页竞态 + 详情页状态机

- [ ] B1 `ProjectEdit.vue`(design §4.1):TOPIC 分支 `onSaveAndGenerate` 改为先 await `startDeep` 再 push;去掉 TOPIC 的 `?gen=deep`(`onSave` 与 `onSaveAndGenerate`)。仿写分支不动。
- [ ] B2 `StepBrief.vue`(design §4.2):
  - 删 `deepMode`/`hasDeepIntent`/`genDeepIntent`/`probeDeepStatus` 有界重查/裸按钮;
  - `deepStage` 值域增 `PLANNING`;新增 PLANNING 进度态模板与自轮询;
  - `syncDeepStatus()` 替代探测(单次拉取 + PLANNING 续轮询);
  - `startDeep()` 替代 `onDeepClarify`;引导页收敛为单一「开始深度研究」主操作;
  - 「重新研究生成」改为直接 `startDeep` + `restarting` 标志(design §4.2)。
- [ ] B3 前端构建:`npm run build`(frontend/)。

### C. 联调验证

- [ ] C1 `./dev.sh restart backend` 后按 AC 手测:
  - AC1/AC2:新建主题项目「创建并生成简报」→ 详情页显示「研究计划生成中」→ 完成后自动出澄清表单;全程无裸按钮、无两步按钮。
  - AC3:PLANNING 期间重复触发被拒(409),主操作 loading/disabled;DB 无重复 PLANNING 行。
  - AC4:PLANNING/CLARIFYING/RESEARCHING 中途退出重进,状态正确恢复。
  - AC5:制造 clarify 失败(如断网/坏 key),页面显示 lastBriefError 并可重试。
  - AC6:「仅存草稿」进入 → 引导页 → 点一次「开始深度研究」即进 PLANNING。
  - AC7:仿写项目创建并分析、简报页展示不回归。

### D. 规格同步(Phase 3)

- [ ] D1 `docs/s0-spec.md`:§5 创建/简报(去 `?gen=deep` 描述)、§14 深度模式(§14 接口表 `/deep/clarify` 返回契约、新增 PLANNING 态与 `plan_status` 字段)、字段级表格补 `plan_status`。
- [ ] D2 如 `docs/article-generation-flow.md` 提到 clarify 同步/`?gen=deep`,同步更新。

## 风险点 / 回滚点

- **R-a 部分唯一索引与存量数据**:若历史已存在多行且恰好 plan_status=PLANNING(不应有,列是新加的),索引创建可能失败 → 列新增为 null,索引创建前无 PLANNING 行,安全。
- **R-b `@Async` 未生效**:必须经自注入 `self` 调用(参照 `DeepResearchService`/`CarSyncJobService`),否则退化为同步阻塞,竞态复现。实现后重点验证接口是否毫秒级返回。
- **R-c 前端 restarting 分支**:旧 brief 存在时重启新流程的渲染优先级需实测(AC1/AC5 覆盖)。
- **回滚**:按 design §7,还原 4 个文件即可;schema 加列/索引幂等无害。

## 验收命令

```bash
mvn -q -DskipTests compile
cd frontend && npm run build
./dev.sh restart backend && ./dev.sh logs backend -f
```
