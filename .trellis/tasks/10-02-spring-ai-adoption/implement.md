# Implement — 引入 Spring AI 2.0 重构 AI 应用层

> 执行计划（父任务视角）。子任务各自另建 `implement.md`。验证命令、审查门、回退点如下。

## 0. 前置与总门

- 分支：`main` 基础上开工作分支（`task.py set-branch`）。
- **G0 依赖预检门（C0）**：`mvn -q -DskipTests compile` + `mvn test` 在 Boot 4 + Spring AI 2.0 下全绿
  （现 510 用例）。任一红灯 => 停，回到规划调整版本（可能降 Boot 3.5 + Spring AI 1.1）。
- **G1 探针门（C0）**：axonhub 对 `json_schema` strict / tool calling / reasoning 的实测结论落档；
  C2/C3 实现深度按此定。

## 1. C0 — 依赖升级预检 + axonhub 探针（前置，阻塞其余全部）

### 1.1 依赖升级
- [ ] pom.xml：`spring-boot-starter-parent` → 4.x；加 `spring-ai-bom` 2.0 + `spring-ai-starter-model-openai`
      （名称以 2.0 文档为准）；`spring-ai-starter-vector-store-pgvector`。
- [ ] MyBatis-Plus 换 Boot 4 对应 starter/版本；Flyway/JJWT/validation 随 BOM 校验。
- [ ] `application.yml`：`spring.ai.openai.base-url=${AI_BASE_URL:https://axo.caiqz.cn}`、
      `api-key=${AI_API_KEY}`、`chat.options.model=${AI_MODEL}`、`embedding.options.model=${AI_EMBEDDING_MODEL}`。
- [ ] `.env.example` 增补 `SPRING_AI_*` 等价项；**不改已有 `.env`**。

### 1.2 探针
- [ ] 写一次性探针（测试或临时 runner），验证：`json_schema` strict、`tool calling`、
      `reasoning` 字段透传；输出探针报告到 `research/axonhub-capability-probe.md`。

### 1.3 验证 / 回退
- 验证：`mvn -q -DskipTests compile`、`mvn test`、`./dev.sh start`（探活 `/api/auth/me`）。
- 回退点：R0 = 升级提交，`git revert` 即回到 3.3.4。

## 2. C1 — ChatClient 收敛 + Prompt 资产化 + 任务级参数 + 观测

- [ ] 建 `com.sparkora.ai` 共享设施：`TaskType` 枚举、`ChatOptions` 工厂、
      `PromptTemplateLoader`（读 `resources/prompts/**`）。
- [ ] 把各 `buildXxxPrompt()` 文本块迁到 `.st` 模板（Brief/Version/Deep/Clarify/QA/Imitation/AiParamCleaner）。
- [ ] 各链路调用点改用 `ChatClient`；**本步不引入结构化语义变化**（输出行为等价）。
- [ ] 加日志/指标 Advisor。
- 验证：`mvn test`；联调逐链路冒烟（简报/正文/深度/问答/仿写）。
- 回退点：R1 = C1 提交；旧 `AiClient` 路径暂不删，便于对照。

## 3. C2 — 结构化输出契约化（最高收益）

- [ ] `ClarifyService`/`BriefService`/`SubAgentRunner` 改 `entity(X.class, spec -> spec
      .useProviderStructuredOutput().validateSchema())`。
- [ ] DTO 加 jakarta validation；schema 由类型派生（删提示词内联 schema 字面量）。
- [ ] 截断处理独立保留（提额重试）；`sanitizeAiJson` 按探针结论降级/删除。
- 验证：`mvn test`；构造"缺字段/类型错/多余字段"用例断言自纠错重试；对拍简报字段完整率。
- 回退点：R2 = C2 提交。

## 4. C3 — Tool Calling 替换手写 agent

- [ ] 把 `SearchTool`/KB 工具包装为 `ToolCallback`（保留 `available()`/`toolHealth` 契约，
      见 ai-rag-guidelines.md「搜索工具可用性契约」）。
