# Logging Guidelines

> How logging is done in this project.

---

## Overview

<!--
Document your project's logging conventions here.

Questions to answer:
- What logging library do you use?
- What are the log levels and when to use each?
- What should be logged?
- What should NOT be logged (PII, secrets)?
-->

(To be filled by the team)

---

## Log Levels

<!-- When to use each level: debug, info, warn, error -->

(To be filled by the team)

---

## Structured Logging

<!-- Log format, required fields -->

(To be filled by the team)

---

## What to Log

<!-- Important events to log -->

(To be filled by the team)

---

## What NOT to Log

<!-- Sensitive data, PII, secrets -->

(To be filled by the team)

---

## Scenario: BYD Windows Local Agent 日志与推送历史

### 1. Scope / Trigger

- Trigger: 本机 Agent 领取服务端任务、执行 MMKV 推送、回传结果，并在 Windows 控制面板展示历史。
- 该路径横跨服务端任务契约、本机执行状态与 UI 展示；错误日志还可能接触本机路径、图片 URL、Token 和签名参数，因此必须有明确脱敏契约。

### 2. Signatures

- `log(level: str, message: object, *, stream=None, config: AgentConfig | None = None) -> None`
- `record_push_history(status, fields, *, config=None, draft_id=None, draft_count=None, duration_ms=None, error=None, server_reported=None) -> None`
- 状态值仅允许：`RECEIVED`、`RUNNING`、`SUCCESS`、`FAILED`。
- 文件位置：统一日志 `work/agent.log`；历史事件流 `work/push-history.jsonl`。

### 3. Contracts

- 每行日志格式：`YYYY-MM-DD HH:MM:SS [LEVEL] message`，使用本地时区并立即 flush。
- 每次领取任务先写 `RECEIVED`，真正进入 `execute_job` 前写 `RUNNING`，每次执行只写一条最终 `SUCCESS` 或 `FAILED`。
- 历史 JSON 固定字段：`at`、`atEpoch`、`status`、`jobId`、`projectId`、`versionId`、`title`、`draftId`、`draftCount`、`durationMs`、`error`、`serverReported`。
- `serverReported`：`null` 表示尚未到结果阶段；`true` 表示服务端已接受回传；`false` 表示本地已有终态但服务端回传失败。
- 本地成功但回传失败时，最终状态仍为 `SUCCESS`，同时 `serverReported=false` 且 `error` 说明回传失败；不得误记为本地执行失败。
- UI 按 `jobId` 归并，只展示每个任务最新状态；历史文件是追加式事实源。

### 4. Validation & Error Matrix

- JSONL 损坏行或非对象 -> 忽略，不中断读取其他历史。
- 缺少 `jobId` 或非法状态 -> 忽略该事件。
- `draftCount` / `durationMs` 非法 -> 写为 `null`，不得中断任务。
- 历史文件不可写 -> 输出一条安全 WARNING，任务继续执行。
- 服务端结果回传异常 -> 写一条最终事件并标记 `serverReported=false`，不得重复写终态。
- 日志/历史序列化前 -> 脱敏 Token、签名、Session、图片 URL 和 Windows/UNC 本机路径。
- 空任务轮询 -> 默认不写日志；预检仅在状态变化时写日志。

### 5. Good / Base / Bad Cases

- Good：`RECEIVED -> RUNNING -> SUCCESS(serverReported=true)`，控制面板显示一条成功历史。
- Base：本地成功但回传失败，展示 `SUCCESS`、`serverReported=false` 与安全错误说明。
- Bad：每 5 秒输出“暂无任务”，或同一任务写两条 `SUCCESS`，导致日志被淹没、事件语义含糊。

### 6. Tests Required

- 断言日志匹配本地时间戳与级别格式。
- 断言 `RECEIVED` 在 payload 解析前落盘，`RUNNING` 在 `execute_job` 前落盘。
- 断言成功/失败每次执行只有一个最终事件，且 `serverReported` 正确。
- 断言控制面板忽略损坏 JSONL、按 `jobId` 取最新、时间倒序且数量有界。
- 断言 Token、URL、Windows 路径、payload 正文不出现在日志或历史。
- 断言空任务轮询不重复输出，预检状态只记录变化。

### 7. Wrong vs Correct

#### Wrong

```python
record_push_history("SUCCESS", fields, server_reported=False)
report_result(...)
record_push_history("SUCCESS", fields, server_reported=True)
```

同一执行产生两条终态事件，UI 和审计难以判断真实结果。

#### Correct

```python
server_reported = True
try:
    report_result(...)
except Exception as exc:
    server_reported = False
    report_error = safe_error_text(exc, config)

record_push_history(
    "SUCCESS",
    fields,
    error=report_error,
    server_reported=server_reported,
)
```

本地终态只写一次；回传是否成功由 `serverReported` 表达。