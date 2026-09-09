# 简报生成重设计:检索设置页 + 取消快速模式全走深度 + 知识库可暂停

## Goal

按用户 2026-09-09 源需求重新设计简报生成链路:

1. **创作不局限于车型**——文章创作面向所有汽车相关知识(不限于车型参数),核实并清理「车型绑定」残留。
2. **知识库可暂停、外部搜索优先**——新增**设置页面**控制「内部知识库」「外部搜索」的启用;知识库存在缺陷,暂停期间**优先使用外部搜索到的资料**。
3. **取消快速模式**——所有生成必走深度模式(理解 → 澄清 → 子代理并行研究 → 汇总写作)。

## 源需求与已确认决策(2026-09-09,decision_id: dec-8af816b18419ad57)

| 决策点 | 结论 | 现状依据 |
|---|---|---|
| 任务方式 | 创建 Trellis 任务,规划后实施 | — |
| 「暂停知识库」落地方式 | **新增设置页面**,控制内部(本地知识库)/外部搜索的启用——非 .env 开关 | 现状无任何运行时开关;`AI_RAG_KB_ENABLED` 仅控制 KB 块、且为 .env 静态配置 |
| 外部搜索接入点 | **取消快速模式,所有生成必走深度模式** | 外部搜索(Searxng/Tavily)目前仅 DEEP 模式子代理可用(`SubAgentRunner` + `SearchTool` 抽象) |
| 「不局限车型」 | S8 统一检索已大体达成(全库检索、车型降为锚点加权),本任务核实残留即可 | `CarRagService.searchTopKUnified` 已全库 UNION ALL 检索 |

## Background(现状盘点,2026-09-09 已核实)

- **FAST 模式**:`BriefService.generate` / `VersionService` → `ragService.retrieveForGeneration(p.getTopic(), 8, modelIds)`,**必查**本地知识库(CAR 车型域 + KB 通用域),车型仅作锚点加权(`AI_RAG_ANCHOR_BOOST`)。rag_status 四态:OK / FAILED / LOW_CONFIDENCE / NO_KNOWLEDGE,均落库透出。
- **DEEP 模式**:`DeepController`(`POST /deep/clarify` 研究计划+澄清 → `POST /deep/generate` 深度写作+数值回查),`SubAgentRunner` 子代理可携带 `SearchTool`(`SearxngSearchTool` / `TavilySearchTool`),`FactSheetService` 事实手册;来源可信度规则:KB > WEB 交叉验证 ≥2 源 > 单一 WEB。
- **外部搜索配置**:`SEARXNG_BASE_URL`、`DEEP_TAVILY_API_KEY`(或 `TAVILY_API_KEY`)均走 .env。
- **模式选择**:项目创建时选 FAST/DEEP(genMode),FAST 单链路生成,DEEP 六阶段流程。

## Requirements

### R1 检索设置页(新增)

- 新增系统设置存储(表 `sparkora_setting`,幂等建表,与 schema.sql 三处同步),至少两个设置项:
  - `kbEnabled`(内部知识库启用,默认 false——用户已决策暂停);
  - `webSearchEnabled`(外部搜索启用,默认 true)。
- 后端 `SettingService` 提供读取(生成链路运行时读取,非 .env);写接口 `@PreAuthorize("hasRole('ADMIN')")`——系统级设置影响全局生成行为,收 ADMIN;读接口 ADMIN/EDITOR 可见。
- 前端新增设置页路由(如 `/settings`),展示两个开关 + 说明文案(「知识库检索存在缺陷,暂停期间生成将优先使用外部搜索资料」类提示);沿用 Element Plus 移动端优先规范。
- 设置变更仅影响**之后的生成**,已生成产物不追溯。

### R2 取消快速模式(模式收敛)

- **所有项目生成必走深度流程**:创建项目不再选模式(genMode 收敛为 DEEP);简报/版本生成链路统一走 `DeepController` 六阶段(理解 → 澄清表单 → 子代理并行研究 → 事实手册 → 写作 → 数值回查)。
- FAST 专属链路(`BriefService.generate` FAST 分支、`VersionService` 快速生成)下线:存量 FAST 项目继续可用现有产物(简报/版本/预览/发布),但**重新生成时走深度流程**。
- 状态机不变:沿用现有 GENERATING_BRIEF / READY / GENERATING_VERSIONS 等状态与原子抢占。
- 与在规划中的仿写任务(09-09-article-imitation)解耦:仿写是独立 genSource(IMITATION),不走主题研究流程,本任务不改变其 PRD 决策(仿写不查知识库)。

