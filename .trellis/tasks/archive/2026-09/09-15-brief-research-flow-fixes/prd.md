# 简报研究链路修复

## Goal

修复简报页「深度研究 / 生成」链路上已确认的缺陷，让进度展示准确、Tavily 配置真正生效且可用性状态可信：

1. 研究进度「分子」缺失（永远只显示分母）。
2. Tavily 配置后不被绑定 / 不可用 / 从不被调用。
3. 工具健康与设置门控展示不一致（KB 恒绿、失败误标「未配置」）。

用户价值：深度研究过程可观测、可信；已付费配置的 Tavily 在需要时真正能兜底；系统检索设置（KB / WEB 开关）如实反映在 UI。

## Background（09-15 勘察结论）

### B1 进度分子缺失（前端，确定 bug）

- `frontend/src/views/project/deep/ResearchProgress.vue:5` 渲染 `{{ doneCount }}/{{ agents.length }}`，但 `<script setup>` **从未声明 `doneCount`**（全仓库仅此一处出现）→ 渲染空字符串，显示为 `/N`。
- 旁边 `el-progress` 的 `pct`（同文件 :52-53）用内联 filter 计算，所以进度条会动、数字不动。
- 后端 `/deep/status` 已返回 `agents`（per-agent `status` = `PENDING|RUNNING|DONE|FALLBACK|FAILED`）——前端已解析并在 `pct`/`allDone` 中使用，**无需改后端**。数据源充分。

### B2 Tavily 配置不生效（后端，确定 bug）

- `src/main/java/com/sparkora/config/DeepProperties.java:12` 绑 `@ConfigurationProperties(prefix = "sparkora.ai.deep")`。
- `src/main/resources/application.yml:57` 的 `deep:` 位于 `sparkora:` 下（`sparkora.deep`），是 `sparkora.ai` 的**兄弟**节点 → `search-web-enabled`、`tavily-api-key` 从未绑定（`sparkora.ai.deep.*` 无对应 YAML）。
- 后果：`SEARCH_WEB_ENABLED` 完全无效（恒定 Java 默认 `true`）；`tavily-api-key` 字段恒空，仅因 `effectiveTavilyKey()`（`DeepProperties.java:24-34`）绕过 Spring 直读环境变量 `TAVILY_API_KEY` 才碰巧可用。
- 实测：`.env` 有 `TAVILY_API_KEY`（`tvly-` 前缀，41 位），直连 `api.tavily.com/search` 返回 HTTP 200；live `/deep/status` 的 `toolHealth.TAVILY=true`。

### B2b Tavily 从不被调用 + 失败闩锁（后端）

- `SubAgentRunner.java:76-84`：WEB 阶段按 `List.of(searxngTool, tavilyTool)` 顺序，SEARXNG 若返回非空即 `break`，Tavily 只在 SEARXNG 空结果时兜底。
- 实测 SEARXNG（`SEARXNG_BASE_URL`，见 `.env:20`）存活且返回结果 → 日志/DB 中 Tavily 调用次数为 **0**（`backend.log` 中 `tavily` 出现 0 次）。
- `TavilySearchTool.java:26,43-45,67-72`：`available() = 有 key && lastOk`；`lastOk` 初值 `true`（无启动探活），仅 `search()` 成功可置回 `true`，任何异常置 `false`。而调用方在 `available()==false` 时跳过 `search()`（`SubAgentRunner.java:78`）→ **一次瞬时失败后永久禁用，直到重启**。

### B3 工具健康 / 设置门控展示不一致（前端 + 后端，次要）

- `ResearchProgress.vue:44` `webEnabled` 硬编码 `ref(true)`，永不从接口更新；`:29` 把「调用失败 / 引擎异常」一律显示为「未配置」，误导。
- `DeepController.java:199` 工具健康把 `KB` 写死 `true`，但实测 DB `sparkora_setting.kb_enabled=f`（知识库已停用）→ UI 仍显示 `KB ✓`，与 `DeepResearchService.applySettingGates`（:250-255）的真实门控矛盾。
- `ResearchProgress.vue:85` `routeId()` 用 `window.location.pathname.split('/')[2]` 手工解析路由，脆弱；项目中已有 `useRoute()` 惯例。

### 文档漂移（附带）

- `.env.example:189` 只记 `DEEP_TAVILY_API_KEY`，实际 `.env` 用 `TAVILY_API_KEY`；spec 引用了不存在的 `DEEP_SEARCH_WEB_ENABLED`（`docs/s0-spec.md:268`）与从未绑定的 `sparkora.ai.deep.tavily-api-key`（`docs/s0-spec.md:681`）。

