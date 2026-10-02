# C3 Tool Calling 替换手写 agent

## Goal

把 `deep` 研究链路的手写 agent 循环（`SubAgentRunner`）改为 Spring AI Tool Calling 驱动；
工具以 `ToolCallback` 暴露，保留既有降级与工具健康契约。

## Depends On

- **C0**（探针定 tool calling 是否透传）、**C1**（ChatClient 基座）。

## Requirements

- 父 R7。
- `SearchTool`（Tavily/SearxNG）与 KB 工具包装为 `ToolCallback`。
- **契约不变**：`available()` 仅配置就绪（无失败闩锁）、`configured()`/`lastCallOk()`、
  `toolHealth` 三态/四态、`rawFallback` 降级路径（snippet/content 留证）均保留。
- 若探针显示 axonhub 不透传 tool calling，则实现自研适配（保持对外行为一致）。

## Acceptance Criteria

- [ ] `SubAgentRunnerTest` 在新实现下等价通过（AC-agent/QA）。
- [ ] 搜索策略路由（WEB provider 顺序）、降级、开关门控用例不回归。
- [ ] `toolHealth` 键值与优先级不变；`available()` 失败后仍为 true（防闩锁回归）。
- [ ] `mvn test` 绿。

## Out of Scope

- WEB 搜索 provider 策略本身、FactSheet 归并算法。
