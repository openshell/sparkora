# Design — 引入 Spring AI 2.0 重构 AI 应用层

> 配套 `prd.md`（需求/验收）。本文只记技术设计：边界、契约、数据流、兼容/迁移、权衡、回退。

## 1. 已决前提（来自 prd 决策）

- 架构姿态 **B：直接采用 Spring AI**，领域服务直接注入框架类型，不建自研 Gateway 抽象。
- 版本 **Boot 4.0/4.1 + Spring AI 2.0**（一次到位）。
- 范围 **全面改造含 RAG 存储**（4 张 embedding 表 → PgVectorStore）。
- 行为基线 **契约等价 + 生成质量允许提升**。
- Prompt **文件化 + 模板引擎 + Git 版本管理**（不入库、无管理后台）。

## 2. 目标架构与边界

### 2.1 分层（直接依赖框架，仍保持"领域/平台"边界）

```
web.controller / domain.service（BriefService、VersionService、DeepWriterService、
        ClarifyService、SubAgentRunner、QaService、CarRagService…）
      │  直接注入 Spring AI 类型
      ▼
Spring AI 2.0：ChatClient · ChatModel · EmbeddingModel · ImageModel
              Advisor（自纠错/日志/指标/记忆）· VectorStore · ChatMemory · ToolCallback
      │
      ▼
axonhub（OpenAI 兼容聚合代理，单 key，按 model 名路由）
```

**仍保留在 `com.sparkora.ai` 的"共享设施"（非替换抽象，只是配置与资产）**：

- `AiProperties` → 保留并扩展为**任务级参数预设**载体（见 §3.2）。
- `resources/prompts/*.st` → 提示词资产 + 加载/渲染（见 §3.3）。
- 任务级 `ChatOptions` 工厂（temperature/模型/maxTokens 按任务）。
- 可观测性 Advisor（Micrometer/日志）。

> 注意：决策 B 不做可替换抽象；上述设施是**共享配置与资产**，领域层可直接依赖 Spring AI 类型。代价见 §7。

### 2.2 组件映射（现状 → 目标）

| 现状 | 目标 Spring AI 组件 | 子任务 |
|---|---|---|
| `AiClient.chat/chatJson/chatMessages` | `ChatClient` + `ChatModel`（OpenAI 兼容） | C1 |
| `AiProperties.temperature=0.7` 全局 | 任务级 `ChatOptions`（每链路不同） | C1 |
| Java 文本块内联 prompt | `resources/prompts/*.st` + `PromptTemplate`/`SystemPromptTemplate` | C1 |
| 手写重试：盲目提额 8192→16384 | 结构化：`entity(…, spec.validateSchema())`（错误回填自纠错，默认3次）+ 截断专用提额 | C2 |
| `sanitizeAiJson` + 裸控制字符转义 | 保留为**兜底**（provider 非原生时），主路径交给 schema 校验 | C2 |
| `BriefDto` 无校验 | DTO + jakarta validation；schema 由类型生成（单一来源） | C2 |
| `SubAgentRunner` 手写 agent 循环 | `ToolCallback`/Tool Calling + 自研循环瘦身/移除 | C3 |
| `QaService` 手拼 messages | `ChatMemory` + `MessageChatMemoryAdvisor` | C4 |
| 4 张 embedding 表 + 统一检索 SQL | `PgVectorStore`（自定义表名/维度1024/HNSW/COSINE）+ ETL | C5 |
| `EmbeddingClient`（/v1/embeddings） | `EmbeddingModel`（OpenAI 兼容） | C5 |
| `AiImageClient`（/v1/images/generations） | `ImageModel`（OpenAI 兼容） | C6 |
| `AiImageClient`（/v1/images/edits 多参考图） | **保留自研**（Spring AI 图模型未必覆盖 edits） | C6 |
| 无观测 | Spring AI Observability（Micrometer）+ Advisor 日志 | C1 |

## 3. 关键契约与数据流

### 3.1 结构化输出契约（C2，核心收益）

Spring AI 2.0 提供两层，组合使用：

```java
// 请求侧原生约束（provider 支持时）+ 响应侧校验自纠错（始终生效）
BriefDto dto = chatClient.prompt()
    .system(systemTemplate.render(vars))
    .user(userTemplate.render(vars))
    .options(taskOptions(TaskType.BRIEF_STRUCTURED))
    .call()
    .entity(BriefDto.class, spec -> spec
        .useProviderStructuredOutput()   // axonhub 不透传时自动退化
        .validateSchema());              // 失败把具体错误回填 prompt，默认重试 3 次
```

