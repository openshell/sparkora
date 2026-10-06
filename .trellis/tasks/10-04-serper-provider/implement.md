# implement.md — A: Serper provider 接入

> 执行计划。设计见 `design.md`；需求与验收见 `prd.md`。

## 0. 前置检查（不通过则不开工）

- [ ] `10-04-serper-provider` 的 `prd.md` 依赖小节确认「无前置依赖」。
- [ ] 基线绿：`mvn -q -DskipTests compile` 通过；`mvn test` 记录**当前通过数**（应 ≥ 510）作为回归对照。
- [ ] `.env` 已含 `SERPER_API_KEY` / `SERPER_API_BASE_URL`（用户已落），**无需索要密钥**。

## 1. 配置层（`config/DeepProperties.java`）

- [ ] 1.1 新增字段（全部带默认值）：`serperApiKey=""`、`serperApiBase="https://google.serper.dev"`、`serperGl="cn"`、`serperHl="zh-cn"`、`webVerticalNewsEnabled=true`、`tavilyApiBase="https://api.tavily.com"`。
- [ ] 1.2 新增 `effectiveSerperKey()` / `effectiveSerperApiBase()` / `effectiveTavilyApiBase()` / `effectiveSerperGl()` / `effectiveSerperHl()`，**严格照 `effectiveTavilyKey()` 的 property→env→字段兜底链写法**（`DeepProperties:41-50`）。
- [ ] 1.3 单测：兜底链各段（`System.getProperty` → `System.getenv` → 字段）、空值/空白归一、`serperApiBase` 结尾斜杠归一。

## 2. 工具接口（`deep/tool/SearchTool.java`）

- [ ] 2.1 新增 `default List<SearchHit> searchVertical(String query, String vertical, int maxResults) { return search(query, maxResults); }`。
- [ ] 2.2 类注释补充两条跨实现约定：①**认证差异**（Serper = Header `X-API-KEY`；Tavily = body `api_key`）；②**未知 vertical 回落 `web` + warn 而非抛异常**，并写明这与 `WebProvider.from()` 对配置错误抛异常的**刻意区别**（配置错误必须暴露 vs 运行时启发式可回退）。
- [ ] 2.3 单测：默认实现委派 `search`（保证 `SearxngSearchTool` 零改动即合规）。

## 3. Serper 工具（`deep/tool/SerperSearchTool.java`，新增）

- [ ] 3.1 照 `TavilySearchTool` 骨架：构造注入 `DeepProperties` + `ObjectMapper`；`RestClient` 用 `ClientHttpRequestFactoryBuilder.jdk()` + **5s 连接 / 15s 读超时**（与 Tavily 一致，`TavilySearchTool:53-57`）；保留**包级测试构造器**可注入 `apiBase`（照 `:49` 先例，便于单测不 mock 端点）。
- [ ] 3.2 `name()="SERPER"`；`available()→configured()`；`configured()` 依 `effectiveSerperKey()` 非空；`lastOk` 语义照抄（乐观初值 `true`、失败置 false、**不参与 available 门控**、仅供健康展示）。
- [ ] 3.3 `search(query,maxResults)` → 委托 `searchVertical(query,"web",maxResults)`。
- [ ] 3.4 `searchVertical`：`web` → `POST {apiBase}/search`，body `{q, num, gl, hl}`（`gl`/`hl` 空值则**不下发该键**）；`news` → `POST {apiBase}/news`，body `{q, num, gl, hl}`。**两条路径共用 `num` clamp 到 `min(maxResults, 10)`**。
- [ ] 3.5 解析：`web` → `organic[]` 映射 `link→url` / `title` / `snippet` / 忽略 `position`；`news` → `news[]` 映射 `link`/`title`/`snippet`，并**把 `date` + `source` 保留进 `SearchHit.content` 的载体或等价字段**（供后续 R4b 使用，本任务不做新鲜度计算）。
- [ ] 3.6 异常处理照抄 Tavily 语义：**只记 `e.getClass().getSimpleName()`，绝不记 `e.getMessage()`**（防密钥/URL 泄漏，`TavilySearchTool` 既有做法）；置 `lastOk=false`。
- [ ] 3.7 单测（用包级构造器注入假 `apiBase`，指向本地 mock 或断言请求体）：
  - 请求头含 `X-API-KEY`（**认证隔离断言，AC-A7**）；
  - `apiBase="https://x/serper"` → 最终路径 `/serper/search`（**路径段不被丢弃**，design.md §4）；
  - `apiBase` 结尾 `/` → 不产生 `//search`；
  - 默认 body 含 `gl=cn`/`hl=zh-cn`；配置为空 → 不下发该键；
  - `num` clamp 到 10（`design.md` §6）；
  - `organic[]`/`news[]` 字段映射正确，`news` 的 `date`/`source` 被保留；
  - 空响应 / 非法 JSON → 空列表 + `lastOk=false`，**不抛异常**。

