# 执行计划:deep-generation

> 依赖阶段 A(unified-retrieval)先行合入。顺序执行,每步验证。

## Step 1 schema 扩展 + 状态机

- [ ] `schema.sql` 幂等加 brief 六列(gen_mode/clarify_questions/clarify_answers/research_plan/research_notes/fact_sheet);entity 同步。
- [ ] 状态常量 CLARIFYING/RESEARCHING(仅深度模式使用)。
- 验证:compile 绿;建库启动无 SQL 错。

## Step 2 SearchTool 抽象与实现

- [ ] `SearchTool` 接口 + `SearchHit`;`KnowledgeSearchTool`(委托统一检索)、`SearxngSearchTool`(超时/空结果降级)、`TavilySearchTool`(REST)。
- [ ] 配置:`SEARCH_WEB_ENABLED`/`TAVILY_API_KEY`/`DEEP_RESEARCH_TIMEOUT_MS` 入 AiProperties/CarProperties 或新 DeepProperties + application.yml + .env.example。
- [ ] 单测:工具降级/空结果容错。
- 验证:compile+test 绿;curl 实测 Tavily(密钥就位后)一次真实调用。

## Step 3 澄清阶段(①②)

- [ ] `ClarifyService`:研究计划生成 + 澄清问题生成(3~5 个事实性问题)+ 需求锁定落库。
- [ ] `DeepController` clarify/clarify-answer 接口(@PreAuthorize EDITOR+)。
- [ ] `ClarifyServiceTest`。
- 验证:compile+test 绿;curl 全流程一次。

## Step 4 研究阶段(③④)

- [ ] `SubAgentRunner`(虚拟线程并行,超时/失败降级);`FactSheetService`(合并/冲突/置信)。
- [ ] `POST /deep/run`:跑研究并落 research_notes/fact_sheet;状态 RESEARCHING→完成。
- [ ] 单测:并行聚合/畸形 JSON 容错/冲突置信规则。
- 验证:测试绿;真实主题跑一次看日志并发与手册条目。

## Step 5 写作与校验(⑤⑥)

- [ ] 深度写作 prompt(风格+手册+锁定需求,数值约束);复用 VersionService 落库链路(gen_mode=DEEP 标记)。
- [ ] `NumericVerifier` 数值回查 → factRisks;日志 + 版本字段记录。
- [ ] 单测:数值抽取/比对用例。
- 验证:测试绿;项目 18 主题深度模式端到端(AC1)。

## Step 6 前端(研究计划与研究过程可视化)

- [ ] 组件四件(`views/project/deep/`):`DeepPlanCard`(研究计划)/`ClarifyForm`(澄清表单两态)/`ResearchProgress`(子代理进度面板+工具健康行+2s 轮询)/`FactSheetSummary`(手册摘要+条目抽屉,来源徽标+置信度条);StepBrief 按状态渲染。
- [ ] 后端支撑:`GET /deep/status` 聚合端点(stage/agents[]/toolHealth/factSheetSummary);`SubAgentRunner` 逐 agent 状态落库(PENDING/RUNNING/DONE/FAILED)。
- [ ] 移动端:面板纵向单列、抽屉全屏、触控 ≥44px。
- 验证:npm run build 绿;深度模式全流程 UI 走查(计划→表单→进度→手册→生成)。

## Step 7 集成验证 + 收尾

- [ ] 端到端 AC1~AC5 全过(证据记 implement.md);快速模式回归。
- [ ] spec 新增 §7「深度生成模式」契约;commit `feat(S9): 六阶段深度生成流程(多代理研究+事实手册+数值回查)`。
- [ ] trellis-check;归档子任务;父任务集成验收后归档。

## 回滚点

- 新表列可空、新接口独立、深度模式开关式入口——任一步 revert 不影响快速模式;`SEARCH_WEB_ENABLED=false` 关外部依赖。