# 文章生成认知层重构:澄清意图对话 + 简报写作蓝图

## Goal

把「澄清」与「简报」两阶段从**工程可用**升级为**认知正确**,以提升成文质量:

- 澄清:从「LLM 一次性生成定长问卷」改为「正交的**意图澄清**(多轮自适应、缺口驱动、以信息充分性为收敛条件)+ **研究规划**(纯事实问题,不面向用户)」,产出结构化 **TaskBrief** 作为下游唯一契约。
- 简报:从「以手册为唯一事实来源的摘要」改为「**写作蓝图**(中心论点/论证结构/证据映射/缺口声明/质量信号)」,写作阶段按证据映射**按需取用**手册条目,替代整块注入。

用户授权:不在意改造成本,**现有项目数据可直接清空**(无历史兼容负担),只追求彻底解决问题。

## Background / Confirmed Facts(仓库证据)

- `ClarifyService.java:152` `generatePlan` 单次 LLM 调用同时产出 `research_plan`(含 `keyQuestions`/`dataNeeds`/`hypotheses`/`toolHints`)与 `clarify_questions`;两类问题**混在同一 `keyQuestions` 一维列表**,靠 `isBackgroundQuestion`(:238)事后区分。
- 澄清为**一次性定长表单**:`lockAnswers`(:327)按问题文本 key 锁定答案;无追问、无充分性判定、无结构化意图契约。
- `BriefService.java:76` `generateFromFactSheet` 以「事实手册(唯一事实来源)」生成 `BriefDto`(`titleCandidates`/`audienceRefine`/`coreViewpoints`/`outline`/`factRisks`),本质是手册的选择性复述,无 thesis、无论证关系、无 evidence map。
- `DeepWriterService.write` 把**整本 fact_sheet** 注入 prompt(按 `kind` 分组 + 四字段),论点与证据未显式绑定。
- 状态机与异步骨架已就绪(`ProjectStatusService` 唯一写权、`start*`/`@Async run*`、轮询),本次重构**不改工程骨架**,只改认知层产物与契约。
- 现有契约文档:`docs/spec/brief-generation.md`、`docs/article-generation-flow.md`、`docs/spec/version-generation.md`;需同步更新。

## Requirements

- R1 **澄清正交拆分**:意图问题(问用户)与研究问题(问世界)彻底分离,各自独立生成与演进,不再共用 `keyQuestions`。
- R2 **意图澄清多轮对话式自适应**(OQ1 已定,用户选择):缺口驱动(slot/gap model),每轮基于已有答案动态决定「下一个最佳问题」或「收敛」;逐题/分批,支持选项/默认值/跳过;答案可触发追问;以「信息是否足以产出高质量初稿」为收敛条件(非固定题数)。UI 需从一次性表单改造为对话/逐轮交互。LLM 调用轮次随对话增长,成本不作为约束。
- R3 **结构化 TaskBrief**:澄清产出显式意图契约(目的/读者/语气/必覆盖/必避/切入角度/期望行动等槽位),每槽位带值、来源(用户/推断/默认)、置信度;作为研究规划与写作的唯一输入。
- R4 **研究规划独立**:基于 TaskBrief 产出纯事实型子问题 + 假设 + toolHints,不暴露给用户当表单,驱动既有并行研究链路。
- R5 **简报即写作蓝图**:产出 `thesis` + 分节论证结构(论点/角色/叙事意图)+ **evidenceMap**(论点→所需证据→绑定 fact_sheet entry key,含覆盖状态)+ narrativeArc + constraints + 缺口声明 + 质量信号。
- R5b **蓝图人工评审门**(OQ2 已定,用户选择「人工确认后写作」):蓝图生成后**暂停**,前端结构化展示 thesis/论证结构/evidenceMap/质量信号,用户可编辑论点、调整证据绑定、确认后才进入写作;未确认不自动串接写作。
- R6 **写作按证据映射取用(硬约束, OQ3 已定)**:写作阶段依蓝图分节取用**绑定的**手册条目,不再整块注入;蓝图未映射的 fact/数值**不得**进入正文;若某论点必需证据但缺失,模型须显式声明「无证据断言」而非自由编造,并配合既有数值回查硬校验。
- R7 **质量信号与可评review**:蓝图带可观测质量信号(论点密度/证据覆盖率/缺口数/与 TaskBrief 一致性);蓝图可作为人工评审/编辑对象(至少结构化展示)。
- R8 **契约与文档同步**:数据模型、接口契约(`docs/spec/brief-generation.md`)、流程图(`docs/article-generation-flow.md`)同步更新;新增 Flyway 迁移。
- R9 **工程骨架复用**:沿用 `ProjectStatusService` 状态写权、`start*`/`@Async run*`+轮询范式、降级可见、AI 截断提额重试、元话语三层防线。

