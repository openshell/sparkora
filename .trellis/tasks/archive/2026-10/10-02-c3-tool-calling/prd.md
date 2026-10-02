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

## Technical Notes（实现前勘察，详见 design.md）

- `SubAgentRunner.research()` **不是 LLM agent loop**，而是确定性流水线：KB → 策略路由 WEB → LLM 单次汇总。
  检索由 Java 策略决定（provider 顺序 / gap 驱动跳过 / 背景题放行 / sourceId 治理），被既有测试钉死。
- 故**不改写编排**（改写必然违反 D4 契约等价与 AC 非回归）；改为**增量**交付 ToolCallback 能力层：
  `SearchToolCallbacks` 工厂把 `SearchTool` 暴露为 `ToolCallback`，供未来显式需要模型驱动检索的链路复用。
- **不得注册为全局 `ToolCallback` bean**（会被 Spring AI 自动配置注入到所有 ChatClient，导致普通对话行为漂移）。
- 「模型自主决定检索」属新行为，延迟另立任务（见 design.md §5）。

## Out of Scope

- WEB 搜索 provider 策略本身、FactSheet 归并算法。
- 把现有 `SubAgentRunner` 流水线改写为模型驱动的 agent loop（延迟，另立任务）。
