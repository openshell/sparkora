# 技术设计：深度研究素材覆盖与降级补齐

## 1. 边界与目标

- 链路:`ClarifyService`(研究计划) → `DeepResearchService`(编排) → `SubAgentRunner`(单问题检索+汇总) → `FactSheetService`(手册) → `BriefService`(简报) → `DeepWriterService`(正文)。
- 本次只改「素材获取与降级保真」,不改检索源、不引抓取/rerank(Out of Scope)。
- 单任务承载 R1-R5(同一链路、同一回归面;拆父子任务的检查开销大于收益)。

## 2. 需求 → 设计映射

| 需求 | 设计 | 改动文件 |
|---|---|---|
| R1 降级保留正文 | `rawFallback` 增加 `snippet` 字段;`FactSheetService` 透传入 entry;`DeepWriterService`/`BriefService` 上下文含 snippet | SubAgentRunner / FactSheetService / DeepWriterService / BriefService |
| R2 背景维度(LLM 判断+兜底) | 计划 prompt 列明两类维度;新增确定性 `ensureBackgroundQuestion`(信号词命中且无背景题时补一条 + 同步补 toolHint) | ClarifyService |
| R3 降级可见 | 进度页读取 `agent.search.fallbackReason`(含 `LLM_FALLBACK`) | ResearchProgress.vue |
| R4 截断先重试 | `chat` 统一:首次 2048;任何失败(截断/空内容/非法 JSON)→ 提额 4096 重试一次,仍失败才降级 | SubAgentRunner |
| R5 动态子代理数 | 计划问题数按复杂度 3-7;`maxAgents` 默认 4→6(可配护栏) | ClarifyService / DeepProperties / application.yml / .env.example |

## 3. R1 降级保留正文(snippet)

### 契约(增量字段,向后兼容)
- `rawFallback` 每个 fact:`{"claim":<title>,"snippet":<≤200字原文>,"source":{...},"confidence":0.4|0.6}`。
  - claim 仍为 title(分组键语义不变);snippet 为新增强制字段(无则空串)。
- `FactSheetService.entry` 增加可选 `snippet`:簇内取**首个非空** snippet 写入 entry(新增字段,前端旧逻辑不读不报错;对齐 `sourcesList` 增量范式)。
- `DeepWriterService.write` 的 `factCtx` 追加 `| 证据:{snippet}`(≤200) —— 使写作阶段可见背景原文。
- `BriefService.buildDeepBriefSystemPrompt` 增补一句:手册条目可带 snippet 原文证据,背景/来龙去脉素材须优先从中提取。
- `verifyNumbers`:haystack 用 `sheet.toString()`,自动含 snippet,无需改。

### 边界
- snippet 只做「素材可用」保真,**不替代 LLM 抽取**;降级仍标 FALLBACK + gap。
- snippet 截断 200 字(复用现有 `snippet()`),控制 token。

## 4. R2 背景维度

### 4.1 Prompt(LLM 判断为主)
`ClarifyService.generatePlan` 的 keyQuestions 指令改为:
- 明确两类维度:`事实/参数型`(价格/尺寸/参数/对比)+ `背景/来龙去脉型`(行业背景/企业战略/长期目标/政策脉络/意义)。
- 授权模型按主题取舍:窄参数主题可无背景题;事件/发布/战略/政策类主题**应**含至少一条背景题。
- 问题数由「固定 3-4」改为「3-7,随复杂度」(见 R5)。

### 4.2 确定性兜底(可测)
- 新增 `static` 可测方法 `ensureBackgroundQuestion(Map plan, String topic, String extraInfo)`:
  - 信号词 `BACKGROUND_SIGNALS = {发布,宣布,建成,落成,完成,启用,战略,计划,规划,政策,里程碑,首个,突破,布局,进军}` 命中 topic/extraInfo;
  - 且现有 keyQuestions 无背景型(检测词 `背景,战略,规划,目标,意义,来龙去脉,发展历程,布局,为什么,如何演变`);
  - → 追加一条问题「<主题> 的行业背景、企业战略与长期目标是什么?」,并在 toolHints 追加 `{"question":<新问题>,"tools":["KB","WEB"]}`,保证 1:1 对应(避免落到 KB-only 默认)。
- 幂等:已含背景题或未命中信号词则原样返回。

## 5. R3 降级可见

- 后端已写 `agent.search.fallbackReason`(`LLM_FALLBACK`/`EMPTY`/`ERROR`/`INVALID_URL`/`UNCONFIGURED`)与 `provider`。
- `ResearchProgress.vue`:降级行数据源改为「`search.fallbackReason` 非空」∪「attempts[].ok===false 且有 reason」;`reasonText` 增补 `LLM_FALLBACK: '汇总失败已用原始条目降级'`(展示层,无接口变更)。

## 6. R4 截断/失败重试

