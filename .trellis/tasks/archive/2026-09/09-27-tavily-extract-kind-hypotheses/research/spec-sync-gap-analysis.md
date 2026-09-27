# Research: 本任务 spec 同步点与实现锚点核对

- **Query**: Phase 3.3 spec 同步——核对 `version-generation.md` R5 缺口、`brief-generation.md` 过时表述、`.env.example`/`application.yml` 注释清晰度
- **Scope**: internal（代码 ↔ 文档一致性）
- **Date**: 2026-09-27

## Findings

### 实现锚点（已读代码确认）

| 契约 | 文件:行 | 现实现状 |
|---|---|---|
| R1 `SearchTool.extract` default 空 | `src/main/java/com/sparkora/deep/tool/SearchTool.java:40` | default 返回 `List.of()`；`TavilySearchTool` 覆写 POST /extract |
| `SearchHit.content` 10 参主构造 + 9/7 参兼容 | `SearchTool.java:53/56/65` | 主构造第 10 分量 nullable content；`web(...,content)` :80、`webContent(...)` :84 |
| `WebHit.content` 透传 | `src/main/java/com/sparkora/deep/search/WebResultNormalizer.java` | 6 参 + 5 参兼容 + `toSearchHit()` 透传 |
| R1 工具层唯一截断 | `DeepProperties.java:34/38` + `application.yml:81` | `webContentMaxChars=2000`，`effectiveWebContentMaxChars()` 兜底 2000 |
| R3 背景题注入分档 | `SubAgentRunner.java:106/142` | `ClarifyService.isBackgroundQuestion` 判定；:142 注释明确「工具层已截断，此处不再二次截断」 |
| R4 手册 kind（簇首条） | `FactSheetService.java:48-49/64/89-91` | merge 并行记录每条 fact 的问题 kind，`clusterKinds` 取簇首条，缺失兜底 `param` |
| R5 写作分组 | `DeepWriterService.java:196-199/206-209/299-318/321-326` | `hasKind()` 任一非空即分组；`buildFactContext` 分「【参数事实】/【背景素材】」，全无 kind 退化平铺；缺 kind 兜底参数组；system prompt 有 kind 时追加第 4 条 |
| R6 简报假设 | `BriefService.java:133/138/153/158-161/170-186` | system prompt 增一条；user prompt 经 `hypothesesBlock` 追加；缺失/畸形 → null 跳过 |
| 配置注释 | `application.yml:80-81`、`.env.example:235-240` | 注释已清晰（说明仅背景题、工具层截断、失败降级） |

### 已正确、无需改的文档

- `docs/spec/brief-generation.md` §4「背景题正文补抓（R1/R3）」:84、§5「条目 kind 分类」:113、§6 写作注入:122、§7「简报注入研究假设」:141、§8 配置无专项行（`DEEP_WEB_CONTENT_MAX_CHARS` 未列，但 §4:89 已锚定）。均与实现一致。
- `.trellis/spec/backend/ai-rag-guidelines.md`:137（`extract` 签名）、:375-435（WEB 正文补抓 Scenario）、:439-490（kind 分类 Scenario）已与实现一致。
- `.env.example:235-240` 与 `application.yml:80-81` 注释清晰，无需改。

### 需补/需修（本次已处理）

1. **`docs/spec/version-generation.md` 缺 R5 写作分组契约**（check 阶段遗留）→ **已补**：§5 新增一条（:95），与 `brief-generation.md` §5/§6 口径一致。
2. **`docs/spec/brief-generation.md:186`** §11 已知限制旧表述「不做正文抓取」→ **已改为**「默认仍为摘要级；仅背景题补正文；`CRAWL4AI_*` 仍未接入」。
3. **`docs/spec/brief-generation.md:155`** `CRAWL4AI_BASE_URL` 注释 → **已改**为「Crawl4AI 未接入（正文补抓改由 Tavily `/extract` 承担）」；并**新增** `DEEP_WEB_CONTENT_MAX_CHARS` 配置表行（:156）。

### 其它文档不一致（列出，超范围不改）

- `docs/spec/knowledge/news.md:114` §11 已知限制同样写「WEB 命中为摘要级，不做正文抓取（`CRAWL4AI_*` 未接入）」——与 deep 研究链路现状矛盾（背景题已补正文）。属新闻知识域文档，未在本任务同步范围。
- `docs/spec/overview.md:17/186`、`docs/README.md:191` 关于 `CRAWL4AI_*` 未启用的表述**仍正确**（CRAWL4AI 确未接入），只是「不做正文抓取」的因果已由 Tavily 机制 B 补上。

## Caveats / Not Found

- `DEEP_WEB_CONTENT_MAX_CHARS` 未出现在 `brief-generation.md` §8 配置表；但 §4 正文已锚定，本次不改表（避免超范围）。
- 无 schema/Flyway 变更（AC-08 红线），三处同步不涉及迁移。
