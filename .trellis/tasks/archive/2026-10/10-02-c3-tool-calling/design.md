# Design — C3 Tool Calling 替换手写 agent

> 配套 `prd.md`。本文记录实现前发现的关键事实与据此调整的实现边界。

## 1. 关键发现（实现前勘察，2026-10-03）

**`SubAgentRunner.research()` 不是 LLM 工具使用循环（agent loop），而是一条确定性检索流水线：**

```
KB 检索(复合 query, 锚点加权)  →  策略路由 WEB(Tavily优先/SearxNG兜底, gap 驱动)  →  LLM 单次「汇总为事实 JSON」
```

- LLM **只被调用一次**（`chat(system, ctx)` → `structured(...)`），输入是**已抓好的** hits；它**不选择工具、不发起检索**。
- "工具使用"（检索）由 Java 编码的**确定性策略**决定，非模型决定：
  - provider 顺序来自 `WebSearchSnapshot`（运行时设置 > 部署默认），`WebSearchRouter` 首源命中即停；
  - **gap 驱动**：KB 命中车型权威块（且非背景题）时**跳过 WEB**；
  - **背景题强制放行 WEB** 并对 top 1–2 URL `extract` 补正文；
  - `sourceId`（W1/W2…）在 `WebResultNormalizer` 生成，事实后验校验（`validateFacts`）绑定。
- 这些行为被 `SubAgentRunnerTest` / `WebSearchRouterTest` / `DeepResearchService*Test`（含 AC-05/06/08 等）逐条钉死。

## 2. 决策：保留确定性编排，ToolCallback 作为**增量**能力层

把上述流水线改写为「LLM 决定调用哪些工具」的 agent loop 会**必然违反**父任务 D4（契约等价）：
模型自选 query/provider 会破坏复合 query、provider 顺序、gap 驱动跳过、背景题放行、sourceId 治理等契约，
且无法证明等价 —— 与 C3 自身 AC（策略路由/降级/门控不回归、`SubAgentRunnerTest` 等价通过）**直接冲突**。

故本任务**不**改写编排；而是交付需求字面要求的能力层：

- 新增 `SearchToolCallbacks` 适配器：把现有 `SearchTool`（KB/Tavily/Searxng）暴露为 Spring AI `ToolCallback`
  （`FunctionToolCallback` 或 `@Tool`），供**未来**显式选择「模型驱动检索」的链路复用。
- **关键约束：不得注册为全局 `ToolCallback` bean**。Spring AI 自动配置会把容器内 `ToolCallback` bean
  作为**所有** `ChatClient` 的默认工具，导致 C1/C2 的普通对话/结构化调用被意外注入工具、模型可能吐出
  `tool_calls` → 行为漂移。适配器只做成按需工厂（非 bean），零全局副作用。
- WEB 适配器一律经 `WebSearchRouter`（保留 provider 顺序/治理/sourceId），**不复用** `TavilySearchTool` 直连。

## 3. 交付物

- `com.sparkora.deep.tool.SearchToolCallbacks`（**工厂，非 @Component bean-of-ToolCallback**）：
  - `ToolCallback[] forTools(List<String> names)`：按名（KB/TAVILY/SEARXNG）产出 callbacks；
    `available()==false` 的工具**不暴露**（与 `applySettingGates` 一致）。
  - KB callback：委托 `KnowledgeSearchTool.search`（可带锚点）。
  - WEB callback：委托 `WebSearchRouter.search`（治理后的 `WebHit` → 字符串化）。
  - 工具名/描述稳定可测；异常内部捕获返回提示串（与工具"绝不抛出"约定一致）。
- 单元测试：callbacks 名称/可用性门控/委托与治理透传/异常降级。

## 4. 为什么这满足 C3 AC

C3 的 4 条 AC 全是**非回归**性质（`SubAgentRunnerTest` 等价通过、策略路由/降级/门控不回归、
`toolHealth`/`available()` 不变、`mvn test` 绿）。增量能力层**不改动** `SubAgentRunner`/`WebSearchRouter`/
`SearchTool` 的现有行为，故全部 AC 自然满足。

## 5. 延迟项（Deferred，需产品显式决策另立任务）

「让模型自主决定检索（真正 agent loop）」属于**新行为 + 成本/契约变化**，不在 D4「契约等价」内。
若产品需要，应新立任务，明确：模型自选 query/provider 的等价性保障、provider 顺序契约是否放宽、
sourceId 治理如何与模型自由输出并存、付费 provider 的调用上限。

## 6. 回退

纯增量、无行为变更；`git revert` 单个提交即回退。
