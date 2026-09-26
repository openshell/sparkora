# 执行计划：深度研究素材覆盖与降级补齐

## 前置

- 任务已 in_progress(`task.py start` 于批准后执行)。
- 校验命令(每步后至少跑编译,末步全量):
  - `mvn -q -DskipTests compile`
  - `mvn test`
  - `cd frontend && npm run build`

## 有序清单

### 步骤 1 — R1 降级保真(SubAgentRunner)
- [ ] `rawFallback`:每条 fact 增 `"snippet":"<esc(snippet(h.snippet()))>"`(≤200)。
- [ ] `SubAgentRunnerTest`:新增用例——含 snippet 的命中降级后 JSON 合法且含正文;含引号/换行/小数不破坏 JSON。
- [ ] `mvn -q -DskipTests compile` + `mvn test`(SubAgentRunnerTest)。

### 步骤 2 — R1 手册/写作透传(FactSheetService + DeepWriterService + BriefService)
- [ ] `FactSheetService.entry(...)` 增可选 `snippet` 参数;各调用点取簇内首个非空 snippet 写入;无则不写(保持旧契约)。
- [ ] `FactSheetServiceTest`:新增「降级 fact 带 snippet → entry.snippet 透传」「无 snippet 不出现该字段」。
- [ ] `DeepWriterService.write`:factCtx 追加 `| 证据:{snippet}`(≤200)。
- [ ] `BriefService.buildDeepBriefSystemPrompt` 增补背景素材提取说明。
- [ ] `mvn test`(FactSheetServiceTest)。

### 步骤 3 — R2 背景维度(ClarifyService)
- [ ] keyQuestions prompt 改为「3-7、两类维度、按主题取舍」。
- [ ] 新增 `static ensureBackgroundQuestion(Map,String,String)` + 常量 `BACKGROUND_SIGNALS`/`BACKGROUND_TERMS`;`generatePlan` 中调用(问题与 toolHint 同步补)。
- [ ] 新增 `ClarifyServiceTest`:命中信号词补题且 toolHint 对齐;含背景题不重复补;窄参数主题不补;toolHints 非数组时不抛异常。
- [ ] `mvn test`(ClarifyServiceTest)。

### 步骤 4 — R5 动态子代理数(DeepProperties + yml + env)
- [ ] `DeepProperties.maxAgents` 默认 4→6;`application.yml` `${DEEP_MAX_AGENTS:6}`;`.env.example` 同步注明范围/含义。
- [ ] 既有 `DeepResearchService*Test` 不回归(默认值变化若断言 4 需同步)。
- [ ] `mvn test`。

### 步骤 5 — R4 截断/失败重试(SubAgentRunner)
- [ ] `chat` 重构:首 2048 → 失败(截断/空/非法 JSON)提额 4096 重试一次 → 仍失败抛出。
- [ ] `SubAgentRunnerTest`:新增「首次截断(AiException)→ 第二次成功,最终 DONE」「两次失败 → FALLBACK」(Mock AiClient)。
- [ ] `mvn test`。

### 步骤 6 — R3 前端降级可见(ResearchProgress.vue)
- [ ] 降级行数据源改为 `search.fallbackReason` ∪ 失败 attempts;`reasonText` 增 `LLM_FALLBACK`。
- [ ] `cd frontend && npm run build`。

### 步骤 7 — 文档同步
- [ ] `docs/spec/brief-generation.md`:研究计划两类维度/3-7、降级 snippet 保真、`maxAgents` 默认、汇总重试语义。
- [ ] `.trellis/spec/backend/ai-rag-guidelines.md`:新增「降级必须保真原始证据(snippet)」Scenario(7 段式)。
- [ ] 检查 `docs/spec/settings.md` 是否需同步(仅当 `maxAgents` 属其收录范围)。

### 步骤 8 — 全量验证与检查
- [ ] `mvn -q -DskipTests compile` / `mvn test`(全绿) / `cd frontend && npm run build`。
- [ ] 派发 `trellis-check` 复核 R1-R5 + 红线。
- [ ] `git status`/`git diff` 审阅;不提交(除非用户明确要求)。

## 风险文件

- `src/main/java/com/sparkora/deep/service/SubAgentRunner.java`(降级+重试,核心)
- `src/main/java/com/sparkora/deep/service/FactSheetService.java`(手册契约,增量字段)
- `src/main/java/com/sparkora/deep/service/ClarifyService.java`(计划确定性兜底)
- `src/main/resources/application.yml` + `DeepProperties.java` + `.env.example`(默认值三处同步)

## 回滚点

- 每步骤独立可回滚;`maxAgents` 经 `DEEP_MAX_AGENTS=4` 即降级;无 schema 变更、无数据迁移。
