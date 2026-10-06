# design.md — A: Serper provider 接入

> 父任务设计论证：`../10-04-brief-retrieval-sources/design.md` §0.1（R1 六条实测硬性约束）、§5.1（垂直路由）。
> 本文档只写 A 自身的实现边界与取舍，**不重复父任务的论证**。

## 1. 边界

改动集中在 4 处，彼此独立、可分别验证：

| 层 | 文件 | 改动 |
|---|---|---|
| 配置 | `config/DeepProperties.java` | 新增 `serperApiKey` / `serperApiBase` / `serperGl` / `serperHl` / `webVerticalNewsEnabled` + `tavilyApiBase` + 6 个 `effective*()` 方法 |
| 工具 | `deep/tool/SerperSearchTool.java`（新增） | `SearchTool` 实现 |
| 工具 | `deep/tool/SearchTool.java` | 新增 `searchVertical(...)` 默认实现 + 接口注释补充认证差异说明 |
| 路由 | `deep/search/WebProvider.java`、`WebProviderOrder.java`、`WebSearchRouter.java` | 枚举加 `SERPER`、注册、`strategyLabel()` 三值 |

外加 `toolHealth` 状态接口与前端展示（契约只加项不改项）、`docs/spec/**` 与 `.env.example` 同步。

**另加（A-R9，实现期发现的集成缺口）**：设置面放开 `SERPER`——`SettingUpdateDto` 正则、`SettingsView.vue` 选项、`web_provider_order` 列宽迁移、`docs/spec/settings.md`。理由见 §10。

**不做**：任何编排逻辑（fanout / 预算 / 补检索）——那是 B 与 C。

## 2. `SearchTool` 接口扩展方式

**决策**：新增 `default` 方法 `searchVertical(String query, String vertical, int maxResults)`，默认 `return search(query, maxResults)`；`search(query, maxResults)` 保持抽象方法不变。

**理由**：
- `SearxngSearchTool` 等现有实现**零改动**即满足新契约（默认落到 `search`）→ 零回归成本最低的做法。
- `SerperSearchTool` 只覆写 `searchVertical`，`search` 委托 `searchVertical(q, "web", n)`。
- 若改为把 `search` 改成 default 并让 `searchVertical` 抽象，则所有现有实现都要改签名，回归面反而扩大。

**认证差异必须写进接口注释**：Serper 用 **Header `X-API-KEY`**，Tavily 用 **body `api_key`**。这是两个 provider 实现细节的分歧点，不写清楚后来者极易把 body 写法复制到 Serper 上（会 401）。放在 `SearchTool` 类注释而非仅 Serper 实现里，因为这是**跨实现的约定**。

## 3. `vertical` 取值与路由

**决策**：`vertical` 用 `String`（`"web"` / `"news"`），**不引入 enum**。

**理由**：垂直数少（2 个）、来源是配置与调用方拼装、`SearchTool` 是窄接口；引入 enum 会让 `SearxngSearchTool` 也被迫感知一个它不支持的概念。未知值按 `"web"` 处理并在日志 warn（**不抛异常**）——理由：垂直选择是**增强性路由决策**，抛异常会让一个可降级的选择变成硬失败，违反「降级不阻断」。这与 `WebProvider.from()` 对未知值抛 `IllegalArgumentException` 的做法**刻意不同**：后者是**运维配置错误**（必须暴露），前者是**运行时启发式选择**（可回退）。此区分需写入接口注释。

**路由规则归属**：**不放进 `SerperSearchTool`**，放 `SubAgentRunner`（决定用哪个垂直）或其上游。理由：工具层不应内含业务启发式（与 `isBackgroundQuestion` 放在 `ResearchPlannerService` 的既有分层一致）。`SerperSearchTool` 只负责「被要求用某垂直时正确执行」。

时效信号词表（`最新/近期/现在/今年/当前/动态/发布/最近`）与 `ResearchPlannerService.isBackgroundQuestion` 同处实现（`ResearchPlannerService` 内新增 `isTimeSensitiveQuestion(String)`，static 包级可见），理由同 `isBackgroundQuestion`——纯字符串判定、三处复用的既有先例。

## 4. `apiBase` 的取值与拼接

**决策**：`effectiveSerperApiBase()` 默认 `https://google.serper.dev`；请求路径用 `RestClient` 的 `uri(String, Object...)` 拼接为 `{apiBase}/search` 与 `{apiBase}/news`。

**理由与注意**：
- 中转端点是 `https://search.604020.xyz/serper`，官方是 `https://google.serper.dev` —— **差异在路径前缀 `/serper`**，不在 host。故 `apiBase` 必须能携带路径段，**不能用 `URI` 解析后丢弃 path**（这是最易踩的坑）。
- 结尾斜杠需归一（`apiBase` 末尾 `/` 时避免拼出 `//search`）。
- 默认值必须是官方端点：默认全关是零回归的前提（AC-A1）。

## 5. `gl` / `hl` 默认中文

**决策**：默认 `gl=cn`、`hl=zh-cn`，由 `sparkora.deep.serperGl` / `serperHl` 覆盖；**空值时不下发该参数**（而非下发空串）。

**理由**：实测 `gl=cn&hl=zh-cn` 回显于 `searchParameters` 且中文召回质量明显更好（autohome / byd.com / 新浪财经 / news.cn）；英文默认会削弱中文召回。空值不下发而非发空串，是为了让 provider 侧走自身默认值，便于将来换用国际源时改配置即可。