- `SubAgentRunner.chat` 重构为:
  - 第 1 次 `chatJson(system,user,2048)`;
  - 失败(截断/空内容/非法 JSON 任一)→ 第 2 次 `chatJson(system,user+纠错说明,4096)`;
  - 仍失败 → 抛出 → `research` catch → FALLBACK。
- 净调用上限仍为 2 次/agent(与原「非法 JSON 重试」同量),仅重试额度提高 + 覆盖截断场景。

## 7. R5 动态子代理数

- `DeepProperties.maxAgents` 默认 4→6;`application.yml` `${DEEP_MAX_AGENTS:6}`;`.env.example` 同步并注明范围。
- 计划问题数 3-7(R2 prompt);`n = min(keyQuestions.size, maxAgents)` 逻辑不变。
- `webQuotaPerAgent = max(1, 8/n)` 不变(总检索预算仍约 8,agent 增多时每 agent 减量)。
- 说明:动态数=「广度来自更多独立子代理」,跨源交叉验证是**可能收益**而非硬承诺(AC 不承诺置信度提升)。

## 8. 兼容与回滚

- 全部为**增量字段**(snippet)与配置默认值调整,**无 schema 变更**。
- 红线段不得触碰:`SearchTool.available()` 无闩锁;`toolHealth` 三键与优先级;`research_notes` 主字段集与状态值域;`/deep/*` 响应主结构。
- 回滚:`maxAgents` 可经 `DEEP_MAX_AGENTS=4` 降级;snippet 为附加字段可忽略;R4 重试可回退为单次。

## 9. 测试策略

- 单测(纯函数/确定性):`rawFallback` 含 snippet 与转义;`FactSheetService` snippet 透传;`ensureBackgroundQuestion`(命中补题/不命中不补/幂等/toolHint 对齐);`chat` 截断重试(Mock AiClient)。
- 既有回归:`SubAgentRunnerTest`/`FactSheetServiceTest`/`DeepResearchService*` 全绿。
- 前端:`npm run build`;`ResearchProgress.vue` 纯展示改动。
- 命令:`mvn -q -DskipTests compile` / `mvn test` / `cd frontend && npm run build`。

## 10. 风险

- R5 agent 数上升 → LLM 汇总次数与 Tavily 调用上升(受 `maxAgents` 与 `8/n` 预算约束)。
- R4 提额 4096 在异常时仅发生 1 次/agent,成本可控。
- snippet 200 字可能不足以覆盖长背景;后续可评估提升上限或抽取式压缩(Out of Scope)。

## 11. R6 深度简报截断容错 + 只保留深度链路(09-26 追加)

### 11.1 背景
R5 放大事实手册(project 52 / brief 66 实测 17 条 / 10294 字，旧链 4486 字)，`BriefService.generateFromFactSheet` 的 `chatJson(...,2048)` 在「3 标题 + 观点 + 大纲 + factRisks」一次产出时被 `finish_reason=length` 截断 → project 52 回 DRAFT + lastBriefError。

### 11.2 契约
- `generateFromFactSheet`:`chatJson(...,8192)`;失败(截断/空/非法 JSON)时 `16384` **重试一次**;仍失败才回 DRAFT + lastBriefError(截断 1000)。
- 重试**独立实现**于 `BriefService`(不抽公共 helper、不共用 FAST 路径);范式对齐 `SubAgentRunner.chat`(R4):失败→提额重试一次→再失败才抛出。
- 首次解析仍走 `AiClient.sanitizeAiJson` 后再 `readValue`。

### 11.3 仅保留深度链路(死代码清理)
- 删除 `BriefService.generate(Long)`(FAST 简报,全仓无调用方)及其专用私有方法 `buildSystemPrompt()`/`buildUserPrompt(ArticleProjectEntity, RagResult)`。
- 删除因此不再使用的字段/依赖:`CarRagService ragService`、`ArticleProjectCarService carService`(仅 FAST generate 使用;`generateFromFactSheet` 不依赖 RAG)。
- 保留 `generateFromFactSheet`、`currentBrief`、`claimGenerating`、`projectStatusGuardMsg`、`citationsJson`、`stuckGenerating`(被 VersionService/ImitationService 复用)与 `STALE_GENERATING_MS`。
- 构造函数随之收窄(移除 ragService/carService 形参),Spring 注入自动适配。

### 11.4 兼容与回滚
- 无 schema 变更。删除公开方法 `generate` 属源码级不兼容,但全仓(含 src/test)无引用 —— 已核实;`currentBrief` 仍被 `ArticleProjectController` 使用。
- 回滚:恢复 `generate` 与依赖即可;提额/重试可回退为 2048 单次。

### 11.5 测试
- 新增 `BriefServiceTest`(Mock AiClient/mapper):① 首次截断(AiException)→ 第二次 16384 成功 → 简报字段落库 + 项目 READY;② 两次均失败 → DRAFT + lastBriefError 且第二次用 16384;③ 首次即成功用 8192(断言额度)。
