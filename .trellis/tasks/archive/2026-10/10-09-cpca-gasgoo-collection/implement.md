# implement.md — 乘联会与盖世信源采集计划

> 复杂任务执行计划。实现顺序按依赖：通道能力（G1–G3）→ 注册闭环（G4）→ 配图过滤（G5）→ 种子（V17）→ 端到端验证。
> 每步完成后跑验证命令；全部完成后进入 check。

## 0. 实现前核对（必做）

- [ ] 复测 Crawl4AI 契约（防版本漂移）：
  `POST $CRAWL4AI_BASE_URL/crawl {"urls":["https://www.cpcaauto.com/news.php?types=csjd"]}` → 断言 `results[0].cleaned_html` 含 `newslist.php?id=`。
- [ ] 复测盖世直连：`curl -A <UA> https://auto.gasgoo.com/news/202610/9I70474346C110.shtml` → 200 且 `<p>` >1。
- [ ] 确认当前 `mvn test` 基线（预期 1076）。

## 1. G1 渲染 HTML 抓取路径

- [ ] `Crawl4aiClient.fetchHtml(url)` → `POST /crawl {urls:[url]}`，解析 `results[0]`：取 `cleaned_html`（回退 `html`）；`success=false`/`results` 空 → `EMPTY`；`/crawl` 异常回退 `/html`（best-effort）。
- [ ] 保留 `fetchMarkdown` 走 `/md`。
- [ ] 单测：mock RestClient 返回 `/crawl` 结构 → 取 cleaned_html；空 results → EMPTY；未配置 → UNCONFIGURED。

## 2. G2 容器优先正文抽取

- [ ] `SiteSourceClient.detail`：命中 `rules.contentSelector()` → 只用该容器（不回退 body）；容器内 `<p>` 拼接达阈值用段落，否则容器 `.text()` 分块；无内容选择器 → 现状 body+`<p>`。
- [ ] 单测：`section>span`（乘联会型）非空；多 `<p>`（盖世型）与现状等价；无选择器零回归。

## 3. G3 非 table 结构化抽取

- [ ] `SourceParseRules` 增 `listRows`/`rowCells`（+ 兼容构造器，保留 8 参）。
- [ ] `SourceTableParser` 增 `parseListRows(root, listRows, rowCells)`：逐行取单元格文本，`CELL_SEP` 拼接，`\n` 连接；并入 `SiteSourceClient.detail` 的块序列（tables 后、段落前）。
- [ ] 单测：`div.data li`（`span` 单元格）→ 行文本含数值且以 ` | ` 分隔；未配置零回归。

## 4. G4 信源注册闭环

- [ ] 新增 `SourceCreateDTO`（含 `channels:[ChannelDTO]`）与 `ChannelDTO`。
- [ ] `SourceService.create(dto)`（事务：插 source + channels）、`addChannel/updateChannel/deleteChannel`；改后 `scheduleService.register`。
- [ ] `SourceController`：`POST /api/sources`、`POST /api/sources/{id}/channels`、`PUT /api/channels/{id}`、`DELETE /api/channels/{id}`；全部 ADMIN/EDITOR；`R<T>`；校验（name/listUrl 必填、type ∈ {RSS,SITE}、parseRules 合法 JSON）。
- [ ] `SourceService` 多构造器（若有）显式 `@Autowired` + 装配守卫（如引入新依赖）。
- [ ] 契约测试：创建/回读 channels[]/权限（viewer 403）。

## 5. G5 配图过滤

- [ ] `SourceParseRules` 增 `imageDeny`（可选）；`SiteSourceClient` 图片抽取：容器内（`rules.imageSelector`）+ 内置默认 deny 常量 + 配置 deny 子串过滤。
- [ ] `SourceImageService.transfer` 不变（已是 `sourceRef`+容错）。
- [ ] 单测：含图标+海报 HTML → 仅海报入转存；单图失败不阻断；无图零回归。

## 6. 种子迁移 V17

- [ ] `src/main/resources/db/migration/V17__seed_cpca_gasgoo_sources.sql`：幂等 `INSERT ... WHERE NOT EXISTS` 两个源 + 4 栏目（含 `parse_rules`）。
- [ ] `db/migration/README.md` 登记 V17。
- [ ] 默认 `enabled=false`；不改表结构。

## 7. 文档

- [ ] `docs/spec/knowledge/sources.md`：登记两源采集契约、选择器、渲染路径、排行结构化、海报过滤；`parse_rules` 字段级说明（含 `listRows`/`rowCells`/`imageDeny`）；`GET /api/sources` 返回体新增 `nextRunAt`。
- [ ] `.env.example`：无需新增密钥（确认 `SOURCE_COLLECT_ENABLED` 说明）。

## 7.5 G6 运维面板「下次运行」（R11/AC-11）

- [ ] 后端：`SourceEntity` 增非持久化 `nextRunAt`；`SourceScheduleService.nextRunAt(src)`（窗口语义 + `CronExpression.next`；停用/总开关关→null；异常→null）；`SourceService.list()/get()` 回填。
- [ ] 前端：`SourceManagePanel.vue` 「排期」列展示「计划 + 下次运行时间」（空「—」，停用「已停用」）。
- [ ] 测试：`SourceScheduleServiceTest` 增 `nextRunAt` 用例（窗口内/外、cron、停用 null）；`SourceControllerContractTest` 断言 `GET /api/sources` 含 `nextRunAt`。
- [ ] `npm run build` 通过。
- [ ] **不做**前端新建信源/栏目 CRUD（用户明确同意）。

## 8. 验证命令

```bash
mvn -q -DskipTests compile
mvn test                                   # 预期基线 1076 + 新增，全绿
# 前端未改则跳过；若动前端：
cd frontend && npm run build
```

## 9. 端到端验证（手工，非 CI）

前置：`SOURCE_COLLECT_ENABLED=true`（本地/容器）、V13–V17 已应用、Crawl4AI 可达。

1. 启后端 → 确认 V17 应用（`flyway_schema_history` 到 17）。
2. `POST /api/sources`（或直接用 V17 种子）→ `GET /api/sources` 见两源。
3. 启用乘联会「车市解读」→ `POST /api/sources/{id}/collect` → `GET /api/source-jobs` 进度；`sparkora_news` 出现行，正文含「万辆」。
4. 启用盖世销量栏目 → 采集 → 断言非 BYD 车企内容存在；`/qcxl` 排行数值入库。
5. 图库：盖世文章海报经转存，`ImageEmbeddingService.searchImages` 命中。
6. 检索：`CarRagService` NEWS 域可命中采集内容（`AI_RAG_SOURCE_TOPK` 需 >0 验证注入，验证后可按需回落）。
7. 零回归：`SOURCE_COLLECT_ENABLED=false` 时 BYD 新闻/生成不变。

## 10. 风险文件与回滚点

- 高风险：`Crawl4aiClient`（换端点）、`SiteSourceClient`（正文抽取主路径）、`SourceTableParser`。
- 回滚点：G1–G5 全部向后兼容；异常时保留旧路径兜底。V17 仅种子，回滚=删除行/禁用源。

## 11. 评审门

- `task.py start` 前需用户确认本 `prd.md`/`design.md`/`implement.md`。
- `implement.jsonl`/`check.jsonl` 已按 spec/research 清单策展（见文件）。
