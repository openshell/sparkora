# 技术设计:简报生成流程重构

## 1. 设计目标

把「主题创作」的深度研究计划(clarify)从**同步阻塞 + 前端有界重探测**改为**后端异步 + brief 侧可查询状态**,并让前端「无简报」区间收敛为**唯一状态机**。核心消除两类缺陷:

- 竞态:前端探测窗口(6s)短于 clarify 耗时(10~30s),导致误判「未开始」。
- 冗余:同一状态两套 UI + 两步按钮 + 重复触发产生重复 brief 行。

## 2. 边界

- 只动「主题创作(TOPIC)+ 深度流程」的 clarify 阶段与 `StepBrief` 无简报区。
- 不改项目状态机(§4):深度仍为 brief 层,PLANNING/CLARIFYING/RESEARCHING 等不写入 `project.status`。
- 不改仿写(IMITATION)交互(D2);不改研究/写作/版本/预览/发布。
- FAST 接口继续封死(410)。

## 3. 后端设计

### 3.1 数据模型:新增 `plan_status`

`sparkora_article_brief` 增列(幂等,`schema.sql`):

```sql
ALTER TABLE sparkora_article_brief ADD COLUMN IF NOT EXISTS plan_status VARCHAR(20); -- DEEP: PLANNING/READY;其余 null
CREATE UNIQUE INDEX IF NOT EXISTS uq_brief_planning
  ON sparkora_article_brief(project_id) WHERE plan_status = 'PLANNING';
```

- `null`:FAST/IMITATION/存量行,以及已完成的 DEEP(历史兼容;完成态也可写 READY)。
- `PLANNING`:异步研究计划生成中(claim 占位)。
- 语义:只有 DEEP 行使用。部分唯一索引保证**同一项目同时至多一条 PLANNING**,是并发幂等的数据库级兜底。

`ArticleBriefEntity` 增字段 `private String planStatus;`(下划线转驼峰自动映射)。

### 3.2 clarify 异步化(`ClarifyService`)

拆成「同步占位 + 异步生成」两段,对齐 `DeepResearchService.run/runAsync` 范式:

- `ArticleBriefEntity start(Long projectId, String topic, String extraInfo)`(**同步**,毫秒级):
  1. 校验项目存在(保留现有 topic 非空校验)。
  2. 清理陈旧占位:`DELETE ... WHERE project_id=? AND plan_status='PLANNING' AND created_at < now()-10min`(复用 `STALE_GENERATING_MS` 语义,进程死亡自愈)。
  3. 插入新 brief:`gen_mode='DEEP'`, `plan_status='PLANNING'`, `created_at=now()`。若并发命中部分唯一索引 → 捕获 `DuplicateKeyException`,抛 `IllegalStateException("该项目正在生成研究计划,请稍候")`(接口层转 409)。
  4. `self.runAsync(b.getId(), topic, extraInfo)`(自注入代理,确保 `@Async` 生效;`@EnableAsync` 已开启)。
  5. 返回 brief(含 id)。
- `@Async void runAsync(Long briefId, String topic, String extraInfo)`:
  1. 调 LLM(现有 `clarify` 的 system/user prompt 与 `normalizeQuestions` 逻辑整体搬入)。
  2. 成功:回写 `research_plan`/`clarify_questions`/`ai_model`/`token_usage`,`plan_status='READY'`。
  3. 失败:删除该 PLANNING 行(保持「失败无残留」,与现状同步失败不落行一致),并写 `project.last_brief_error`(截断 1000,状态保持 DRAFT),`log.warn`。

> 保留 `ClarifyService.clarify(projectId,topic,extraInfo)` 的 LLM 生成主体为私有方法 `generatePlan(...)`,供 `runAsync` 调用;`lockAnswers`/`normalizeQuestions` 不动。

### 3.3 `/deep/clarify` 契约变更(`DeepController`)

- 请求体不变 `{topic, extraInfo}`。
- 返回由 `{briefId, researchPlan, questions}` 改为 `{briefId, stage:"PLANNING"}`(立即返回,不再携带计划内容)。
- 异常:`IllegalStateException`(并发/陈旧冲突)→ `R.fail(409, msg)`;`IllegalArgumentException` → 400。
- 保留 `@PreAuthorize("hasAnyRole('ADMIN','EDITOR')")`。

### 3.4 `/deep/status` 暴露 PLANNING(`DeepController.stageOf`)

`stageOf` 增首判:

```java
if ("PLANNING".equals(b.getPlanStatus())) return "PLANNING";
```

`status` 输出增 `planStatus` 字段(便于前端/排障)。查询仍取该项目最新 DEEP 行;PLANNING 行最新即被返回。其余 stage 判定顺序不变(RESEARCH_DONE > RESEARCHING > CLARIFIED > CLARIFYING > NONE)。

### 3.5 幂等/并发小结

| 场景 | 行为 |
|---|---|
| 双击/双开触发 | 第二次插入撞部分唯一索引 → 409;不产生重复行 |
| JVM 中途死亡留 PLANNING | 超 10min 被 start 清理,可重新触发自愈 |
| 计划生成失败 | 删除占位行 + last_brief_error;status 回 NONE,前端引导态可见错误并重试 |

