# Implement — C3 Tool Calling 替换手写 agent

## 0. 前置
- 依赖 C0（tool calling 探针：**支持**，标准 `tool_calls`）、C1（ChatClient 基座）。
- 已核实 API（javap 2.0.1）：`org.springframework.ai.support.ToolCallbacks.from(Object...)`、
  `@org.springframework.ai.tool.annotation.Tool`、`ChatClient...toolCallbacks(ToolCallback...)`。

## 1. 交付（纯增量，不改现有编排）
- [ ] 新增 `com.sparkora.deep.tool.SearchToolCallbacks`（工厂类，**非 @Component**）：
  - `ToolCallback[] forTools(List<String> names)`：按 `KB`/`TAVILY`/`SEARXNG` 名产出；`available()==false` 的工具不暴露。
  - KB：委托 `KnowledgeSearchTool.search(query, maxResults[, anchors])`。
  - WEB：委托 `WebSearchRouter.search(query, maxResults, snapshot)`，`WebHit` 结果字符串化（保留 sourceId/url/provider）。
  - 异常内部捕获，返回降级提示串（不抛出，符合工具约定）。
- [ ] 不新增任何 `ToolCallback` 全局 bean / 不改 `SubAgentRunner.research()` / 不改 `WebSearchRouter` / `SearchTool`。
- [ ] 单测 `SearchToolCallbacksTest`：名称稳定、不可用工具不暴露、KB/WEB 委托与治理透传、异常降级。

## 2. 验证
```bash
mvn -q -DskipTests compile
mvn test                      # 目标：现有全绿（现 604）+ 新增用例
grep -rn "ToolCallback" src/main/java   # 仅出现在 SearchToolCallbacks（非 bean 扫描路径）
```

## 3. Review Gate（check 子代理）
- 逐条核对 C3 AC：`SubAgentRunnerTest` 等价、策略路由/降级/门控不回归、`toolHealth`/`available()` 不变、`mvn test` 绿。
- 核对**未**把 `ToolCallback` 注册为全局 bean（防普通对话被注入工具）。
- 核对未触碰 db/migration、frontend、web/controller、真实 `.env`。

## 4. 回退点
- 纯增量、无行为变更；`git revert` 单个提交即回退。
