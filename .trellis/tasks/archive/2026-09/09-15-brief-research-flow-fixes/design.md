# Design — 简报研究链路修复

## Boundaries

| 层 | 文件 | 改动 |
|---|---|---|
| 配置 | `src/main/java/com/sparkora/config/DeepProperties.java` | 绑定前缀修正 |
| 工具 | `src/main/java/com/sparkora/deep/tool/SearchTool.java` | 增健康状态契约（默认方法） |
| 工具 | `src/main/java/com/sparkora/deep/tool/TavilySearchTool.java` | available 去闩锁 + 状态 |
| 工具 | `src/main/java/com/sparkora/deep/tool/SearxngSearchTool.java` | 同上 |
| 编排 | `src/main/java/com/sparkora/deep/service/SubAgentRunner.java` | 无需改（调用点复用 available） |
| 接口 | `src/main/java/com/sparkora/web/controller/DeepController.java` | toolHealth 契约升级 + 设置门控 |
| 前端 | `frontend/src/views/project/deep/ResearchProgress.vue` | doneCount / 状态标签 / useRoute |
| 文档 | `.env.example`, `docs/s0-spec.md` | 变量名/绑定路径同步 |

## D1 配置前缀修正

**问题**：`DeepProperties` 声明 `prefix = "sparkora.ai.deep"`，YAML 实际在 `sparkora.deep`（`application.yml:57`）→ 整块未绑定。

**方案（推荐）**：把 `DeepProperties.java:12` 改为 `@ConfigurationProperties(prefix = "sparkora.deep")`。

- 理由：仓库既有域配置类均为 `sparkora.<domain>`（`NewsProperties`/`CarProperties`/`WenyanProperties`/`QiniuProperties`/`ImageProperties`），YAML 也已写在 `sparkora.deep`。改注解 = 零 YAML 改动、零 `.env` 改动，与惯例一致。
- 备选（不采用）：把 YAML 块移入 `sparkora.ai.deep`。技术上可行（`AiProperties`@`sparkora.ai` 与 `DeepProperties`@`sparkora.ai.deep` 可共存），但需改 YAML 且与兄弟域惯例不一致。
- 影响面：`getMaxAgents/getResearchTimeoutMs/isSearchWebEnabled/getTavilyApiKey` 全部从"恒默认值"变为真实绑定。`SEARCH_WEB_ENABLED` 由失效变为真实生效——这是**行为变更**：若部署 `.env` 曾设 `SEARCH_WEB_ENABLED=false` 却未生效，修复后会真的关闭 WEB。当前 `.env` 未设该项 → 维持默认 `true`，无现网行为翻转。
- `effectiveTavilyKey()` 优先级（`DEEP_TAVILY_API_KEY` → `TAVILY_API_KEY` → 绑定字段）不变。

**验证**：启动后断点/单测确认 `deepProps.getTavilyApiKey()` 非空（当 YAML 路径有值时）且 `isSearchWebEnabled()` 反映 YAML。

## D2 Tavily/SEARXNG 可用性去闩锁 + 三态健康

**问题**：`available()` 含 `lastOk`/`lastCallHadResults` 惰性态，但重置只能发生在被跳过的 `search()` 内 → 一次失败永久禁用。

**方案**：

1. `SearchTool` 接口新增默认方法（不破坏 KB 实现）：
   ```java
   /** 配置态:密钥/地址是否就绪(不随调用结果变化)。 */
   default boolean configured() { return true; }
   /** 最近一次调用是否成功(初值 true=未调用过,乐观)。 */
   default boolean lastCallOk() { return true; }
   ```
2. `available()` 语义收敛为**门控判定 = 配置就绪**,移除惰性闩锁：
   - Tavily：`apiKey != null && !apiKey.isBlank()`（`configured()` 同源）
   - SEARXNG：`baseUrl != null && !baseUrl.isBlank()`