## Requirements

### R1 进度分子正确显示

- `ResearchProgress.vue` 顶部标签正确显示 `已完成数/总数`；完成口径与 `pct`/`allDone` 一致（`DONE|FALLBACK|FAILED`）。
- 进度条与数字口径一致；`agents` 为空时显示 `0/0`。

### R2 配置绑定修复（Tavily / 搜索开关）

- 使 `sparkora.deep.*` 的 `search-web-enabled`、`tavily-api-key`（及同块 `research-timeout-ms`、`max-agents`）真正绑定到 `DeepProperties`。
- 绑定路径与应用层既有惯例一致；修复后 `SEARCH_WEB_ENABLED=false` 能真实关闭 WEB 检索。
- 不改变 `DEEP_TAVILY_API_KEY` 优先于 `TAVILY_API_KEY` 的既有优先级。

### R3 Tavily 失败可恢复（去闩锁）

- 一次瞬时失败不得永久禁用 Tavily；需可恢复（如失败后允许重试 / 周期性恢复，或从 `available()` 移除 `lastOk` 而由调用点容错）。
- 保持「无 key → 不可用」的判定；密钥解析逻辑不变。
- 保持 SEARXNG 优先、Tavily 兜底的现有顺序（用户已确认）。

### R4 工具健康与实际门控一致

- 工具健康需反映真实设置门控：KB 停用时不应显示 `KB ✓`；WEB 关闭时 Web 工具不应显示可用。
- Tavily 标签区分「未配置（无 key）」与「调用失败」，不再把失败显示为「未配置」。
- 前端 `webEnabled` 应从接口/设置真实取值，不得硬编码。

### R5 路由解析健壮化（次要）

- `ResearchProgress.vue` 改用 `useRoute()` 获取项目 id，去掉 `window.location.pathname` 手工切分。

### R6 文档同步

- 修正 `.env.example` 与 `docs/s0-spec.md` 中关于 Tavily 变量名 / 搜索开关 / 绑定路径的漂移，与实际绑定一致。

## Out of Scope

- 不改 SEARXNG / Tavily 结果合并策略（保持 SEARXNG 优先 + Tavily 兜底）。
- 不重构深度研究编排、澄清异步、事实手册等 09-11 已交付逻辑。
- 不引入新检索源或向量库；不做工具调用的重试退避框架级改造。
- 不改动 `09-15-image-smart-app` 子任务涉及的配图功能。

## Acceptance Criteria

- [ ] 深度研究进行中，页面顶部显示 `已完成/总数`，分子随 agent 完成递增；进行条与数字一致。
- [ ] `agents` 为空时显示 `0/0`，不报错。
- [ ] `app.yml` 中 `sparkora.deep.search-web-enabled=false`（或 `.env` `SEARCH_WEB_ENABLED=false`）时，WEB 工具确实被禁用（`toolHealth` 显示 WEB 不可用，研究不再发起外部搜索）。
- [ ] 通过 YAML/属性路径配置的 Tavily key 能绑定进 `DeepProperties.tavilyApiKey`（不再依赖环境变量直读兜底才能生效）。
- [ ] Tavily 单次调用失败后，后续研究仍会再次尝试调用（不永久禁用）；无 key 时仍报「不可用」。
- [ ] 工具健康：DB `kb_enabled=f` 时 UI 不再显示 `KB ✓`；Tavily 失败态与未配置态文案不同。
- [ ] `ResearchProgress.vue` 不再使用 `window.location.pathname` 解析路由。
- [ ] 文档中 Tavily 变量名 / 搜索开关 / 绑定前缀与实际代码一致。
- [ ] 零回归：既有深度研究/简报生成/澄清流程行为不变。
- [ ] `mvn -q -DskipTests compile` 与 `cd frontend && npm run build` 均通过。

## Notes

- 复杂任务：需 `design.md`（绑定前缀修正方案的兼容性/迁移 + Tavily 可用性状态机 + 工具健康契约）+ `implement.md`。
- 实现前待查（design 首要项）：
  1. `sparkora.ai`（`AiProperties`）下是否已有 `deep:` 节点或命名冲突；决定「改前缀」还是「移 YAML 块」。
  2. `toolHealth`/设置接口是否有既有的 `kbEnabled`/`webSearchEnabled` 暴露字段可直接复用（`SettingService`）。
  3. `available()` 语义调整后对 `SubAgentRunner` 与 `DeepController` 展示的连锁影响。
