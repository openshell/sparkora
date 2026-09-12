# 知识库遗留清理（前端 import / 新闻列表精简 / 定时自愈 / 新闻块类型）

## Goal

清理 C1~C4「车型库/知识库数据基座 + RAG 问答」交付后遗留的 4 项非阻塞问题，使该数据基座无已知隐患。全部为小范围修复，不新增功能、不改检索语义。

## Background — 证据（代码已确认）

1. **前端缺 import（既有缺陷）**：`frontend/src/views/CarLibrary.vue:133` 的 `onRebuildAll` 调用 `http.post('/car/models/rebuild-all')`，但该文件 `<script setup>` 只 import 了 `carApi`（`CarLibrary.vue:107`），**无 `http` 导入**。ADMIN 点「重建全部向量」必触发 `ReferenceError: http is not defined`。
2. **新闻列表返回大字段**：`NewsService.list()`（`src/main/java/com/sparkora/news/service/NewsService.java:218-231`）返回 `PageResult<NewsEntity>`，`NewsEntity.content`（正文大字段）被一并序列化。`GET /api/news?size=50` 实测响应约 123KB，列表页并不需要 `content`（详情 `get()` 才需要）。
3. **定时同步缺陈旧自愈**：`CarSyncScheduler`（`src/main/java/com/sparkora/car/service/CarSyncScheduler.java`）与 `NewsSyncScheduler`（`src/main/java/com/sparkora/news/service/NewsSyncScheduler.java`）均用 `jobService.hasRunning()` 防重叠；`hasRunning()` 只查 `status=RUNNING`（`CarSyncJobService.java:153-155`、`NewsSyncJobService.java:144-146`）。若 JVM 在同步中途死亡，任务表残留 `RUNNING` 行，定时增量将**永久跳过**（手动路径不受影响）。既有自愈范式：`BriefService.java:50-51` / `VersionService.java:72-73` / `ImitationService.java:55-56` 的 `STALE_GENERATING_MS = 10 * 60 * 1000L` + `updated_at < staleCutoff`。
4. **新闻 `chunk_type` 语义/文档**：`NewsDocService.java:64` 在「单块且 `isTitleOnly`（块内无换行）」时落 `NEWS_TITLE`，否则 `NEWS_BODY`。该分支**可达**（`content` 完全为空且 title 非空时 `chunkContent` 只产出标题块，`NewsDocService.java:137-142`），并非死代码；当前图片型新闻正文含 `[图片] …` 文本，故实际落 `NEWS_BODY`。`countByNews()`（`mapper/NewsDocEmbeddingMapper.java:32-38`）全仓库无调用（列表块数实际走 `NewsDocService.chunkCount`）。文档 `docs/s0-spec.md:754,764` 已声明 `NEWS_BODY/NEWS_TITLE` 与图片型容错，语义基本准确。

## Requirements

- **R1 修复前端缺 import**：`CarLibrary.vue` 批量重建调用改为经 `carApi` 具名导出（`frontend/src/api/index.js` 现无该封装，新增 `carApi.rebuildAll()`），消除对裸 `http` 的依赖，符合 `.trellis/spec/frontend/index.md` 的 API 分层约定（页面禁直调 http）。
- **R2 新闻列表精简**（方案已定）：`NewsService.list()` 在填充 `chunkCount` 的同时将每条记录的 `content` 置空；`NewsEntity.content` 加**字段级** `@JsonInclude(JsonInclude.Include.NON_NULL)`，使 null 时该字段不在 JSON 出现。**详情 `get()` 不动**（仍返回完整 `content`）。**不可**用 `@JsonProperty(WRITE_ONLY)`（会连详情一起丢）；不改其它字段的存在性契约。已验证 C3 前端 `NewsKnowledgePanel.vue` 列表不依赖 `content`（仅详情抽屉 `detail.content` 使用）。
- **R3 定时同步陈旧自愈**（方案已定）：两个 Scheduler 的「运行中」判定改为「存在**未过期**的 RUNNING 任务」。任务表无 `updated_at`，用 `started_at` 作为存活时间戳；阈值取 **60 分钟**（`SYNC_STALE_MS`，全量 56 车型 + 清洗 + embedding 实测可超 20 分钟，10 分钟会误判活任务；60 分钟对最长合法运行足够宽裕）。存在陈旧 `RUNNING`（`started_at < now-60min`）时：先将其原子置为终态（`FAILED` + `error_msg='运行超时判定为陈旧,自动终止'` + `finished_at`），再继续本轮定时任务。未过期 RUNNING 仍正常阻塞（防重叠不回归）。
- **R4 新闻块类型澄清**（方案已定）：**不改**切块/检索行为（`NEWS_TITLE` 分支实际可达，非死代码）。动作：① 补测试固定 `NEWS_TITLE`（`content` 空 + title 非空）与 `NEWS_BODY`（有正文）两分支断言；② 删除无调用点的死代码 `NewsDocEmbeddingMapper.countByNews()` 与 `NewsDocService.chunkCount()`（均无引用；列表 `chunkCount` 由 `NewsService.list()` 直接 `docMapper.selectCount` 计算）；③ 校验 `docs/s0-spec.md` §15 措辞与实现一致（现有描述准确则不改）。

## Acceptance Criteria

- [ ] AC1：`CarLibrary.vue` 不再引用未导入的 `http`；ADMIN 批量重建链路可编译并正确调用 `carApi.rebuildAll()`（`npm --prefix frontend run build` 通过；grep 确认该文件无裸 `http`）。
- [ ] AC2：`GET /api/news?size=50` 响应 JSON 不含 `content` 字段；`GET /api/news/{id}` 仍含 `content`。列表响应体积较改前显著下降。
- [ ] AC3：构造「残留陈旧 RUNNING 任务（`started_at` 超 60 分钟）」场景，触发对应 Scheduler 方法后**不再被跳过**，且该陈旧任务被原子置为 `FAILED`（`error_msg` 标注陈旧）；未过期的 RUNNING（手动全量运行中）仍正常阻塞（防重叠不回归）。车型与新闻两侧均覆盖。
- [ ] AC4：`mvn test` 新增/覆盖 `NEWS_TITLE`（content 空 + title 非空 → 单块 NEWS_TITLE）与 `NEWS_BODY`（有正文）两分支断言并通过；`NewsDocEmbeddingMapper.countByNews()` 与 `NewsDocService.chunkCount()` 已删除（grep 无残留）；`docs/s0-spec.md` §15 与实现一致。
- [ ] AC5：`mvn -q -DskipTests compile`、`mvn test`、`npm --prefix frontend run build` 全部通过。

## Out of Scope

- 新增功能（流式问答、正文重抓、浏览增强等）。
- 修改三域统一检索语义、`RagStatus` 四态、锚点加权。
- 全量历史新闻重新抓取或向量重建。
- `CarLibrary.vue` 的其它重构（仅修 import）。

## Notes

- 轻量任务：PRD-only，无需 `design.md`/`implement.md`。
- R2 属跨层响应契约变更（列表字段集），实现时须核对 C3 前端 `NewsKnowledgePanel` 与 `newsApi` 无对列表 `content` 的依赖。
- R3 需同时覆盖车型与新闻两个 Scheduler，避免只修一处。
