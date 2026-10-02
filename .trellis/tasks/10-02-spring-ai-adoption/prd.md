# 引入 Spring AI 2.0 重构 AI 应用层

## Goal

把当前"手写 RestClient + 散落 prompt 文本块 + 事后正则补丁"的 AI 应用层，重构为
**可持续演进的规范化 AI 平台层**：统一调用（ChatClient）、结构化输出契约、提示词资产化、
任务级模型参数与可观测性，消除字段漂移与元话语泄漏类系统性问题。

用户明确诉求：彻底改造、不在乎改造成本、以规范与可持续发展为第一目标。
技术设计见 `design.md`，执行计划见 `implement.md`。

## Key Decisions

- **D1 架构姿态 = 直接采用 Spring AI**（不建自研 Gateway 抽象）。领域服务直接注入框架类型
  （`ChatClient`/`EmbeddingModel`/`ImageModel`/`Advisor`）。接受后果：领域代码与框架强耦合、
  升级/回退波及面大；换取最薄抽象与最少样板。缓解见 `design.md` §7。
- **D2 版本 = 一步到位 Boot 4.0/4.1 + Spring AI 2.0**。两次大版本跳跃叠加，故 C0（依赖预检 +
  探针）为阻塞其余全部子任务的前置硬门；若某依赖无 Boot 4 兼容版，回退 Boot 3.5 + Spring AI 1.1。
- **D3 范围 = 全面改造含 RAG 存储**：模型调用层 + 4 张 embedding 表改接 Spring AI PgVectorStore（含 ETL）。
- **D4 行为基线 = 契约等价，生成质量允许提升**：接口契约（R<T>/路由/DB schema/前端交互）不得变；
  AI 生成内容质量与内部实现允许改进（修 schema 漂移、元话语泄漏、数值误报等既有缺陷）。
- **D5 Prompt = 文件化 + 模板引擎 + Git 版本管理**：迁至 classpath 资源（`resources/prompts/*.st`），
  Spring AI `PromptTemplate`/`SystemPromptTemplate` 渲染，带版本标识；不入库、无管理后台。

## Background / Confirmed Facts（代码勘察）

框架与版本约束：

- 当前 Spring Boot **3.3.4** / Java 21（`pom.xml:9-21`）；MyBatis-Plus 3.5.7、Flyway、JJWT 0.12.6 随 Boot BOM。
- 官方版本矩阵：Spring AI **1.x 要求 Boot 3.4+**，Spring AI **2.0 要求 Boot 4.0/4.1**；
  LangChain4j 可在 Boot 3.3 上运行（未选）。
- 模型入口是自建聚合代理 **axonhub**（`https://axo.caiqz.cn`，单 key，按 model 名路由，
  OpenAI 兼容）。其是否透传 `json_schema` strict / tool calling / reasoning 未知 → C0 探针（见 `design.md` §4）。

现有 AI 调用面（待收敛对象）：

- 唯一入口 `com.sparkora.ai.AiClient`：手写 `RestClient` 直调 `/v1/chat/completions`；
  `chatJson` 强制 `response_format=json_object`（:128），无 `json_schema`；
  `temperature` 全局单值 0.7（`config/AiProperties.java:27`）；
  失败靠 `sanitizeAiJson`（:86）事后剥围栏/转义控制字符 + 调用方盲目提额重试。
- 调用方（分散）：`BriefService`、`deep/service/ClarifyService`、`deep/service/DeepWriterService`、
  `deep/service/SubAgentRunner`、`service/VersionService`、`service/ImitationService`、
  `service/QaService`、`car/service/AiParamCleaner` 等。
- 结构化产物 DTO：`ai/BriefDto.java`（无 `@Valid`/schema 校验，反序列化成功即入库）；
  schema 字面量内联在 prompt 与 DTO 类**两处硬编码**，无单一来源。
- 元话语泄漏补丁链：`service/ReaderViewRules` + `service/MetaLeakCleaner`（句级正则删除），
  根因是"写给作者的指令(`fact_risks[].suggestion`)与素材混入同一上下文"。
- 数值校验 `DeepWriterService.verifyNumbers`（:497）用子串 `contains` 比对，存在
  `"1200"` 命中 `"12000"` 的漏报。
- 自研 agent 循环 `deep/service/SubAgentRunner`；多轮问答 `qa/service/QaService` 手拼 messages。
- 自研 RAG：4 张 embedding 表（car/kb/news/image，`V3__embedding_model.sql`）同向量空间（维度 1024），
  统一检索 SQL 按域隔离候选窗口 + 锚点加权/置信度/跨源合并（`FactSheetService`/`CarRagService`）。
  向量模型名防护（写入盖名 + 检索过滤）见 `database-guidelines.md`。