## 4. Tavily 侧对称（A-R6）

- [ ] 4.1 `TavilySearchTool` 的 `DEFAULT_API_BASE` 改为从 `effectiveTavilyApiBase()` 取（生产构造器 `:43-46` 路径），**包级测试构造器 `:49` 的注入能力保留不变**。
- [ ] 4.2 单测：默认 `apiBase` 与现状完全一致（零回归）；`apiBase` 尾部斜杠归一。

## 5. 路由与契约

- [ ] 5.1 `WebProvider` **追加** `SERPER` 到枚举末尾（不插入中间，理由见 `design.md` §7）；`from(String)` 的大小写不敏感与未知值抛 `IllegalArgumentException` 语义不变。
- [ ] 5.2 `WebProviderOrder`：`DEFAULT_RAW="TAVILY,SEARXNG"` **保持不变**；CSV 解析支持 `SERPER`；`strategyLabel()` 扩三值（新增 `PRIMARY_FANOUT`，两值分支逻辑不动）。
- [ ] 5.3 `WebSearchRouter`：构造器注册 SERPER（`EnumMap`，`:35-40`）；`search` 的 `available()`/`configured()` 门控与 `UNCONFIGURED` 分支自动覆盖新 provider。
- [ ] 5.4 **核对并修正 `Attempt.resultCount` 语义**：确认其记的是 normalize 后**实际命中数**而非请求数（`design.md` §6，AC-A6）。若现状已是实际数则仅补单测；若记请求数则一并修正并确认 B 的预算核算不受误导。
- [ ] 5.5 `toolHealth` 状态接口加 `SERPER` 分支：未配置 → `UNCONFIGURED`；前端来源展示按 provider 区分（若现有展示已含 provider 则仅补样式/文案）。
- [ ] 5.6 单测：`WebProviderOrder` 解析 `SERPER,TAVILY`、`strategyLabel()` 三值、`WebSearchRouter` 在 `SERPER` 未配置时正确跳过且不影响 TAVILY。

## 6. 垂直路由接线（AC2a）

- [ ] 6.1 `ResearchPlannerService` 新增 `static boolean isTimeSensitiveQuestion(String)`（词表：`最新/近期/最近/现在/今年/当前/动态/发布`），**与 `isBackgroundQuestion` 同处、同为纯字符串无副作用**（`:187-191` 先例）。
- [ ] 6.2 `SubAgentRunner` 按问题时效性选择垂直传入 `searchVertical`；**默认全走 `web`**（`sparkora.deep.webVerticalNewsEnabled=false` 时强制 `web`，保证零回归）。
- [ ] 6.3 单测：时效问题 → `news`；非时效 → `web`；开关关闭 → 一律 `web`。

## 7. 文档与配置模板

