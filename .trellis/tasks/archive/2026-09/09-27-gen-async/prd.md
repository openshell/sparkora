# 生成链路异步化（P1-④）

> 父任务：`09-27-p1-arch-consistency`（P1 架构一致性）。依赖 ⑤（`ProjectStatusService` 已定型并归档）。

## Goal

消除三条「项目状态驱动的生成链路」的同步阻塞：端点当前同步执行 1~N 次 LLM 调用（120~300s），前端靠放宽 axios 超时硬扛、占用 Tomcat 请求线程、无重复触发防护。改为**同步置生成中状态（毫秒级）+ 后台 `@Async` 执行 + 前端轮询状态翻转**，复用 ⑤ 的 `ProjectStatusService` 与既有 `store.startPolling`。

范围收敛论证（评审 P1-④ 原列 5 条，本任务取 3 条）：

| 链路 | 当前超时/耗时 | 载体 | 本任务 |
|---|---|---|---|
| `imitation/analyze` | 120s（1 次 LLM） | 项目状态 `GENERATING_BRIEF`→`READY` | ✅ 纳入 |
| `generate/versions` | **300s**（N 次串行 LLM） | 项目状态 `GENERATING_VERSIONS`→`VERSIONS_READY` | ✅ 纳入 |
| `deep/generate` | **300s**（前端按风格串行多次） | 项目状态（当前**无 claim**） | ✅ 纳入 |
| `publish` | ~十几秒（远低于 120s 超时） | 状态机**无「发布中」态** | ⏸ 见 Out of Scope |
| `qa ask` | 120s（1 次 LLM） | 非项目状态；轮询聊天体验差 | ⏸ 见 Out of Scope |

三条纳入链路共享同一机制（项目状态即进度载体），合并为一个可独立验证的交付；publish/qa 载体不同，另行任务。

## Background（证据，file:line）

### 既有异步先例（ClarifyService 占位行范式）

- `ClarifyService.start()`（`ClarifyService.java:66-95`）：同步清理陈旧占位（>10min）→ insert `plan_status=PLANNING` 占位（撞部分唯一索引 `uq_brief_planning` → `IllegalStateException` 409）→ `self.runAsync(...)`（`@Async`，自注入代理 `@Autowired @Lazy` L49-51）→ 立即返回。
- `@Async` 触发靠自注入代理（`this.runAsync` 不走代理）；先例 4 处（ClarifyService/DeepResearchService/CarSyncJobService/NewsSyncJobService）。
- `@EnableAsync` 在 `SparkoraApplication.java:19`；**无自定义 TaskExecutor bean**（Spring Boot 默认）。
- **响应形态为 `HTTP 200 + R.ok({briefId, stage:"PLANNING"})`，不是 HTTP 202**（`DeepController.java:74-78`）；全库无 `HttpStatus.ACCEPTED`。
- 轮询 `GET /deep/status?briefId=`（`DeepController.java:179-214`）；**按 id 查无 → `stage=NONE`**（失败可检测契约）；stage 由 `stageOf` 推导（PLANNING/RESEARCHING/…/NONE）。

### 三条纳入链路现状

**imitation/analyze**（`ArticleProjectController.java:205-217` → `ImitationService.analyze` L77-144）
- 已有完整 claim/advance/fail：`statusService.stuckGenerating`（409）→ `claimBriefGenerating(projectId, p, "分析原文")`（L89）→ 1 次 `aiClient.chatJson`（L108）→ `advanceReady(projectId, briefId, Map.of("imitation_analysis", json))`（L134）→ 失败 `failBriefToDraft`（L140）+ 抛 `AiException`。
- 前端：`api/index.js:50` 超时 120000；`StepBrief.vue:286-300` `onAnalyze`（同步 await + 手动 `ensureImitation`/`ensureProject`，注释明说「同步 API，无轮询翻转」）与 `ProjectEdit.vue:198-204`（创建后 fire-and-await）。