3. `lastCallOk`（原 `lastOk`/`lastCallHadResults`）保留为**展示用**状态：调用成功置 true、异常/空结果置 false。因不再参与 `available()` 门控，下次研究仍会调用 → **自恢复**。
4. `SubAgentRunner` 调用点不变：`if (!webTool.available()) continue;` 现在表示"未配置则跳过"，瞬态失败不再永久跳过；SEARXNG 优先、非空即 `break` 的顺序不变（用户确认）。

**为什么不用周期性探活**：引入调度复杂度且无必要；只要门控不再被闩锁污染，每次研究天然重试。

## D3 toolHealth 契约升级（三态 + 真实门控）

**问题**：`toolHealth` 布尔无法区分"未配置/被禁用/调用失败"；KB 恒 `true` 与 DB `kb_enabled=f` 矛盾；仅查部署级 `SEARCH_WEB_ENABLED`，漏了运行时 DB 门控。

**方案**：`toolHealth` 值由 `Boolean` 改为**状态码字符串**（契约变更，唯一消费方是 `ResearchProgress.vue`）。

状态码：`OK` | `DISABLED` | `UNCONFIGURED` | `FAILED`。

判定（`DeepController.status`，注入 `SettingService`）：

- `webAllowed = deepProps.isSearchWebEnabled() && settingService.isWebSearchEnabled()`
- `KB`：`settingService.isKbEnabled() ? OK : DISABLED`（KB 无"未配置"态）
- `SEARXNG`：`!webAllowed ? DISABLED : (!tool.configured() ? UNCONFIGURED : (tool.lastCallOk() ? OK : FAILED))`
- `TAVILY`：同上
- 优先级：`DISABLED` > `UNCONFIGURED` > `FAILED` > `OK`。

响应示例：`toolHealth: {KB:"DISABLED", SEARXNG:"OK", TAVILY:"UNCONFIGURED"}`。

**兼容性**：`docs/s0-spec.md:663` 的 `toolHealth:{KB,SEARXNG,TAVILY}` 布尔契约改为状态码，spec 与前端同步。无其他消费者（已 grep 确认）。

## D4 前端 ResearchProgress

- **进度分子**：新增
  ```js
  const doneCount = computed(() =>
    agents.value.filter(a => ['DONE','FALLBACK','FAILED'].includes(a.status)).length)
  ```
  与 `pct`/`allDone` 同口径；`agents` 空 → `0/0`。
- **工具健康行**：删除硬编码 `webEnabled`；三个标签恒渲染，由状态码映射文案/颜色：
  - `OK`→success ✓；`DISABLED`→info「已停用」；`UNCONFIGURED`→info「未配置」；`SEARXNG FAILED`→danger「引擎不可用，已降级」；`TAVILY FAILED`→warning「调用失败」。
- **路由**：`import { useRoute } from 'vue-router'`，`const route = useRoute()`，`routeId()` 改读 `route.params.id`（或直接内联），去掉 `window.location.pathname`。

## Data Flow

```
application.yml(sparkora.deep.*) ──绑定──▶ DeepProperties ──▶ SubAgentRunner(webQuota/开关)
                                                          └─▶ TavilySearchTool(apiKey)
SettingService(DB 单行) ─┐
DeepProperties ──────────┴─▶ DeepController.status ──▶ toolHealth{状态码} ──▶ ResearchProgress 标签
research_notes(agents[].status) ──▶ /deep/status ──▶ ResearchProgress pct/doneCount
```

## Risks / Rollback

- **风险**：`SEARCH_WEB_ENABLED` 修复后真实生效——若某环境曾依赖"设置了但没生效"，行为会翻转。当前仓库 `.env` 未设该变量 → 无翻转；上线前需知会部署方。
- **风险**：toolHealth 契约由布尔改字符串；若外部有未识别的消费方会破坏。已确认仓库内唯一消费方为 `ResearchProgress.vue`。
- **回滚**：改动集中在单文件级，可逐文件 revert；DB 无 schema 变更，无需迁移回滚。
- 不涉及 `schema.sql` / entity，无数据库变更。

## Open Questions

（无阻塞项；D1 前缀二选一已在方案中定论为改注解，备选仅记录。）