- **schema 单一来源 = DTO 类型**：`validateSchema()` 从 `BriefDto.class` 派生 JSON Schema（DRAFT_2020_12），
  取代现有"提示词里内联 schema 字面量 + DTO 类"两处硬编码。
- **自纠错 vs 截断**：`validateSchema` 治"字段缺失/类型错/多余字段"；**截断**（`finish_reason=length`）
  仍须独立处理（提额重试），因为截断不是 schema 违规。两者不得混为一谈。
- **`useProviderStructuredOutput()` 依赖探针结论**（C0）：axonhub 若不透传 `json_schema`，该调用
  自动退化，仍由 `validateSchema()` 兜底 → 设计对两种结果都成立。
- **`sanitizeAiJson` 去留**：C2 后，若探针证明 provider 稳定，删除控制字符转义路径；否则降级为
  解析前的最后兜底。删除前须有回归用例覆盖"含裸控制字符/围栏"的响应。

### 3.2 任务级参数（C1）

```java
enum TaskType {
    CLARIFY_PLAN,      // 结构化抽取，低温
    BRIEF_STRUCTURED,  // 结构化，低温
    FACT_EXTRACT,      // 子代理结构化，低温
    ARTICLE_WRITE,     // 正文创意，高温
    QA_CHAT,           // 问答，中温
    IMAGE_PROMPT
}
```

- 每 `TaskType` → `ChatOptions`（model/temperature/maxTokens），值走 `AiProperties` 扩展 + `.env` 覆盖。
- 默认建议：结构化类 temperature 0~0.2，正文类 0.7，QA 0.3~0.5（具体值 C1 实测标定）。
- **契约不变**：对上层仍是"传 system+user+额度"语义，只是额度/温度按任务选。

### 3.3 提示词资产化（C1）

- 目录：`src/main/resources/prompts/<domain>/<name>.st`，首行注释 `# version: vN`。
- 渲染：Spring AI `PromptTemplate`（`{var}` 占位）/ `SystemPromptTemplate`。
- 动态块（fact_sheet、RAG context、brief 字段、forbiddenClaims）继续由 Java 组装后作为变量传入，
  但**模板与可变数据分离**（模板文件不变、数据注入）→ 满足"指令/素材分层"的结构基础（配合 C7）。
- 无 DB、无后台；版本差异走 Git diff。

### 3.4 向量存储迁移（C5，最高数据风险）

- `PgVectorStore` 支持 `vectorTableName`/`dimensions`/`indexType`/`distanceType`/`schemaName`，
  可按域建 4 个 store（car/kb/news/image），维度 1024、HNSW、COSINE。
- **保留自研的部分**：锚点加权（`ragAnchorBoost`）、按域独立配额（`ragKbTopk`/`ragNewsTopk`）、
  候选窗口隔离、置信度/跨源合并（`FactSheetService`/`ClaimSimilarity`）。`VectorStore` 只替换
  "存储 + 相似度检索"这一层，不替换**业务打分与合并**。
- **`embedding_model` 防护不回归**：现有 `V3` 的"写入盖名 + 检索过滤"必须在 `PgVectorStore` 的
  `metadata` 中承载（`embedding_model` 作为 metadata 字段 + filterExpression），否则同维换模型静默混空间。
  这是 C5 的硬验收项。
- **迁移策略**：新增 Flyway 迁移建 `springora_*` 的 pgvector store 表（或复用现有表结构映射）；
  **回填用后台 runner**（复用现有 `rebuildAll/rebuildMissing` 范式，异步 + 异常全吞）。
  旧列/表在验证达标后由后续迁移删除（分两次：先双写/回填，再切换读取，最后删旧）。

### 3.5 元话语泄漏根因治理（C7）

- 现状：`ReaderViewRules` 禁词 + `MetaLeakCleaner` 句级正则（事后堵漏）；根因是
  **`fact_risks[].suggestion`（写给作者）与素材同上下文**。
- 目标：在数据层分离"给作者的指令"与"给读者的素材"——正文 prompt 只注入素材（claim/value），
  suggestion/铁律/黑名单走 system 的"给作者"区并明确标记，或干脆不进 user。
- `MetaLeakCleaner` 保留为最后防线，但不再作为主机制。
- `verifyNumbers`：由子串 `contains` 改为**数值归一化比对**（千分位/单位/数量级/小数），消除
  `"1200"` 命中 `"12000"` 的漏报；复用 `ClaimSimilarity.numberValues` 的数值签名先例。

## 4. 兼容 / 依赖预检（C0，最高风险前置）

Boot 3.3.4 → 4.x 的破坏性面 + Spring AI 2.0 的 starter 命名变更（`spring-ai-starter-*`）：

