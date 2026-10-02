# C1 ChatClient 收敛 + Prompt 资产化 + 任务级参数 + 观测

## Goal

把全部 AI 文本调用从手写 `RestClient` 收敛到 Spring AI `ChatClient`；把 Java 内联 prompt
迁到 `resources/prompts/*.st` 模板；引入任务级 `ChatOptions`；加基础观测。
本步**只换调用形态，不引入结构化语义变化**，保持输出行为等价。

## Depends On

- **C0**（Boot4 + Spring AI 2.0 编译测试全绿）必须先完成。

## Requirements

- 父 R2/R4/R5/R6。
- 建 `com.sparkora.ai` 共享设施：`TaskType` 枚举、任务级 `ChatOptions` 工厂、
  `PromptTemplateLoader`（读 `resources/prompts/**`）。
- 各 `buildXxxPrompt()` 迁到 `.st`（Brief/Version/Deep/Clarify/QA/Imitation/AiParamCleaner）；
  首行含 `# version: vN`。
- 调用点改用 `ChatClient`；**旧 `AiClient` 路径暂保留**以便对照回退。
- 加日志/指标 Advisor（token/时延/失败）。

## Acceptance Criteria

- [ ] grep 生产代码无直连 `/v1/chat/completions` 的手写调用（AC-调用收敛）。
- [ ] 生产代码无内联长 prompt 文本块；prompt 均来自 `resources/prompts/**`（AC-Prompt 资产化）。
- [ ] 结构化类与正文类使用不同 temperature/模型，可配置可测（AC-任务级参数）。
- [ ] AI 调用产生 token/时延指标（AC-观测）。
- [ ] `mvn test` 绿；简报/正文/深度/问答/仿写冒烟输出与改造前等价。

## Out of Scope

- Schema 校验/自纠错（C2）、Tool Calling（C3）、ChatMemory（C4）、向量迁移（C5）。