**generate/versions**（`ArticleProjectController.java:235-257` → `VersionService.generate` L76-138）
- 仅 IMITATION 项目（非仿写 → `R.fail(410)` 指向 `/deep/generate`）。
- 已有完整 claim/advance/fail：`claimVersionsGenerating`（L97）→ 循环 `generateOne`（每风格 1 次 `chatJson`，L117-120 单版失败收集不致命）→ `advanceVersionsReady(projectId, firstId, lastVersionError)`（L130）→ 失败 `failVersionsToReady`（L135）。
- 前端：`api/index.js:23` 超时 **300000**；`StepVersions.vue:264-276` await 后 `loadVersions()` + 「已生成 N 版」toast。

**deep/generate**（`DeepController.java:128-160` → `DeepWriterService.write` L69）
- 请求体 `{briefId, styleId?}`；**当前无 claim、无 409 重复防护**（研究确认）；成功后在**控制器**调 `advanceVersionsReadyFromReady(projectId, versionId)`（`DeepController.java:153`，源态 `READY/DRAFT`）；失败仅 `R.fail(500)` 不动状态。
- 前端：`api/index.js:45` 超时 **300000**；`StepVersions.vue:286-297` 按选中风格**串行 await 多次**，逐风格收集成功/失败名。

### 前端轮询基建（可直接复用）

- `frontend/src/store/project-detail.js`：`startPolling`（L159-176，默认 4000ms，`ensureProject(force)` 比对 before/after 状态翻转）→ `→READY` 时 `ensureBrief` + 仿写再 `ensureImitation` 并 stop（L168-173）；`→VERSIONS_READY/DRAFT` 时 `ensureVersions` 并 stop（L174）；`invalidate`/`ensure*` 家族齐备。
- 驱动：`ProjectLayout.vue:114-117` `watch(project.status)`，`isGenerating(status)` → `startPolling`；`onUnmounted` 停止。
- `constants/project.js`：`isGeneratingBrief`/`isGeneratingVersions`/`isGenerating`（L31-33）为轮询启动与防重复提交的唯一依据；`maxReachableStepOf`（L28）随状态锁步骤导航。
- `StepBrief.vue:391-414` `startPlanningPoll(briefId)`（2500ms）为轮询先例；**必须带本次 briefId**（失败删占位后按 project 取最新会拿到旧行）。
- **缺口**：`startPolling` 无 `PUBLISHED_DRAFT` 翻转处理；无 QA 轮询。

### 约束（spec 规则）

- `database-guidelines.md`：异步=同步落占位 + 后台生成；占位清理物理删除；轮询必须用返回的占位 id。
- `error-handling.md`：全部 `status`/`last_*_error` 写入只经 `ProjectStatusService`，新链路**只委托不手写**；不同源态白名单不得合并成一个方法；异步成功分支三件事（写产物+推状态+清错）齐全。
- **无 `@Transactional` 包裹**（`VersionService` javadoc L33：置状态短事务先提交、AI 调用无事务、最后写产物+置状态再提交）——**这是异步化低风险的关键**：无跨 `@Async` 边界的事务泄漏。

## Requirements