### R3 知识库暂停 + 外部搜索优先

- `kbEnabled=false` 时:
  - 深度模式子代理研究**不装配 KB 检索工具**,仅用 WEB 搜索(Searxng/Tavily);
  - 检索状态落 `DISABLED`(rag_status 新增枚举值,spec 契约同步),前端展示「知识库已停用(全局设置)」,不与 NO_KNOWLEDGE 混淆;
  - 不注入任何本地知识块到 prompt。
- `webSearchEnabled=false` 且 `kbEnabled=false` 时:子代理无资料工具,研究靠模型自身知识,prompt 明确要求 factRisks 标注「未检索任何外部资料」。
- 两者都启用时维持深度模式现有规则(KB 优先,WEB 交叉验证 ≥2 源)。
- SEARXNG 不可用降级 Tavily 的现有逻辑不变。

### R4 主题不局限车型(核实与清理)

- 核实并清理车型绑定残留:前端「写作锚点车型」文案、提示词中车型绑定表述、创建页车型关联交互(保留为可选增强项,非必填门禁)。
- 车型锚点加权逻辑保留(S8 已是增强而非门禁),但明确其语义为「可选加权」。

### R5 规格同步

- `docs/s0-spec.md` 修订:§6b/6c 检索契约增补「设置开关」语义与 `DISABLED` 状态;§7 深度模式契约改为**唯一生成模式**(快速模式条目作废标注);设置接口契约字段级文档;`sparkora_setting` 表结构三处同步(schema.sql + entity/mapper + spec)。

## Constraints

- 后端响应走 `R<T>` 包装;写操作 `@Valid` DTO;角色模型仅 ADMIN/EDITOR/VIEWER。
- 不改 S5 发布链路;图片链路不动。
- 设置存储不用 .env(用户要求页面控制,.env 保留作为部署级默认值兜底可后续评估,本任务不做)。
- 深度模式为 AI 密集流程,取消快速模式会显著增加生成时长与 token 成本——这是用户已确认的方向,PRD 不再保留快速回退开关(回滚靠 git revert)。
- `mvn -q -DskipTests compile` 通过;前端 `npm run build` 通过。

## Acceptance Criteria

- [ ] AC1 设置页可读写两个开关;非 ADMIN 写操作被 `@PreAuthorize` 拒绝;设置落库且重启后保留。
- [ ] AC2 `kbEnabled=false` 时,深度模式生成(日志可证)子代理仅装配 WEB 搜索工具,无本地知识块注入;产物 rag_status=`DISABLED`,前端正确展示停用文案。
- [ ] AC3 `webSearchEnabled=true` 时,研究笔记/事实手册出现 WEB 来源引用(来源 URL 可见);SEARXNG 不可用时自动降级 Tavily 不阻断。
- [ ] AC4 新建项目无模式选择入口,生成必走深度流程(澄清表单出现);存量 FAST 项目产物可读,重新生成走深度流程。
- [ ] AC5 外部搜索关闭+知识库停用的双重关闭场景生成仍成功,factRisks 含「未检索外部资料」标注。
- [ ] AC6 创建/简报页无「车型必选」门禁残留;主题为非车型类汽车知识(如「新能源置换政策解读」)可正常走深度流程生成。
- [ ] AC7 `docs/s0-spec.md` §6b/6c/§7 与 schema.sql/entity/mapper 三处同步完成;`mvn -q -DskipTests compile` 与 `npm run build` 通过。

## Notes

- 复杂任务:需 design.md + implement.md 后再 `task.py start`。
- 与进行中任务 `09-03-rag-mandatory-gate` 的关系:其「必查+降级可见」语义被本任务 supersede(必查改为设置开关控制),归档时处理该任务状态。
- 外部搜索资料「优先」语义:知识库停用期间,WEB 搜索结果是唯一资料来源;非停用期间维持 KB > WEB 交叉验证规则。