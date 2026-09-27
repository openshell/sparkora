# 执行计划：Tavily 正文补抓 + 事实手册分类 + 简报假设注入

> 前置：R1 机制确认（design.md §2 / Q1）。确认后 `task.py start` 才进入实现。

## 实施顺序（每步可独立编译 + 单测）

1. **契约层（无行为变化）**
   - `SearchTool.SearchHit` 增 `content`（nullable）+ 保留 9/7 参构造器 + `web(...)` 重载。
   - `WebResultNormalizer.WebHit` 增 `content` + 保留 5 参构造器 + `toSearchHit()` 透传。
   - `FactSheetService.entry` 增 `kind` 参数与字段；`merge` 构建「fact → 所属问题」映射。
   - 校验：`mvn -q -DskipTests compile`；既有测试不改仍绿。

2. **R1 工具层正文获取（机制 B）**
   - `SearchTool` 增 `default List<SearchHit> extract(List<String> urls, String query) { return List.of(); }`。
   - `TavilySearchTool` 覆写 `extract`：`POST /extract`（`query`、`chunks_per_source=3`、`extract_depth=basic`、`include_raw_content`/`format=markdown`），取 `raw_content`，工具层截断到 `effectiveWebContentMaxChars()`；`failed_results`/空/异常 → 空列表。
   - `SubAgentRunner` 背景题且 WEB 命中 → 对 top 1–2 URL 调 `extract`，结果按 URL 回填对应 hit 的 `content`（用可变列表或 map 重建命中）。
   - `DeepProperties` 增 `webContentMaxChars`（默认 2000）；`application.yml` 增 `${DEEP_WEB_CONTENT_MAX_CHARS:2000}`；`.env.example` 同步。
   - 测试：`TavilySearchToolExtractTest`（mock HTTP）——正文截断、`failed_results` 降级、异常降级、非背景题不调用 `extract`。

3. **R3 子代理注入分档 + R2 降级承载**
   - `SubAgentRunner.research` ctx 追加正文行（仅背景题且 `content` 非空）。
   - `rawFallback` fact 非空 content 时增 `"content":esc(...)`。
   - 测试：`SubAgentRunnerTest` 增——背景题 ctx 含正文、参数题不含；`rawFallback` content 转义可解析。

4. **R4/R5 手册分组与写作消费**
   - `FactSheetService` 写 `kind`（param/background 显式）。
   - `DeepWriterService.write` factCtx 按 kind 分「参数事实」「背景素材」两段；缺 kind 兜底 param。
   - 测试：`FactSheetServiceTest` 增 kind 归属；`DeepWriterServiceBatchTest`/新增用例断言两段出现与归属；旧 brief（无 kind）等价。

5. **R6 简报注入 hypotheses**
   - `BriefService.buildDeepBriefUserPrompt` 注入 hypotheses；`buildDeepBriefSystemPrompt` 增观点须回应假设。
   - 测试：`BriefServiceTest` 增——prompt 捕获含假设文本；`research_plan` 缺失不报错。

6. **文档同步（Phase 3.3）**
   - `docs/spec/brief-generation.md`：WEB 正文补抓、fact_sheet `kind`、简报 hypotheses 三段契约。
   - `.trellis/spec/backend/ai-rag-guidelines.md`：SearchHit content 字段、背景题注入分档、kind 归并契约新增 Scenario。

## 验证命令

```bash
mvn -q -DskipTests compile
mvn test
# 前端未改则免（本轮不改前端）
```

## 风险文件 / 回滚点

- `SearchTool.SearchHit` 是**全链共享 record**：加字段必须保留全部旧构造器，否则多处调用/测试编译失败（对照 `ai-rag-guidelines.md` 的 record 加字段先例：`Citation` docId）。
- `WebResultNormalizer.WebHit` 构造点（router/测试）需逐一核对透传。
- `SubAgentRunner.rawFallback` 的 JSON 转义（新 content 字段）必须有测试。
- `FactSheetService` 的 fact→问题映射：`merge` 现只展开 facts，需同步记录 note 索引，勿破坏归并/数值回查。
- 回滚：纯代码还原，无数据/迁移。

## task.py start 前检查

- [ ] Q1 机制已定并写回 design.md §2。
- [ ] `implement.jsonl` / `check.jsonl` 已 curate（非空）。
- [ ] 用户已显式批准最终规划摘要。