- 自研 embedding 客户端 `car/client/EmbeddingClient`（`/v1/embeddings`，可被 Spring AI OpenAI 覆盖）；
  自研图客户端 `ai/AiImageClient`（`/v1/images/generations` + **`/v1/images/edits` multipart 图生图**，
  后者 Spring AI 图模型未必覆盖，保留自研）。
- 自研状态机 `service/ProjectStatusService`（写权唯一持有者）；发布链路 `WenyanServerService` +
  `PublishService`（**范围外**）。

## Requirements

- **R1** 选定并锁定版本组合：Boot 4.x + Spring AI 2.0（D2），完成依赖兼容预检（C0）。
- **R2** 全部 AI 文本调用经 Spring AI `ChatClient`，替换 `AiClient` 手写 RestClient 调用。
- **R3** 结构化输出：schema 单一事实来源（由 DTO 类型派生）+ 解析后校验 + **带具体校验错误的
  自纠错重试**（`validateSchema`），替换盲目提额；截断（`finish_reason=length`）独立处理。
- **R4** Prompt 资产化：迁出 Java 文本块到 `resources/prompts/*.st` + 模板渲染 + Git 版本（D5）。
- **R5** 任务级模型参数：按 `TaskType` 区分 temperature/model/预算（结构化低温、正文高温）。
- **R6** 可观测性：token/成本/时延/失败率指标与调用日志。
- **R7** agent 链路用 Tool Calling 替换手写循环；QA 用 ChatMemory 替换手拼 messages。
- **R8** 4 张 embedding 表迁至 Spring AI `PgVectorStore`，保留业务 RAG 打分/配额/锚点/合并；
  `embedding_model` 元数据防护不得回归。
- **R9** 元话语泄漏根因治理（数据层分离作者指令/读者素材）+ `verifyNumbers` 数值归一化比对。
- **R10** 迁移顺序与可回退策略（strangler fig）：每条链路保留旧实现直到新实现验收达标。
- **R11** 契约等价（D4）：控制器路由、`R<T>` 包装、DB schema 对外契约、前端交互不得变。

## Acceptance Criteria

- [ ] **AC-探针**：C0 产出 axonhub 对 `json_schema`/tool calling/reasoning 的实测报告；
      C2/C3 实现深度据此确定。（R1,R3,R7）
- [ ] **AC-编译测试门**：Boot 4 + Spring AI 2.0 下 `mvn -q -DskipTests compile` 与 `mvn test`
      全绿（现 510 用例）。（R1）
- [ ] **AC-调用收敛**：grep 确认生产代码无直连 `/v1/chat/completions` 的手写调用，
      全部经 `ChatClient`。（R2）
- [ ] **AC-结构化自纠错**：构造"缺字段/类型错/多余字段"响应，断言模型收到**具体校验错误**后
      重试成功；简报字段完整率较改造前可复现提升。（R3）
- [ ] **AC-Prompt 资产化**：`grep` 生产代码内无内联长 prompt 文本块；prompt 均来自
      `resources/prompts/**` 且模板含版本标识。（R4）
- [ ] **AC-任务级参数**：至少结构化类与正文类使用不同 temperature/模型，可配置且可测。（R5）
- [ ] **AC-观测**：AI 调用产生 token/时延指标（Micrometer 或等价）。（R6）
- [ ] **AC-agent/QA**：`SubAgentRunnerTest`、`QaServiceTest` 在新实现下等价通过；
      工具可用性契约（`available()`/`toolHealth`）与降级路径不回归。（R7）
- [ ] **AC-向量迁移**：迁移前后**同 query 集对拍**（命中集/分数/排序）达标；
      跨 `embedding_model` 的向量不混空间（硬验收）；`mvn test` 绿。（R8）
- [ ] **AC-元话语**：`fact_risks[].suggestion` 不再进入正文素材上下文；真实正文抽检泄漏率下降；
      `verifyNumbers` 对 `1200` vs `12000` 用例不再漏报。（R9）
- [ ] **AC-契约等价**：既有 API 契约与前端交互不变；`npm run build` 通过；端到端冒烟
      （主题→简报→多版本正文→深度→编辑→预览）无回归。（R11,D4）
- [ ] **AC-回退**：每个子任务提交可独立 `git revert` 回退；父任务集成前遗留旧路径未删。（R10）

## Out of Scope

- wenyan 预览/发布链路（`WenyanServerService`/`PublishService`）。
- 状态机 `ProjectStatusService`、业务 RAG 打分/锚点加权/跨源合并算法（框架不替代）。
- 前端改动（契约保持不变）。
- Prompt DB 化管理 / A-B 实验平台（D5 明确不做）。