| 依赖 | 风险 | 预检动作 |
|---|---|---|
| MyBatis-Plus 3.5.7 | Boot 4 兼容？starter 名 `mybatis-plus-spring-boot3-starter` | 升 Boot 后编译+跑测试；必要时升 MP 版本 |
| Flyway 10.10.0 | Boot 4 BOM 抬升版本；PG17 告警 | 保持走 BOM，确认 checksum/迁移正常 |
| JJWT 0.12.6 | 与 Boot 无关，应无影响 | 编译验证 |
| `RestClient` 自研调用残留 | Boot 4 无破坏 | 编译验证 |
| `spring-boot-starter-validation` | jakarta 版本随 Boot | 编译验证 |
| Spring AI starter | 模块名在 2.0 有重命名 | 按 2.0 文档选 `spring-ai-starter-model-openai` 等 |
| OpenAI 兼容 base-url | 指向 axonhub | `spring.ai.openai.base-url` + `api-key` 配 `.env` |

**Gate**：C0 结束时 `mvn test` 全绿（现 510 用例），否则不得进入后续子任务。

## 5. 子任务映射与依赖（父任务拥有，非树位置依赖）

父任务 `10-02-spring-ai-adoption` 拥有源需求、子任务映射、跨子验收、最终集成复核。子任务各自
`prd.md`/`implement.md` 写明依赖。

| 子任务 | 交付物 | 依赖 |
|---|---|---|
| **C0** 依赖升级预检 + axonhub 探针 | Boot4+SpringAI 编译测试全绿；探针报告 | 无（前置门） |
| **C1** ChatClient 收敛 + Prompt 资产化 + 任务级参数 + 观测 | 文本调用全走 ChatClient | C0 |
| **C2** 结构化输出契约化（schema 单一来源 + 自纠错） | Clarify/Brief/SubAgent 自纠错 | C0,C1 |
| **C3** Tool Calling 替换手写 agent | DeepResearch 工具化 | C0,C1 |
| **C4** ChatMemory 替换手拼 messages | QA 多轮框架化 | C0,C1 |
| **C5** 向量存储迁移 PgVectorStore | 4 域检索迁移 + 质量对拍 | C0 |
| **C6** ImageModel（保留 edits 自研） | 生图契约不变 | C0 |
| **C7** 元话语根因治理 + verifyNumbers 归一 | 泄漏/数值回归修复 | C1,C2 |

C2/C3/C4/C5/C6 相互独立可并行；C7 依赖 C1/C2 的 prompt 分层基础。

## 6. 迁移批次（可回退）

1. **C0 门**：升级 + 探针。失败即停，回退 = git revert 升级提交。
2. **C1 基座**：先只做文本调用收敛（不含结构化语义变化），保持输出行为等价 → 可单独回退。
3. **C2/C3/C4/C5/C6**：逐链路切换，每条链路独立可回退（旧路径保留至新路径验收达标）。
4. **C7 治理**：在 prompt 分层就绪后移除正则主依赖。
5. 父任务集成：跨链路回归 + 删除遗留 `AiClient` 旧路径。

## 7. 权衡与回退

- **决策 B 的代价**：领域代码与 Spring AI 类型强耦合；Spring AI 升级/更换波及面大；无自研
  替换点。接受此代价换取最少样板。**缓解**：把 prompt 资产、任务参数、观测集中到 `com.sparkora.ai`
  共享设施，缩小"框架类型扩散"到调用点，而非把框架类型写进业务规则。
- **Boot 4 一次到位的代价**：两次大版本跳跃叠加，C0 是硬门；若预检发现某依赖无 Boot 4 兼容版，
  回退方案 = 降到 Boot 3.5 + Spring AI 1.1（需重新评估，属 plan 回滚）。
- **RAG 迁移的代价**：改写已验证的检索质量。缓解 = 迁移前后**同 query 集对拍**（命中集/分数/排序），
  达标才切读路径；`embedding_model` 防护列为硬验收。
- **provider 退化**：axonhub 不透传原生结构化/tool calling 时，能力退化为 prompt-based（`validateSchema`
  自纠错仍有效，工具调用需自研适配）。C0 探针决定 C2/C3 的实现深度。

## 8. 运维 / 回退要点

- **Flyway**：不得改已应用脚本；新结构走新 `V<n>__`；旧列删除放最后一批迁移。
- **回填**：向量/派生数据写入用 `REQUIRES_NEW` 事务隔离（现有先例），异常全吞不阻断启动。
- **启动补齐**：多 runner 用 `@Order` 固定依赖序（现有先例）。
- **密钥**：`axonhub` api-key、`QINIU_*` 等一律 `.env`，不进代码/日志。
- **失败回滚**：每条链路保留旧实现直到新实现验收；`git revert` 单个子任务提交即可回退。
