# 技术设计：简报外部搜索 Tavily 优先与全局可配置

## 1. 目标与边界

- 在现有深度研究链路内，把硬编码的 `SEARXNG → Tavily` 改为按策略路由，默认 `TAVILY_FIRST`。
- 引入 ADMIN 可调整的全局策略配置，持久化到现有 `sparkora_setting` 单行表。
- 增强证据可追溯性与 WEB 结果治理，但不引入正文抓取、语义重排或付费双源聚合。
- API Key 只从部署环境读取，配置层仅存策略枚举，不存密钥。

## 2. 架构与数据流

```text
run(briefId)
  → 校验 brief 存在/DEEP/答案已锁定
  → 运行开关与策略快照(DeepProperties.searchWebEnabled && SettingService.isWebSearchEnabled(), SettingService.webProviderOrder)
  → 每 agent: WebSearchRouter.search(query, maxResults, snapshot)
       按 effectiveOrder 依次尝试 provider:
         provider 未配置 → 跳过
         provider.search → 有效命中(含 URL)则返回
         provider 异常/超时/空结果/全部无效 URL → 记录 fallbackReason → 下一 provider
  → WebResultNormalizer: 协议校验 + URL 规范化 + 去重 + 截断 + 分配 sourceId
  → SubAgentRunner 仅把已知 sourceId 命中写入 LLM 上下文
  → 后验校验 sourceId/url/provider,不合法转 gap
  → FactSheetService 合并(沿用 KB 优先/置信规则)
```

关键约束：路由与规范化必须在子代理之前完成，LLM 只做事实抽取，不负责来源合法性。

## 3. 组件设计

### 3.1 WebProvider（策略枚举）

新建 `com.sparkora.deep.search.WebProvider`：

- 值域 `TAVILY`、`SEARXNG`。
- `WebProviderOrder.parse(String csv)` 解析逗号分隔顺序并去重；未知值或空值启动/调用时明确拒绝，不静默吞掉。
- 空或缺省配置回退到代码默认 `TAVILY,SEARXNG`。

### 3.2 WebSearchRouter

新建 `com.sparkora.deep.search.WebSearchRouter`（`@Component`）：

- 注入 `TavilySearchTool`、`SearxngSearchTool`、`DeepProperties`、`SettingService`。
- 方法 `WebSearchOutcome search(String query, int maxResults, WebSearchSnapshot snapshot)`。
- `snapshot` 包含 `order`、`webAllowed`、`briefId`、`agentId`、`topic`、`clarifyAnswers`；同一批次只构建一次。
- 逐个 provider 尝试：`available()` 为 false 则跳过并记录 `UNCONFIGURED`；返回结果经 `WebResultNormalizer` 校验为空时才降级；任一路径成功即停止。
- 返回结构包含 `hits`、`usedProvider`、`attempts`（provider、resultCount、latencyMs、fallbackReason）。

### 3.3 WebResultNormalizer

新建 `com.sparkora.deep.search.WebResultNormalizer`：

- URL 校验：仅 `http`/`https`，拒绝空 URL、非绝对 URL。
- 规范化：去 fragment、统一小写 scheme/host、按 host+path+query 去重。
- 分配稳定 `sourceId`（如 `W1`、`W2`…按本次输入顺序）。
- 超过上限时截断。

### 3.4 SearchHit 扩展

为 `SearchTool.SearchHit` 增加可空 `sourceId`、`provider` 字段（或新增 `WebHit` 包装）。遵循 Trellis 规范：record 加字段必须保留旧构造器/兼容工厂，避免既有调用方编译失败。

### 3.5 SubAgentRunner 改造

- 移除 `List.of(searxngTool, tavilyTool)` 硬编码，改调 `WebSearchRouter`。
- 保留 KB 权威块 gap 驱动逻辑，但修正 `kbHit` 未使用与注释不一致。
- WEB 查询构造使用 `topic + question + 已锁定答案`；无锁定答案时不注入未选项。
- LLM 上下文中每条 WEB 命中带 `sourceId`；提示词要求事实 `source` 必须引用已给 `sourceId`。
- 后验校验：`sourceId` 不在本次输入集合、或 URL/provider 不匹配的事实，拒绝或写入 gaps。
- 保留非法 JSON 重试与原始条目降级；修正 `rawFallback` 的 JSON 转义完整性。

### 3.6 配置读取

- 新增 `DeepProperties.webProviderOrder`，由 `application.yml` `sparkora.deep.web-provider-order: ${DEEP_WEB_PROVIDER_ORDER:TAVILY,SEARXNG}` 绑定，同步 `.env.example`。
- 运行时全局策略存 `sparkora_setting.web_provider_order VARCHAR(20) NOT NULL DEFAULT 'TAVILY,SEARXNG'`；`SettingService` 读取并缓存，写后刷新。
- 生效顺序：运行时设置非空用运行时值；否则用部署级默认。策略与开关一起在 `run()` 开始时快照。

### 3.7 可观测性与工具健康

- `research_notes` 每个 agent 增加 `search` 元数据：`strategy`、`usedProvider`、`attempts`、`resultCount`、`latencyMs`、`fallbackReason`；不写密钥。
- `webCount` 改为实际接受的 WEB 结果数；日志不再把事实条数命名 `webCalls`。
- `toolHealth` 保持 `DISABLED > UNCONFIGURED > FAILED > OK`；`available()` 仍只判配置。可增量增加 `webStrategy` 字段，不改现有三键值域。
- `ResearchProgress.vue` 增量展示当前策略与降级原因；未知态仍渲染 `--`，不乐观显示可用。

### 3.8 状态前置校验与并发

- `/run` 增加：brief 存在、属于路径 projectId、`genMode=DEEP`、研究计划就绪、`clarifyAnswers` 已锁定；否则明确 4xx。
- 增加同一 brief 的运行互斥，重复 `/run` 返回冲突，避免重复付费调用。
- 超时后 `cancel(true)`，避免后台继续产生外部调用。

## 4. 契约与兼容

- 保持 `/deep/*` 请求/响应主结构；新增字段为增量可空。
- 保持两个总开关语义与角色权限不变；策略写入口仅 ADMIN。
- `sparkora_setting` 使用幂等 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，旧行自动获得默认值。
- `docs/spec/brief-generation.md`、`docs/spec/settings.md`、`.env.example`、前端文案与 Trellis `ai-rag-guidelines.md` 同步。
- 不接入 QA/知识浏览链路，不把策略扩展到项目级/用户级。

## 5. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 默认反转导致 Tavily 成本/延迟上升 | 限额与截断；策略可由 ADMIN 立即切回；密钥未配置时自动跳过并降级 SearxNG |
| 新增 sourceId 后验校验拒绝过多事实 | 先降级为 gap + warning，不整条 agent 失败；测试覆盖合法/非法用例 |
| 设置表字段变更 | 幂等迁移 + 默认值；回滚仅清理该列或用默认值 |
| 并发重复 `/run` | 运行互斥 + 409；快照隔离设置变更 |
| 前端契约破坏 | 增量字段、未知态占位、`npm run build` 验证 |

回滚形状：代码回退 + 数据库列保留但不再读取；策略立即回 `SEARXNG,TAVILY` 即可恢复旧行为。

## 6. 明确不做

- 不做项目级/用户级策略、不做 BOTH 双源聚合、不做 Crawl4AI 正文抓取、不做向量 reranker、不引入指标平台、不在页面填写 API Key。