- [ ] `SubAgentRunner` 循环瘦身/改由 Tool Calling 驱动；`rawFallback` 降级路径保留。
- 验证：`SubAgentRunnerTest` 等价；搜索策略路由/降级用例不回归。
- 回退点：R3 = C3 提交。

## 5. C4 — ChatMemory 替换手拼 messages

- [ ] `QaService` 用 `ChatMemory` + `MessageChatMemoryAdvisor`；**不得注入 `SettingService`**（现有契约）。
- 验证：`QaServiceTest` 多轮顺序/角色；空 content 抛错行为不变。
- 回退点：R4 = C4 提交。

## 6. C5 — 向量存储迁移 PgVectorStore（数据风险最高）

- [ ] 每域建 store（car/kb/news/image），维度 1024、HNSW、COSINE、自定义表名。
- [ ] `embedding_model` 作为 metadata 承载 + 检索 filter（**硬验收，防同维混空间**）。
- [ ] ETL/回填 runner（异步、`REQUIRES_NEW`、异常全吞、`@Order` 排序）。
- [ ] 保留 RAG 打分/配额/锚点/合并；`VectorStore` 只替换存储+检索。
- 验证：迁移前后**同 query 集对拍**（命中集/分数/排序）；`mvn test`；`sparkora-*-embedding` 数据对账。
- 回退点：R5 = 切读路径提交；旧表/列在达标后的后续迁移再删（先不删）。

## 7. C6 — ImageModel

- [ ] 文生图接 `ImageModel`；**图生图 `/v1/images/edits` 保留自研 `AiImageClient`**（Spring AI 未必覆盖）。
- 验证：`ImageService` 单测；多参考图 1~4 上限与顺序契约不变。
- 回退点：R6 = C6 提交。

## 8. C7 — 元话语根因治理 + verifyNumbers 归一

- [ ] prompt 数据层分离"给作者指令"与"给读者素材"；`MetaLeakCleaner` 降为最后防线。
- [ ] `verifyNumbers` 改数值归一化比对（复用 `ClaimSimilarity.numberValues` 先例）。
- 验证：`DeepWriterServicePromptTest`、`MetaLeakCleanerTest`、数值回查用例；真实正文抽检泄漏率。
- 回退点：R7 = C7 提交。

## 9. 父任务集成（全部子任务达标后）

- [ ] 跨链路端到端回归：主题→简报→多版本正文→深度→编辑→预览（发布链路只做不破坏验证）。
- [ ] 删除遗留 `AiClient` 旧路径与 `sanitizeAiJson`（若探针允许）。
- [ ] 更新 `docs/spec/**` 与 `.trellis/spec/backend/ai-rag-guidelines.md`（AI 调用契约已变）。
- 验证：`mvn test` 全绿 + `npm run build`（前端契约不变）+ 一键联调冒烟。

## 10. 全局验证命令

```bash
mvn -q -DskipTests compile      # 编译
mvn test                        # 全量用例（现 510）
./dev.sh restart backend        # 联调重启
./dev.sh logs backend -f        # 跟随日志
npm run build                   # 前端产线构建（frontend/）
```

## 11. 风险清单 / 回退点汇总

| 点 | 风险 | 回退 |
|---|---|---|
| R0 | Boot4+SpringAI 依赖不兼容 | revert 升级，降 Boot 3.5+SA1.1 |
| R1 | ChatClient 收敛改变输出 | revert C1，旧 AiClient 保留 |
| R2 | 结构化契约影响输出 | revert C2 |
| R3 | Tool Calling 行为变化 | revert C3 |
| R4 | QA 记忆语义变化 | revert C4 |
| R5 | 向量迁移质量回退 | 切回旧检索（表未删） |
| R6 | 生图契约变化 | revert C6 |
| R7 | 治理引入新泄漏 | revert C7 |

## 12. task start 前检查

- [ ] `prd.md`/`design.md`/`implement.md` 完成且内部一致。
- [ ] `implement.jsonl`/`check.jsonl` 含真实 spec/research 条目（非 seed）。
- [ ] 子任务已建（C0–C7）并各自链接父任务。
- [ ] 用户对最终规划摘要给出**新的**明确批准。
