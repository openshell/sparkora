# 技术设计：Tavily 正文补抓 + 事实手册分类 + 简报假设注入

> 状态：全部设计已定。**R1 机制已确认 = B：`search` 拿命中 + 对背景题按需 `POST /extract`**（见 §2）。

## 1. 边界与改动面

| 层 | 文件 | 改动 |
|---|---|---|
| 工具抽象/契约 | `deep/tool/SearchTool.java` | `SearchHit` 增正文载荷字段（nullable）+ 兼容构造器 |
| 治理 | `deep/search/WebResultNormalizer.java` | `WebHit` 透传正文（截断）+ 兼容构造器 |
| provider | `deep/tool/TavilySearchTool.java` | R1：获取正文（机制见 §2） |
| 子代理 | `deep/service/SubAgentRunner.java` | 按问题类型注入正文；`rawFallback` 携带正文 |
| 手册 | `deep/service/FactSheetService.java` | 问题→kind 映射；entry 增 `kind` |
| 写作 | `deep/service/DeepWriterService.java` | 按 kind 分组呈现 |
| 简报 | `service/BriefService.java` | 注入 `research_plan.hypotheses` |
| 配置 | `config/DeepProperties.java` + `application.yml` + `.env.example` | 正文上限（如 `DEEP_WEB_CONTENT_MAX_CHARS=2000`） |

复用既有资产：`ClarifyService.isBackgroundQuestion`（`com.sparkora.deep.service` 同包，`SubAgentRunner`/`FactSheetService` 可直接调用）。

## 2. R1 · 正文获取机制（已确认 = B）

- **B（已选）**：`TavilySearchTool.search` 保持 `search_depth=basic` 拿摘要命中；`SubAgentRunner` 在**背景题**且 WEB 命中后，对 **top 1–2 条 URL** 调用 Tavily `POST /extract`（`query=问题`、`chunks_per_source=3`、`extract_depth=basic`），取 `raw_content` 作为正文片段，工具层截断到 `effectiveWebContentMaxChars()`。
  - 成本：1 credit / 5 个成功 URL（basic）；仅背景题触发，量可控。
  - provider 无关：SearxNG 命中同样可走 `/extract` 补正文（只要 Tavily key 可用）；Tavily 不可用时跳过补正文、行为退化为纯摘要。
  - `extract` 失败/空（`failed_results` 或空 `results`）→ `content=null` 降级，绝不抛异常。
  - **新增能力接口**：`SearchTool` 增 `default List<SearchTool.SearchHit> extract(List<String> urls, String query)`（默认返回空列表）——避免在 `SubAgentRunner` 中硬编码 `TavilySearchTool` 依赖，保持工具抽象；Tavily 覆写实现。
- 未选 A（`search + include_raw_content`）：零成本但整页体积大、且只在 Tavily 搜索路径生效；B 更精准、体积小、provider 无关。
- 未选 C（自适应）：代码路径与失败面翻倍。

## 3. 数据契约（增量、向后兼容）

### 3.1 `SearchTool.SearchHit`
```java
record SearchHit(String type, String title, String url, String snippet,
                 String modelName, Long docId, double score,
                 String sourceId, String provider, String content) {   // content 新增,nullable
    // 保留 9 参(旧主构造器,content=null) 与 7 参兼容构造器
    // web(...) 增一个带 content 的重载;旧 web(...) 委托 content=null
}
```
- 语义：`snippet` = 摘要（引用/预览/既有语义不动）；`content` = 正文片段（仅供研究注入与降级留证）。

### 3.2 `WebResultNormalizer.WebHit` 增 `String content`（保留 5 参构造器），`toSearchHit()` 透传。

### 3.3 `SubAgentRunner`
- 注入策略：`isBackgroundQuestion(question)` → WEB 命中且 `content` 非空时，在 LLM ctx 追加正文行（截断至上限）；参数题仅用 `snippet`。
- `rawFallback`：fact 增 `"content":"<esc(content)>"`（仅非空时；转义沿用 `esc`）。

### 3.4 `FactSheetService.entry` 增 `kind`
- `merge` 展开 facts 时构建 `map<claim原始text, kind>`（或按 fact 出现顺序并行记录所属 note 的 question → `isBackgroundQuestion`）；聚类后取簇首条对应 kind。
- 规则：`background`（问题为背景型）/ `param`（其余、无关联、历史数据）。
- entry 仅在 `background` 时写 `kind`（无则字段不出现 → 旧契约零回归，对齐 `snippet`/`sourcesList` 范式）。**注意**：为让参数组也能分组，建议 `param` 也显式写（见 §4 AC 取舍）。

### 3.5 `BriefService.buildDeepBriefUserPrompt`
- 追加：`研究假设(研究前立的假设,请在观点中回应其是否成立):` + `research_plan.hypotheses`（解析失败/缺失则跳过）。
- `buildDeepBriefSystemPrompt` 增一条：`coreViewpoints 须体现假设是否被事实手册证实或推翻`。

## 4. 关键取舍

- **kind 写 `param` 还是省略**：省略可保证旧契约零回归，但写作分组需要「参数组」也能识别；建议 `param`/`background` **都显式写**（增量字段，旧前端不读不报错，与 `sourcesList` 同范式），并在写作侧对缺 kind 兜底为 param。
- **正文上限单点化**：工具层截断为唯一上限，避免「工具截一次、注入再截一次」的隐形双重限制（当前 200 字问题的教训）。
- **不做前端**：`FactSheetSummary` 分组展示留后续（Out of Scope），保证本轮后端可独立验收。

## 5. 兼容与回滚

- 无 schema 变更、无 Flyway。
- 旧 `fact_sheet`/`research_notes` 无新字段 → 消费侧全部容错（缺省不抛）。
- 回滚：还原上述文件即可；数据层仅 JSON 内容形态变化，不影响既有行读取。

## 6. 风险

| 风险 | 缓解 |
|---|---|
| `raw_content` 体积大（数十 KB/页） | 工具层硬截断 + 仅背景题注入 |
| 抽取失败/`failed_results` | 降级回 snippet，不抛 |
| credits 超支（若选 B/C） | 仅背景题 + top N 限制；A 方案零额外 credits |
| 正文含噪声（导航/广告） | 依赖 Tavily 清洗（markdown）；只作 LLM 素材，不作已核验事实 |
