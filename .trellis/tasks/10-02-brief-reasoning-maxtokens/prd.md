# 澄清阶段展示AI思考过程并修复max_tokens截断

> 回链：`docs/spec/brief-generation.md`（§1 六阶段、§3 接口、§7 前端）、`docs/spec/project-lifecycle.md`

## Goal

1. 修复「澄清/研究计划」阶段因 reasoning 模型 `max_tokens=4096` 被推理 token 吃光导致的 JSON 截断失败（页面报「上次生成失败：AI 输出被 max_tokens 截断…」）。
2. 把该阶段 AI 的推理过程（reasoning）落库并在澄清页呈现（**方式 A：事后一次性展示**，复用现有异步+轮询链路）。
3. 澄清阶段 prompt 须结合「创建项目时用户输入的信息」。
4. **【新增】重构项目附属信息字段**：删除 `keywords`（关键词）与 `remark`（备注）；把 `extraInfo`（补充信息）**改名并升级为 `contentDescription`（内容描述）**（Q3=A 等价替代 + 存量数据迁移），使其**影响全链路**（创建落库→澄清→研究→简报→深度写作→多版本）；并确保 **内容描述 + 目标读者 + 目标字数** 进入生成 prompt。
   - 澄清：`extraInfo` 不是被删除的概念，只是换名（`contentDescription`）并扩大生效范围；真正删除的是 `keywords` 与 `remark`。

## Background（已核实事实）

### 失败根因（2026-10-02 线上实测）

- 报错来自**前置的研究计划步骤**而非简报本身。文案里的 `keyQuestions` 是研究计划 schema，产出点为 `ClarifyService.generatePlan` → `aiClient.chatJson(system, user, 4096)`（`src/main/java/com/sparkora/deep/service/ClarifyService.java:192`）。
- 线上项目 id=59 / brief=74、75 均以 `研究计划异步生成失败 briefId=…: AI 输出被 max_tokens 截断（finish_reason=length）` 落 `project.last_brief_error`，前端 `StepBrief.vue:199-200` 展示为「上次生成失败：…」。
- `.env` 配置 `AI_MODEL=deepseek-v4-pro-cus`；axonhub 把该别名路由到 **`deepseek-v4.1-flash`（reasoning 模型）**，先输出大段 `reasoning` 再输出 `content`。
- 实测复现（同一 prompt，`max_tokens=4096`）：`completion_tokens=4096`、`finish_reason=length`、`content=""`（推理约 9004 字）。提到 `max_tokens=16384` 即成功：`finish_reason=stop`、`completion_tokens=6064`、`content` 2579 字。
- 附带风险：48h 日志中同一别名还出现 `422 model not found: deepseek-v4-pro-cus`（brief=72），axonhub 侧别名路由不稳定（属运维，见 Out of Scope）。
- `AiClient.parseChat` 目前**只取 `content`、丢弃 reasoning**（`src/main/java/com/sparkora/ai/AiClient.java:194-207`）；`ChatResult` 为 `(content, model, totalTokens, finishReason)`（`AiClient.java:55`）。

### 澄清阶段当前输入口径

- 后端 prompt 仅由 `topic` + `extraInfo` 组装（`ClarifyService.java:188-191`），**未使用** `keywords` / `audience` / `wordCountTarget`。
- 项目创建落库 `topic/keywords/audience/wordCountTarget/extraInfo/remark`（`ArticleProjectController.java:92-99`）。
- 前端发起入口只传 `{topic, extraInfo}`（`frontend/src/views/ProjectEdit.vue:194`、`frontend/src/api/index.js:41-42`）；`/deep/clarify` 请求体同为 `{topic?, extraInfo?}`（`DeepController.java:60-67`）。

### 待删字段的全部引用点（已核实）

| 字段 | 引用点 |
|---|---|
| `keywords` | `ProjectEdit.vue:69,122`（表单）；`ProjectList.vue:60-64`（列表列）；`ProjectRequest.java:18`、`ArticleProjectEntity.java:22`、`ArticleProjectController.java:93,114,132`；`CarModelMatcherService.match(topic,keywords)` `:44,70`（`CarModelMatcherService.java` 全文调用点在 `ArticleProjectController.java:114` 与 `DeepResearchService.java:447`）；`VersionService.java:300`（prompt） |
| `extraInfo` | `ProjectEdit.vue:91,122,194`；`StepBrief.vue:456`；`api/index.js:41-42`；`DeepController.java:60,67-68`；`ClarifyService.java:69,93,103,105,149,189-190,201,244`；`VersionService.java:309-311`（prompt 注入）；`ProjectRequest.java:32`、`ArticleProjectEntity.java:36`、`ArticleProjectController.java:97,136` |
| `remark` | `ProjectEdit.vue:96,122`；`ProjectRequest.java:47`、`ArticleProjectEntity.java:31`、`ArticleProjectController.java:99,138`（仅存/回写，不进 prompt、无展示） |

### 现有链路与可复用范式