## 4. 前端设计

### 4.1 创建页(`ProjectEdit.vue`,仅 TOPIC 分支)

- `onSaveAndGenerate`:改为 **先 `await startDeep()` 再 `router.push`**,消除「导航早于落库」竞态;startDeep 现在毫秒级返回,await 无体验损失。失败也导航(项目已建,详情页据 `lastBriefError` 展示)。
- 去掉 TOPIC 跳转的 `?gen=deep` 意图参数(`onSave` 与 `onSaveAndGenerate` 均不再拼)。
- 仿写分支保持原样(仍 push 后 await analyzeImitation,`?gen=imitation` 不变)。

### 4.2 详情页无简报区统一状态机(`StepBrief.vue`)

删除 `deepMode` / `hasDeepIntent` / `genDeepIntent` / 有界重探测(`probeDeepStatus` 的 3×2s 循环)与裸按钮。引入单一 `deepStage` 驱动(值域扩为 `NONE|PLANNING|CLARIFYING|CLARIFIED|RESEARCHING|RESEARCH_DONE`),渲染优先级(仿写分支独立,不动):

```
generatingBrief            → 生成中骨架(project.status=GENERATING_BRIEF,研究完成后的自动简报)
deepStage === 'PLANNING'   → 「研究计划生成中」进度态(自轮询 /deep/status)
deepStage === 'CLARIFYING' → ClarifyForm(可填)
deepStage === 'CLARIFIED'  → ClarifyForm(locked,回显)
deepStage === 'RESEARCHING'→ ResearchProgress
deepStage === 'RESEARCH_DONE' → FactSheetSummary + (重试简报 / 跳过简报直接写正文)
brief 存在且未在重启流程 → 简报正文(现 ④ 分支)
briefError                 → 错误卡片 + 重试
否则                        → 引导页 intro-hero(单一主操作「开始深度研究」)
```

> 「重新研究生成」不再回到引导页再点一次:点击即调 `startDeep` 并进入 PLANNING。为在旧 brief 存在时展示新流程,引入局部 `restarting` 标志:重启时置 true 并记下当前 `currentBriefId`;`watch(currentBriefId)` 变化(新简报落库)后置 false。`restarting=true` 时跳过「brief 存在」分支,优先走深度流程分支。

- `startDeep()`(原 `onDeepClarify`):POST `/deep/clarify` → 取 `briefId` → `deepStage='PLANNING'` → 启动 PLANNING 轮询。
- PLANNING 轮询:每 2.5s 拉 `/deep/status`;`stage` 离开 PLANNING 即停并应用;若 `stage` 回 NONE 且 `project.lastBriefError` → 停并显示错误(引导态)。
- 挂载/项目就位后 `syncDeepStatus()` 单次拉取(替代原 `probeDeepStatus`),恢复 CLARIFYING/CLARIFIED/RESEARCHING/RESEARCH_DONE;若返回 PLANNING 则续起轮询。不再需要 `?gen=deep` 意图与重查。
- 引导页文案与按钮收敛为一个:「开始深度研究」→ `startDeep()`。
- 保留 `onResearchDone` / `onDeepBriefRetry` / `onDeepGenerate` / `onClarifySubmit` 逻辑,`deepStage` 赋值改为新值域。

### 4.3 store 与布局

- `store/project-detail.js` 不改协议;PLANNING 期间 project.status 仍为 DRAFT,布局轮询不启动,由 StepBrief 自轮询(与 ResearchProgress 同款,自包含)。
- `ProjectLayout` 步骤条/可达范围不变。

## 5. 兼容性 / 迁移

- 存量 DEEP brief(`plan_status=null`)不受影响;`stageOf` 对 null 走原判定。
- 存量因重复点击产生的多条 DEEP brief 保留可读;部分唯一索引仅约束 PLANNING 态,不冲突。
- FAST 封死行为不变。
- `/deep/clarify` 响应结构变更,唯一调用方为前端 `projectApi.startDeep`,同步改。

## 6. 权衡

- **brief 侧 plan_status vs 复用 project.status**:选前者,保持「深度不改项目状态机」的既有设计;代价是 StepBrief 需自轮询(已在 ResearchProgress 有先例)。
- **部分唯一索引 vs 应用层加锁**:选索引,数据库级原子、单实例与未来多实例都成立;代价是一次 schema 变更。
- **失败删除占位行 vs 标记 FAILED**:选删除,保持「失败无残留」与现状一致,status 天然回 NONE;代价是丢失失败计划行(失败原因已落 `last_brief_error`,足够)。

## 7. 回滚

- 后端:还原 `DeepController.clarify`/`stageOf`、`ClarifyService`,`plan_status` 列与索引可保留(幂等无害)。
- 前端:还原 `ProjectEdit`/`StepBrief`。
- 无破坏性数据迁移(仅加列/加部分索引),回滚无数据风险。
