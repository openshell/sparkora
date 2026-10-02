# C2 结构化输出契约化（schema 单一来源 + 自纠错）

## Goal

用 Spring AI `entity(X.class, spec -> spec.validateSchema())` 的 schema 校验 + **错误回填自纠错**
替代现有"盲目提额重试"；schema 由 DTO 类型单一派生，消除提示词字面量与 DTO 两处硬编码。

## Depends On

- **C0**（探针定 provider 原生结构化是否可用）、**C1**（ChatClient 基座）。

## Requirements

- 父 R3。
- `ClarifyService`/`BriefService`/`SubAgentRunner` 改 `entity(...)`；按探针结论决定是否加
  `useProviderStructuredOutput()`。
- DTO 加 jakarta validation；**删除提示词内联 schema 字面量**，schema 由类型派生。
- **截断独立处理**：`finish_reason=length` 仍走提额重试，不得与 schema 违规混淆。
- `sanitizeAiJson`：按探针结论降级为兜底或删除；删除前须有"裸控制字符/围栏"回归用例。

## Acceptance Criteria

- [ ] 构造缺字段/类型错/多余字段响应，断言模型收到**具体校验错误**后重试成功（AC-结构化自纠错）。
- [ ] 简报字段完整率较改造前可复现提升。
- [ ] 截断路径仍能提额重试成功（独立于 schema 校验）。
- [ ] `mvn test` 绿；prompt 中无内联 schema 字面量。

## Out of Scope

- Tool Calling（C3）、向量迁移（C5）、元话语治理（C7）。