- [ ] 7.1 `.env.example` 新增：`SERPER_API_BASE_URL=`、`SERPER_API_KEY=`、`DEEP_SERPER_API_BASE_URL=`、`DEEP_SERPER_API_KEY=`、`DEEP_TAVILY_API_BASE_URL=`、`DEEP_WEB_VERTICAL_NEWS=true`。**密钥项一律留空**；**URL 类键一律 `_BASE_URL` 结尾**（否则 `secret-guard` 误判为凭据，`.opencode/plugins/secret-guard.js:12`）。
- [ ] 7.2 `docs/spec/brief-generation.md`：搜索 provider 章节补 SERPER 字段级契约（认证方式、垂直、条数上限、`apiBase` 配置项）。
- [ ] 7.3 `docs/spec/retrieval.md`：provider 策略与降级链补 SERPER 位置。
- [ ] 7.4 `.env.example` 中注明 `gl=cn` 默认值的含义与「国际源场景需覆盖」（design.md §5 风险项）。

## 8. 验证（顺序执行，全绿方可进入评审）

```bash
mvn -q -DskipTests compile          # 编译
mvn test                            # 全量：新增单测 + 既有 510 例必须全绿（AC-A1）
cd frontend && npm run build        # 前端若消费 toolHealth
```

- [ ] 8.1 提交前**密钥扫描**：`git diff --cached` 中不得出现真实 key；`docs/**` 与 `.env.example` 密钥项为空。**已知教训**：本次规划中曾把 `.env` 既有 key 抄进任务文档并被 `secret-guard` 拦下。
- [ ] 8.2 手工联调（可选，需 `.env` 已配）：`./dev.sh restart backend` → 触发一次 DEEP 简报 → 查 `toolHealth` 含 SERPER 就绪、`research_notes` 的 `search.attempts` 出现 `provider=SERPER`。

## 8.5 设置面放开 SERPER（A-R9，实现期发现）

- [ ] 8.5.1 `SettingUpdateDto`：正则改 `^\\s*$|^\\s*(TAVILY|SEARXNG|SERPER)(\\s*,\\s*(TAVILY|SEARXNG|SERPER))*\\s*$`，message 同步；非法值仍 400。
- [ ] 8.5.2 Flyway `V12__widen_web_provider_order.sql`：`ALTER TABLE sparkora_setting ALTER COLUMN web_provider_order TYPE VARCHAR(50)`（不改默认值）。
- [ ] 8.5.3 `SettingsView.vue`：增补含 Serper 的顺序选项（保留原两项；默认 `TAVILY,SEARXNG`）。
- [ ] 8.5.4 `docs/spec/settings.md`：字段值与枚举同步（`SERPER` 可入顺序）。
- [ ] 8.5.5 单测：`PUT /settings` 接受 `SERPER,TAVILY` / `TAVILY,SERPER,SEARXNG` 并落库；非法（如 `GOOGLE`）仍 400；默认值不变。

## 9. 风险文件与回滚点

| 文件 | 风险 | 回滚 |
|---|---|---|
| `deep/search/WebSearchRouter.java` | 核心路由，误改会影响既有 provider | 独立 revert；`FIRST_HIT` 路径未动 |
| `deep/tool/TavilySearchTool.java` | 触碰既有生产路径（A-R6） | 独立 revert，默认值等价故无行为变化 |
| `config/DeepProperties.java` | 新增字段影响 `@ConfigurationProperties` 绑定 | 独立 revert（新字段全有默认值） |
| `deep/tool/SearchTool.java` | 接口新增 default 方法 | 二进制/源码兼容（default 方法不破现有实现） |
| `db/migration/V12__widen_web_provider_order.sql` | 列宽变更（A-R9） | 独立 revert；放宽列宽不回滚无数据风险 |
| `domain/dto/SettingUpdateDto.java` / `SettingsView.vue` | 放开 SERPER 入顺序（A-R9） | 独立 revert（默认值不变，零回归） |

## 10. `task.py start` 前的最后确认

- [ ] `prd.md` 的 AC-A1 ~ AC-A9 逐条可判定。
- [ ] `design.md` / `implement.md` / `prd.md` 无待决项。
- [ ] `implement.jsonl`（6 条）与 `check.jsonl`（4 条）已策展且 `task.py validate` 通过。
- [ ] 用户已明确批准本子任务的最终规划摘要（**尚未批准 → 不得 `task.py start`**）。