**风险**：若将来主用国际源，`gl=cn` 会成为错误约束——故必须可配，且 `.env.example` 中显式注释说明。

## 6. 条数上限与 `resultCount` 语义

**决策**：per-provider 请求条数 clamp 到 `min(maxResults, providerMaxResults)`，`SerperSearchTool` 内 `providerMaxResults = 10`（实测硬上限）；`WebSearchRouter` 的 `Attempt.resultCount` 记 **normalize 后的实际命中数**。

**理由**：实测 `num=20` 只回 10 条。若 `resultCount` 记请求数，B 的预算核算（`design.md` §4.1 per-provider 级）会把未获得的条目计入已获，**预算失真**。现有 `WebSearchRouter` 已在 normalize 后统计（`rawCount==0 ? EMPTY : INVALID_URL` 分支用的是 normalize 前后对比），需确认 `resultCount` 走的是实际命中数而非请求数——**这是实现时必须核对的第一件事**。

## 7. 契约扩展的兼容做法

| 变更 | 做法 | 依据 |
|---|---|---|
| `WebProvider` 加 `SERPER` | 追加在枚举末尾（**不插入中间**） | 现有 `WebProviderOrder.DEFAULT_RAW="TAVILY,SEARXNG"` 不变；且 C 要修的 `extract` 枚举序问题说明**枚举声明序已被误用为遍历序**，追加末尾的影响面最小 |
| `strategyLabel()` 三值 | 新增 `PRIMARY_FANOUT`，两值分支逻辑不动 | 父任务 `design.md` §2.4 |
| `WebSearchOutcome`/`SearchMeta` | **本任务不动**（属 B 的 `usedProviders`/`providers`） | 避免 A/B 同时改同一 record 造成冲突 |
| `toolHealth` | 加 `SERPER` 分支，未配置 → `UNCONFIGURED` | 照 Tavily 现状 |

`WebProvider` 枚举顺序敏感性：C 会把 `extract` 从枚举序改为快照 order，A 只需**不加剧**该问题（追加末尾即可）。

## 8. 风险

| 风险 | 缓解 |
|---|---|
| 改 `TavilySearchTool` 的 `apiBase`（A-R6）触碰既有生产路径 | 默认值与现状完全一致；`TavilySearchTool` 已有包级构造器可注入 `apiBase`（`:49`），单测沿用该注入点，**不需要 mock 端点** |
| `apiBase` 含路径段被误解析 | 单测断言 `apiBase="https://x/serper"` 时最终请求路径为 `/serper/search` |
| `SerperSearchTool` 认证写法错误 | 单测断言请求头含 `X-API-KEY`；`.env` 中转 key 可用于手工联调验证 |
| 未知 vertical 抛异常导致硬失败 | 按 §3 回落 `web` + warn |
| `resultCount` 语义被误改为请求数 | §6 已列为实现期第一件核对事项，并在 `implement.md` 设检查点 |

## 9. 回滚

无新增表。A-R9 引入一个**列宽迁移**（`web_provider_order VARCHAR(20)→VARCHAR(50)`），可独立 revert（放宽列宽不回滚无数据风险）。全量回滚 = 删除 `SerperSearchTool` + 回退 6 处改动 + 清空新增配置项（`DEEP_WEB_PROVIDER_ORDER` 与 DB `web_provider_order` 若被改为含 SERPER，需改回 `TAVILY,SEARXNG`）。`TavilySearchTool` 的 `apiBase` 改动可独立回滚且默认行为等价。

## 10. 设置面放开 SERPER（A-R9，实现期发现）

**问题**：Serper 在代码层已接通（枚举/路由注册/toolHealth），但**运行时顺序永远从 DB 取**，而 DB 默认值 `TAVILY,SEARXNG` 非空、设置 API/UI 又拒绝 `SERPER` → 无法通过任何受支持路径启用 Serper（AC-A2 在默认部署下不成立）。

**根因链**：
- `DeepResearchService.resolveSnapshot:206-210` 运行时 order 非空即优先于部署级 `.env`。
- `SettingService.get():42` 行缺失时插入 `TAVILY,SEARXNG`（永不空）。
- `SettingUpdateDto:23` `@Pattern` 仅 `TAVILY|SEARXNG`。
- `SettingsView.vue:42-43` 仅两选项；`web_provider_order VARCHAR(20)`。

**决策**：A 内一并放开设置面（最小且完整）：
- `SettingUpdateDto` 正则 `(TAVILY|SEARXNG|SERPER)` 任意组合（非法值仍 400）。
- Flyway `V12__widen_web_provider_order.sql`：`ALTER TABLE sparkora_setting ALTER COLUMN web_provider_order TYPE VARCHAR(50)`（`ADD COLUMN` 已存在的列不加，直接改类型即可；幂等 `IF EXISTS` 语义由 Flyway 版本保证）。
- `SettingsView.vue` 增补「Tavily 优先（含 Serper 兜底）」等选项，保留原两项；默认仍 `TAVILY,SEARXNG`。
- `docs/spec/settings.md` 字段值与枚举同步。

**取舍**：不把 SERPER 进默认顺序（零回归）；B/C 的多源 fanout 也依赖这个更宽的顺序，故在 A 内先放开避免 B 重做设置面。
