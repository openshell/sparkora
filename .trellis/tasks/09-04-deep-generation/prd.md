# 六阶段多代理深度生成流程(deep-generation)

## Goal

把「简报→正文」的单链路生成升级为**六阶段深度研究流程**:理解主题 → 一次性结构化澄清 → 并行子代理研究(统一知识库 + SEARXNG + Tavily)→ 冲突汇总 → 事实手册 → 写作 → 数值回查校验。同时保留现有单链路作为「快速模式」,深度模式为项目生成时的可选开关。

## 背景

- 文章 18 空话化的根因:AI 手里没有数值清单,只能在参数缺口处绕着写。本流程的核心产出是「事实手册」——正文所有数值的唯一来源,防编造从 prompt 喊话升级为结构性约束。
- 前置依赖:阶段 A 统一检索(子代理查库走 `searchTopKUnified`)。

## 流程定义(六阶段)

```
① 理解   主代理解析主题 → 研究计划 JSON {keyQuestions[], dataNeeds[], hypotheses[], toolHints[]}
② 澄清   从研究计划派生 3~5 个事实性问题(目标读者/对比竞品/立场/篇幅)一次性表单;
         用户提交 → 锁需求(需求 JSON 落库);风格问题不问(风格库职责)
③ 研究   按研究计划并行派生 N(2~4)个子代理(Java 虚拟线程),每个:
         按问题查统一知识库 + 可选 SEARXNG/Tavily → 产出研究笔记
         {topic, facts[{claim, value?, source{KB|WEB,url,块id?}, confidence}], gaps[]}
④ 汇总   主代理合并笔记:冲突检测(数值打架→降置信/标注)、缺口归类 → 事实手册
         {entries[{key, value, source, confidence}], uncoveredQuestions[], warnings[]}
⑤ 写作   风格画像 + 事实手册 + 澄清锁定的需求 → 正文;约束:数值仅可出自手册
⑥ 校验   正则抽正文数值 → 与手册比对:未收录→factRisks(或触发改写);结果随版本落库
```

## Requirements

- **R1 状态机扩展**:项目/简报层新增深度模式状态 `CLARIFYING`(等表单)、`RESEARCHING`(子代理进行中);现有状态机兼容(深度模式状态在 brief 生成前推进,完成后进入既有 BRIEF_READY)。
- **R2 编排服务**:`DeepResearchService` — generateClarify(①②)/ runResearch(③④)/ generateDeep(⑤⑥) 三段式 API,断点恢复:每阶段产物落库,中断可从任意阶段续跑。
- **R3 澄清 API/前端**:`POST /api/projects/{id}/deep/clarify`(AI 生成问题)→ `POST /api/projects/{id}/deep/clarify-answer`(锁定需求);StepBrief.vue 增深度模式入口与表单 UI。
- **R4 SearchTool 抽象**:`KnowledgeSearchTool`(统一检索)/`SearxngSearchTool`(results 空或引擎异常→标记不可用降级)/`TavilySearchTool`(REST,超时重试);子代理按 toolHints 调用;配置 `SEARCH_WEB_ENABLED`、`TAVILY_API_KEY`(.env)。
- **R5 事实手册落库**:brief 侧新增 JSON 字段(fact_sheet),条目带 key/value/source/confidence;写作 prompt 注入手册并约束「数值必须出自手册」。
- **R6 数值回查**:`NumericVerifier` 正则抽取正文数值,与手册比对;未收录→factRisks(high)并在日志记录;前端版本卡展示回查结果。
- **R7 并行与成本**:子代理虚拟线程并行,单子代理超时(可配 `DEEP_RESEARCH_TIMEOUT_MS` 默认 120s)、失败不阻断(缺口进手册);深度模式全流程调用次数上限 12。
- **R8 快速模式保留**:现有 BriefService/VersionService 链路不动,前端保留;深度模式为独立入口。

## Acceptance Criteria

- [ ] AC1 深度模式端到端(集成验证用「海狮08 定价」主题):澄清表单→研究→正文出现具体价格/续航/尺寸数值且均出自事实手册。
- [ ] AC2 子代理并行:日志可见 ≥2 个研究代理并发执行;单代理失败不影响整体(缺口进手册 gaps)。
- [ ] AC3 WEB 来源:至少一条手册条目带 url 来源;SEARXNG 不可用时自动降级 Tavily(日志可见工具选择)。
- [ ] AC4 数值回查:构造正文含手册外数值的用例,factRisks 标注 high。
- [ ] AC5 快速模式回归:现有测试全绿,老流程行为不变。
- [ ] AC6 编译+全量测试绿;`npm run build` 绿;spec §7 新契约落档(含研究可视化数据契约 /deep/status)。
- [ ] AC7 研究过程可视化:深度模式下用户可在 Step1 页看到研究计划、逐子代理进度(进行中/完成/失败)与工具健康、事实手册摘要与每条数值的来源徽标(端到端 UI 走查通过)。

## Constraints

- 检索一律走阶段 A 统一入口(`retrieveForGeneration(query, topK, anchor)`),不重建隔离源。
- 不用 Dify/消息队列/外部编排;并发用 JDK21 虚拟线程,状态落 PG。
- Tavily 免费额度敏感:深度模式每次生成 WEB 搜索 ≤3 次/子代理,总计 ≤8。
- 澄清问题生成与表单锁定状态均落库,可断点续跑。

## Notes

- 三件套中 design.md 定状态机与数据结构;implement.md 分步执行;现状调研(含 SEARXNG 引擎不可用实测)见父任务 `research/current-state.md`。