- `/deep/status` 已透出 `researchPlan/questions/answers/agents/factSheet`（`DeepController.java:209-213`），前端 `applyDeepStatus` 消费（`StepBrief.vue:386-393`）。
- brief 表已有 `research_plan` / `plan_status`（`ArticleBriefEntity.java:37-42`）；Flyway 现至 `V3__embedding_model.sql`，下一版为 `V4__`（`db/migration/README.md`）。
- 提额重试先例：`BriefService` 8192→16384（`BriefService.java:96-108`）、`SubAgentRunner` 2048→4096、`DeepWriterService` 4096→8192、`VersionService`(4096)。
- 前端组件：`DeepPlanCard` 在研究计划就绪的各态渲染（`StepBrief.vue:213,220`），是思考过程面板的天然挂载点。

## Requirements

- **R1（修复截断）**：澄清阶段 LLM 调用提高 `max_tokens` 并加「截断/空内容/非法 JSON → 提额重试一次」范式（对齐 `BriefService`）。
- **R2（思考过程落库+接口透出）**：`AiClient` 透出 reasoning，`ClarifyService` 落库至 brief 新列，`/deep/status` 增量透出（旧契约不破坏）。
- **R3（前端呈现）**：澄清页（CLARIFYING/CLARIFIED/RESEARCHING/RESEARCH_DONE）以可折叠面板展示该阶段 AI 思考过程；无 reasoning 时隐藏。
- **R4（澄清 prompt 结合创建输入）**：澄清 prompt 注入 **内容描述 + 目标读者 + 目标字数**（`topic` 仍在）。
- **R5（附属信息字段重构）**：删除 `keywords`/`remark` 两个字段（前端表单+列表列、DTO、entity、DB 列、控制器、所有调用点）；将 `extraInfo` **改名为 `contentDescription`（内容描述）并迁移存量数据**，贯穿全链路（创建落库→澄清→研究→简报→深度写作→多版本）；`VersionService` 等既有 prompt 中相应位置改用内容描述。
- **R4b（内容描述进研究链路）**：研究阶段也须让内容描述生效——KB 复合 query、WEB query、子代理汇总上下文纳入内容描述，使内容描述「影响全链路」。
- **R6（文档同步）**：`docs/spec/brief-generation.md` 字段级契约、`/deep/status` 响应表、`.env.example`（如需）同步；DB 结构三处同步（迁移脚本+entity/mapper+spec）。

## Acceptance Criteria

- [ ] AC1 用 reasoning 模型对「比亚迪9月销量发布」类主题触发 `/deep/clarify`，研究计划成功生成（不再 `finish_reason=length`）。
- [ ] AC2 截断/空内容/非法 JSON 时确实提额重试一次，仅两次均失败才写 `last_brief_error`。
- [ ] AC3 `/deep/status` 在研究计划就绪后返回 AI 思考过程；历史/非推理模型数据下缺省，前端不报错。
- [ ] AC4 澄清页可见「AI 思考过程」折叠面板；无内容时隐藏。
- [ ] AC5 澄清 prompt 实际包含 内容描述 + 目标读者 + 目标字数（测试断言 prompt 内容）。
- [ ] AC6 创建/编辑表单不再含 关键词/备注；原「补充信息」位置改为「内容描述」且可编辑保存；项目列表不再展示关键词列；存量项目内容描述不丢（迁移自 extra_info）。
- [ ] AC7 删除 keywords/remark 后全链路编译通过、无残留引用；车型识别改用 主题+内容描述；既有测试同步更新。
- [ ] AC9 研究阶段的 KB 复合 query、WEB query 与子代理汇总上下文包含内容描述（测试断言）。
- [ ] AC8 `mvn test` 全绿；`npm run build` 绿。

## Out of Scope

- 研究阶段子代理/深度写作/多版本等其它 `chatJson` 调用的 reasoning 透出与额度调整（除非通用方案明确覆盖）。
- 更换 `AI_MODEL` 或修复 axonhub 侧别名路由（运维）。

## Open Questions

- **Q4（待决）**：内容描述在**研究阶段**的注入方式——直接拼进 KB 复合 query / WEB query（会实质改变检索语料，可能引入噪声），还是仅注入子代理 LLM 汇总的 system/user 上下文（让 AI 理解写作意图，不改检索 query）？决定 R4b 的实现面与检索行为影响。
- 已决：**Q1 = A**（思考过程事后一次性展示）；**Q2 被字段重构取代**（keywords 删除）；**Q3 = A**（`extraInfo` 改名 `contentDescription`、存量迁移、全链路生效）。

## Notes

- 本任务跨前后端（AiClient / ClarifyService / DeepController / ArticleProjectController / DTO+entity+Flyway / StepBrief+ProjectEdit+ProjectList+组件 / spec），判定为复杂任务，需 `design.md` + `implement.md`。
- 已确认：Q1 = A（事后一次性展示）；Q2 被本次字段重构取代（keywords 删除），进 prompt 的字段改为 内容描述/目标读者/目标字数。
- Q3 解决前不得进入实现。