## Out of Scope

- 不改发布/配图/预览链路。
- 不引入外部 MCP/Agent 框架或新重型依赖。
- 不保留存量项目数据兼容(允许清空)。

## Key Decisions(用户已定)

- D1 意图澄清采用**多轮对话式**(每轮动态决定下一问/收敛,充分性为收敛条件)。
- D2 写作蓝图设**人工评审门**:生成后暂停,用户确认后才写作。
- D3 evidenceMap 对写作为**硬约束**:未映射证据/数值不得进正文。
- D4 任务树拆分由规划者定(见下),属规划结构非产品决策。

## Task Tree(规划结构,D4)

父任务 `gen-cognitive-redesign` 持有需求集与跨子任务验收;拆为可独立验收的子任务(依赖写在子任务 artifact 内,非树位置暗示):

- C1 `意图澄清对话` — 数据模型 + 多轮自适应服务 + TaskBrief 契约。
- C2 `研究规划拆分` — 从澄清剥离纯事实研究规划,驱动既有研究链路。
- C3 `简报写作蓝图` — thesis/论证结构/evidenceMap/质量信号 + 人工评审门。
- C4 `写作按映射取用` — evidenceMap 硬约束驱动写作 + 数值回查衔接。
- C5 `前端认知层交互` — 对话式澄清 UI + 蓝图评审 UI。
- C6 `契约/文档/迁移同步` — Flyway + `docs/spec/**` + 流程图。

依赖:C1→C2;C1/C2→C3;C3→C4;C1/C3→C5;全程 C6 随各子任务同步。

## Acceptance Criteria

- [ ] AC1 意图问题与研究问题在数据模型与接口上物理分离,不再混装。
- [ ] AC2 澄清为多轮自适应:轮次由充分性判定驱动(可通过构造用例证明「信息足够即停止」「不足则追问」)。
- [ ] AC3 澄清产出结构化 TaskBrief(槽位含来源/置信度),下游研究/写作仅依赖 TaskBrief。
- [ ] AC4 简报产出含 thesis + 论证结构 + evidenceMap(绑定 entry key)+ 质量信号的写作蓝图。
- [ ] AC5 写作阶段按 evidenceMap 取用证据;蓝图外数值不进入正文(可用用例验证)。
- [ ] AC6 蓝图质量信号可计算且随产物落库/展示。
- [ ] AC7 `docs/spec/**` 与流程图同步,新增 Flyway 迁移,后端编译 + 前端构建通过,相关单测通过。
- [ ] AC8 状态机/异步/降级/重试行为无回归。
- [ ] AC9 蓝图人工评审门生效:未经用户确认不自动串接写作;确认后解锁。

## Cross-Child Acceptance

- C1→AC1/AC2/AC3;C2→AC1/AC4;C3→AC4/AC6/AC9;C4→AC5/AC8;C5→AC2/AC6/AC9(UI);C6→AC7。
- 集成验收:澄清→规划→研究→蓝图(评审门)→写作 全链路在清空数据的库上端到端走通。

## Notes

- 本任务为复杂任务,已产出 `design.md` + `implement.md`;子任务 C1~C6 见 `Task Tree`。