- R1 **imitation/analyze 异步化**：端点同步完成前置校验与 `claimBriefGenerating`（毫秒级），返回占位响应；`@Async` 体执行 AI 分析 → `advanceReady` / 失败 `failBriefToDraft`。重复触发经 claim 的 `claimed==0` → 409。前端去掉「手动刷新」改为轮询翻转。
- R2 **generate/versions 异步化**：端点同步 `claimVersionsGenerating` 后返回；`@Async` 体执行 N 版生成 → `advanceVersionsReady` / 失败 `failVersionsToReady`（含部分成功语义 `last_version_error`）。前端 await 改轮询翻转。
- R3 **deep/generate 异步化 + 补 claim**：新增 claim（当前缺失，属既存并发缺陷），`@Async` 体执行 `DeepWriterService.write` → 状态推进 / 失败回退；前端串行多次 await 改单次触发 + 轮询。
- R4 **进度载体复用项目状态机**：本任务**不新建表/不加索引**，`GENERATING_BRIEF`/`GENERATING_VERSIONS` 即进度载体；陈旧自愈沿用 `stuckGenerating` 的 10min 分支（异步线程死亡可由重试自愈）。
- R5 **状态推进全部委托** `ProjectStatusService`；如需新源态白名单（如 deep 单版从 `GENERATING_VERSIONS` 推进），在服务内新增**专用方法**，不合并既有白名单。
- R6 **前端**：三条链路触发后由 `store.startPolling` 驱动翻转刷新；补齐必要的翻转后提示（如版本数 toast）与失败态展示（`last_*_error`）；确保重复提交按钮在 `GENERATING_*` 期间禁用（既有 `isGenerating` 已支持）。
- R7 **响应契约**：端点保持 `HTTP 200 + R.ok`，返回即时状态标记（形如 `{status:"GENERATING_BRIEF"}` 或沿用既有字段），**不引入 HTTP 202**（与全库契约一致）；错误映射维持 400/409/500。

## Acceptance Criteria

- [ ] AC1 三个端点均为「同步毫秒级返回 + 后台执行」：用 curl 计时，返回耗时 < 1s（不含 AI）。
- [ ] AC2 重复/并发触发在 `GENERATING_*` 期间返回 `R.fail(409)`，不重复调 AI。
- [ ] AC3 生成成功：状态翻转（`READY` / `VERSIONS_READY`）且 `current_*` 指向正确；成功分支清空对应 `last_*_error`。
- [ ] AC4 生成失败：状态回退（`DRAFT`/`READY`）且 `last_*_error` 写入（截断口径不变）；前端可见。
- [ ] AC5 `mvn -q -DskipTests compile` + `mvn test` 全绿（既有 345 用例不回归 + 新增用例）。
- [ ] AC6 `npm run build` 通过。
- [ ] AC7 状态写入仍只经 `ProjectStatusService`（`grep 'set("status"'` 无非状态服务命中）。
- [ ] AC8 前端：三条链路触发后无需 await 结果，靠 `startPolling` 翻转刷新；步骤导航随状态正确解锁/锁死。
- AC9（人工验证，不阻塞合入）：生成中刷新页面仍能恢复进度（状态自愈 + polling on mount）。

## Out of Scope

- **publish 异步化**：实测仅十几秒（远低于 120s 超时），且状态机无「发布中」态，改动需新增状态/载体，收益低——**建议暂缓**（保留同步）。若需要另立子任务。
- **qa ask 异步化**：轮询对聊天体验差，正解是 SSE/流式（独立设计），非本任务占位+轮询范式——**建议另立子任务**。
- `POST /deep/brief`（`BriefService.generateFromFactSheet` 同步重试）与 `deep/run`、`deep/clarify`（已异步）不改。
- 自定义 `TaskExecutor` 线程池（属 P2-⑮，另行任务；本任务沿用默认 executor）。
- 前端超大组件拆分（P1-⑨）。

## Key Decisions

- **复用项目状态机作进度载体**，不引入新表：三条链路的进度与失败天然映射到既有 `GENERATING_*`/`READY`/`VERSIONS_READY`，`store.startPolling` 已能翻转，`stuckGenerating` 已能自愈——新表只会重复造轮子。
- **响应用 200 + 标记**（非 202），与 ClarifyService 先例及全库契约一致。
- **deep/generate 补 claim**：同步化暴露的既存缺陷（当前无重复触发防护），随本任务一并修。
- **范围取 3 条**：按载体同质性收敛，publish/qa 载体不同另议（见 Out of Scope）。

## Open Questions

- **[阻塞]** 范围确认：是否同意本任务只做 3 条项目状态链路，publish/qa 另立子任务？若要求 publish 或 qa 一并做，需相应扩展 design（publish 需新增状态载体、qa 需 SSE 设计）。
- `POST /deep/brief`（同步简报重试）是否纳入本任务（同属同步 LLM 调用，但原 P1-④ 未列）？
