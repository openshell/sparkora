# C4 ChatMemory 替换手拼 messages

## Goal

把 `QaService` 手工拼装 message 列表改为 Spring AI `ChatMemory` + `MessageChatMemoryAdvisor`。

## Depends On

- **C0**、**C1**。

## Requirements

- 父 R7。
- `QaService` 用 `ChatMemory` 管理多轮历史；`buildSystemPrompt` 保留。
- **契约不变**：`QaService` 构造器**不得注入 `SettingService`**（C4 先例，浏览/问答不受 `kb_enabled` 控制）；
  不得注入知识上下文开关到问答链路。
- 空 content / 无 choices 的错误语义不变。

## Acceptance Criteria

- [ ] `QaServiceTest` 多轮顺序/角色等价通过（AC-agent/QA）。
- [ ] 断言 `QaService` 未依赖 `SettingService`（防契约回归）。
- [ ] AI 返回空 content 抛 `AiException` 行为不变。
- [ ] `mvn test` 绿。

## Out of Scope

- 问答答案配图（`QaImageRefService`）逻辑不变。
