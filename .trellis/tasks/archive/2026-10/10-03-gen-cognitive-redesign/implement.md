# 执行计划:文章生成认知层重构

> 关联:`prd.md` / `design.md`。父任务 `gen-cognitive-redesign` 持有需求集与跨子任务验收;实现按子任务落地,依赖写在各自 PRD/本文件。

## 0. 前置与门

- 本文档对应父任务;`task.py start` 前需用户对最终 planning 摘要**显式批准**。
- 用户已授权**清空存量项目数据**,故迁移无需回填,可破坏性变更。
- 验证命令(每子任务必跑):后端 `mvn -q -DskipTests compile`、`mvn test`(相关用例);前端 `npm run build`、`cd frontend && npm run test`(涉 UI 时)。

## 1. 子任务拆解与顺序

### C1 意图澄清对话(地基,先做)

- [ ] 设计并落 `clarify_session` / `task_brief` 数据模型(Flyway `V10__cognitive_layer.sql`)与 entity。
- [ ] 新增 `ClarifyConversationService`(start/answer/converge/abort + 充分性收敛 + 下一问选择)。
- [ ] 新增 DTO:`ClarifyTurnDto` / `TaskBriefDto`(`com.sparkora.ai`),schema 单一来源派生。
- [ ] 新增 prompt 模板 `prompts/clarify/conversation-system.st`、`taskbrief-system.st`。
- [ ] 新增接口:`POST /deep/clarify/start`、`POST /deep/clarify/answer`、`POST /deep/clarify/converge`。
- [ ] 会话并发:部分唯一索引 `status='ASKING'`;陈旧自愈。
- [ ] 单测:充分性收敛(够即停/不足追问)、必要槽位硬兜底、异常降级。
- **验收**:AC1、AC2、AC3(意图契约部分)。

### C2 研究规划拆分(依赖 C1)

- [ ] 从 `ClarifyService` 剥离纯事实研究规划 → `ResearchPlannerService`,入参改为 TaskBrief。
- [ ] `research_plan` 结构收敛为纯事实(不再含意图题);迁移 `ensureBackgroundQuestion` 确定性兜底到研究规划侧。
- [ ] `ClarifyPlanDto` 拆分/重命名,移除混装字段。
- [ ] 调整 `DeepResearchService.selectResearchWindow`/`isBackgroundQuestion` 引用点。
- [ ] 单测:规划仅含事实问题、背景题兜底、toolHints 1:1。
- **验收**:AC1、AC4。

### C3 简报写作蓝图(依赖 C1/C2)

- [ ] 新增 `BlueprintService`:输入 TaskBrief + research_plan + fact_sheet → WritingBlueprint。
- [ ] `BlueprintDto` + prompt `prompts/brief/blueprint-system.st` / `blueprint-user.st`。
- [ ] evidenceMap 绑定 `fact_sheet.entries[].key`;coverage 确定性计算(COVERED/PARTIAL/MISSING)+ gaps。
- [ ] 质量信号:argumentDensity/evidenceCoverage(本地确定性)、gapCount、taskBriefConsistency。
- [ ] 落 `writing_blueprint` / `blueprint_status`(REVIEWING)/ `blueprint_quality`;状态机委托 `ProjectStatusService`。
- [ ] 新增 `POST /deep/blueprint/confirm`(置 CONFIRMED,解锁写作);`/deep/status` 透出蓝图。
- [ ] 改造 `BriefService.generateFromFactSheet` 委托 `BlueprintService`(或退役),保留异步/重试/状态守护。
- [ ] 单测:evidenceMap 绑定与 coverage 计算、质量信号、人工门状态流转。
- **验收**:AC4、AC6、AC9(蓝图门)。

### C4 写作按映射取用(依赖 C3)

- [ ] `DeepWriterService.write` 改为按 `argumentStructure` 逐节取 `evidenceMap.entryKeys` 投影 fact_sheet。
- [ ] prompt 硬约束:未映射 fact/数值不得出现;MISSING 论点写定性或标待核实。
- [ ] 数值回查升级为白名单比对(蓝图允许集合外数值 → high risk)。
- [ ] 保留元话语三层防线、截断提额重试、按 kind 分组。
- [ ] 单测:越界数值被标记、未映射证据不进 prompt、无回归。
- **验收**:AC5、AC8。

### C5 前端认知层交互(依赖 C1/C3)

- [ ] 对话式澄清 UI(替代 `ClarifyForm` 一次性表单):逐轮问答、选项/默认/跳过、收敛态。
- [ ] 蓝图评审 UI:结构化展示 thesis/论证/evidenceMap/质量信号,支持编辑论据与 entryKeys、确认。
- [ ] 扩展 `StepBrief.vue` `deepStage` 状态机(ASKING/CONVERGED/BLUEPRINT_REVIEW)。
- [ ] `src/api/index.js` 增具名导出;Enter 提交 IME 安全;防重入。
- [ ] E2E/视觉基线按需更新。
- **验收**:AC2、AC6、AC9 的 UI 部分。

### C6 契约/文档/迁移同步(贯穿)

- [ ] `docs/spec/brief-generation.md` 重写认知层章节(澄清会话/研究规划/蓝图契约表)。
- [ ] `docs/article-generation-flow.md` 流程图更新。
- [ ] 新增/更新 Flyway `V10`;`.env.example` 若新增配置同步。
- [ ] `docs/README.md` 模块索引必要时更新。
- **验收**:AC7。

## 2. 验证命令

```bash
mvn -q -DskipTests compile        # 每次后端改动
mvn test                          # 服务契约/状态机改动后必跑
cd frontend && npm run build      # 前端改动
cd frontend && npm run test       # 涉 UI/交互后
./dev.sh restart backend && ./dev.sh logs backend -f   # 联调走查
```

## 3. 风险文件与回滚点

- 高风险:`ClarifyService`(认知职责迁移)、`BriefService`(委托改造)、`DeepWriterService`(取用改硬约束)、`DeepController`(接口增删)、`StepBrief.vue`(状态机扩展)。
- 回滚点:每子任务独立提交;蓝图门可配置降级为自动确认;`V10` 结构变更回滚 = 重置库(数据可清空)。

## 4. `task.py start` 前检查

- [ ] 用户显式批准最终 planning 摘要。
- [ ] `prd.md` / `design.md` / `implement.md` 三件齐备。
- [ ] `implement.jsonl` / `check.jsonl` 各含真实 spec 条目。
- [ ] 子任务已用 `task.py create --parent` 建好并在各自 artifact 写明依赖。
