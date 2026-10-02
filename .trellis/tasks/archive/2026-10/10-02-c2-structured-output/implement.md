# Implement — C2 结构化输出契约化

> 依据父 `design.md` §3.1 与 C0 探针结论（axonhub `json_schema` strict 退化 → 正确性必须靠响应侧 `validateSchema()`）。

## 0. 已核实事实（主会话 javap 复验，直接用）

- Spring AI 2.0.1 `ChatClient` 支持：
  - `CallResponseSpec.entity(Class<T>, Consumer<EntityParamSpec>)` / `responseEntity(Class<T>, Consumer<EntityParamSpec>)`。
  - `EntityParamSpec.validateSchema()`（开启响应侧 schema 校验 + **把具体校验错误回填 prompt 的自纠错重试**）/ `useProviderStructuredOutput()`（axonhub 退化时自动无效）。
  - `responseEntity` 保留 `ChatResponse`（model/usage/finishReason/reasoning）→ 可继续满足既有 `ChatResult` 契约。
- `StructuredOutputValidationAdvisor.builder()` 可配 `outputType`/`outputJsonSchema`/`maxRepeatAttempts`/`jsonMapper`。
- schema 单一来源可用 `new BeanOutputConverter<>(Dto.class).getFormat()`（供 prompt 文本）与 `JsonSchemaGenerator.generateForType(Type)`。
- `BeanOutputConverter` 内部用 `tools.jackson`（Jackson 3），仅取 `getFormat()` 文本时无需外部 mapper。

## 1. 改动范围（严格限定 PRD 三站点 + 单来源）

- 迁移：`BriefService`（BriefDto，唯一有类型 DTO）、`ClarifyService`（现 JsonNode）、`SubAgentRunner`（现 JSON 字符串）。
- 为 Clarify / SubAgent 新建类型 DTO，使 schema 可从类型派生；其余 7 个 `chatJson` 站点（Style/Imitation/Version/AiParamCleaner/CarModelMatcher）**本次不改**（无 DTO，属后续增量；不阻塞 C2 验收）。

## 2. 实施清单

- [ ] **AiClient 增结构化方法**（新增，不破坏旧 API）：
  - `record TypedResult<T>(T entity, ChatResult chat)` 或等价（entity + model/tokens/finishReason/reasoning）。
  - `<T> TypedResult<T> structured(String system, String user, int maxTokens, Class<T> type)`：
    用 `ChatClient.prompt().system().user().options(TaskType.STRUCTURED_EXTRACT)`
    `.call().responseEntity(type, spec -> spec.validateSchema())`；
    对返回的 `ChatResponse` 复用 `parseChat`（空/无 result→AiException；`finish_reason=length`→截断 AiException；
    reasoning 读取与截断）。
  - 截断与 schema 违规**分离**：截断由 `parseChat` 抛专用异常 → 服务层提额重试；schema 违规由 `validateSchema` 在调用内自纠错。
- [ ] **BriefDto**：加 jakarta validation（`@Size`/`@NotEmpty` 等，宽松以不误杀），确认 `BeanOutputConverter` 能生成 schema。
- [ ] **新建 DTO**：`ClarifyPlanDto`（keyQuestions/dataNeeds/hypotheses/toolHints/questions）、`SubAgentFactsDto`（facts/gaps）；字段与现 prompt schema 一一对应。
- [ ] **prompt 去内联 schema**：`brief/deep-brief-system.st`、`clarify/plan-system.st`、`deep/subagent-system.st` 的 JSON schema 字面量改为 `{{schema}}` 占位；由 `BeanOutputConverter.getFormat()`（或 JsonSchemaGenerator）渲染注入。保留其余自然语言规则（keyQuestions 规则等）。
- [ ] **三服务改结构化调用**：BriefService / ClarifyService / SubAgentRunner 用新 `structured(...)`；保留外层「截断→提额一倍重试一次」范式（与 schema 自纠错叠加）。
- [ ] `sanitizeAiJson` 保留为兜底（其余站点仍用）；不在迁移站点主路径。

## 3. 验证命令

```bash
mvn -q -DskipTests compile
mvn test                      # 目标全绿（现 595）
grep -rn "chatJson" src/main/java/com/sparkora/service/BriefService.java \
     src/main/java/com/sparkora/deep/service/ClarifyService.java \
     src/main/java/com/sparkora/deep/service/SubAgentRunner.java   # 三站点应改走 structured
grep -rn '"keyQuestions"\|"facts"\|"titleCandidates"' src/main/resources/prompts/{brief,clarify,deep}/*.st  # schema 字面量应消失
```

## 4. 测试要求

- **AC-结构化自纠错**：单测构造「缺字段/类型错/多余字段」响应，断言经 `validateSchema` 后模型收到具体校验错误并重试成功。
- **截断独立**：`finish_reason=length` 仍触发提额重试（与 schema 校验解耦）。
- 既有 `BriefServiceTest`/`ClarifyServiceTest`/`SubAgentRunnerTest` 行为等价（字段、降级、净调用次数不回归）。
- `BriefDto` schema 派生稳定（新增 schema 快照/存在性断言）。

## 5. 风险 / 回退

- 风险：`validateSchema` 内部重试在截断场景会重复同额度调用 → 用 `maxRepeatAttempts` 收敛（默认 1~2）；外层截断重试兜底。
- 风险：`responseEntity` 经自纠错后 usage/model 是否保留 → 实测确认；不保留则从最后一次 ChatResponse 取。
- 回退：R2 = 本任务提交，`git revert` 即回到 C1 的 `chatJson` 直连状态。
