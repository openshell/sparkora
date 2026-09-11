# Implement — 车型库/知识库数据基座 + RAG 问答（父任务）

> 父任务不直接改产品代码。本文件是**执行编排**：子任务顺序、每个子任务开工前必须细化的产物、验证命令与回滚点。

## 执行顺序

```
C1 car-foundation-hardening ──▶ C2 news-ingestion-domain ──▶ C3 knowledge-center-ui ──▶ C4 qa-multiturn
        （基础加固/同步范式）        （新闻域+统一检索）          （浏览页）              （问答）
```

- C1 与 C3 的「车型 Tab」可并行，但 **C2 必须先于 C3 新闻 Tab 与 C4**。
- 每个子任务独立 `task.py start` → 实现 → `trellis-check` → 归档；父任务在全部子任务完成后做集成验收。

## 子任务开工前置（每个子任务各自完成）

1. 补全子任务 `prd.md`（继承父任务相关 R/AC + 本子任务独立验收）。
2. 复杂子任务补 `design.md`（含与本文「跨子任务共享契约」的衔接点）。
3. 补 `implement.md`（有序清单 + 验证命令 + 回滚点）。
4. curate `implement.jsonl` / `check.jsonl`（spec/research 清单）。

## 各子任务实现要点

### C1 车型数据基座加固
- [ ] `intro_images` 语义定稿：后端 DTO 返回图片 URL（或前端经 `/images` 解析 id→URL）；修 `CarLibrary.vue:161-166`。
- [ ] `CarModelService.delete` 级联物理清理 `car_doc_embedding`（按 model_id）+ 解除/清理图库引用。
- [ ] `schema.sql`：`sparkora_kb_chunk_embedding` 索引 IVFFLAT → HNSW（幂等 DROP+CREATE）。
- [ ] 落地 `@Scheduled` 定时增量同步（读 `sparkora.car.sync-cron`；`sync-enabled` 控制开关；默认低频/关闭）。
- [ ] 验证七牛转存：跑一次车型同步，确认 `image_asset` 有 `source=byd` 记录且 `publicUrl` 可访问。
- 验证：`mvn -q -DskipTests compile`；`./dev.sh restart backend` 后跑单车型同步 + 查看日志；`npm run build`（前端改动）。

### C2 新闻数据接入与独立知识域
- [ ] 新增 `NewsClient`（`POST /es/search` 列表 + 详情页 HTML 抽取正文）；引入 HTML 解析库（**jsoup**，需加 `pom.xml` 依赖）或等价。
- [ ] 新增 news 三表 + sync_job（`schema.sql` 幂等）+ entity/mapper。
- [ ] `NewsService`：抓取 → 清洗（正文去噪、日期/标签标准化）→ 幂等 upsert by 官方 `news_id` → 切块 → embedding。
- [ ] 统一检索接入：`searchTopKUnified` 扩 NEWS UNION；`CarRagService` 支持 `NEWS` 来源 + `【官方新闻：<title>】` 标注。
- [ ] 同步任务（手动 + 定时增量，复用 C1 范式）。
- [ ] REST：`GET /news`、`GET /news/{id}`、`POST /news/sync/jobs` 等。
- 验证：`mvn -q -DskipTests compile`；抓取 167 篇幂等重跑；检索命中新闻。

### C3 知识中心浏览页
- [ ] 新增 `/knowledge` 路由 + 页面，Tab：车型 / 新闻。
- [ ] 车型 Tab 复用 `carApi`；新闻 Tab 用 C2 新接口；补 `newsApi`/`kbApi` 封装（现有 KB 页直调 http，顺手规范）。
- [ ] 保留 `/car`、`/kb` 路由可用。
- 验证：`npm run build`；`./dev.sh start` 后手测两 Tab。

### C4 多轮对话式问答
- [ ] `AiClient` 增多轮方法（不破坏现有 `chat`/`chatJson`）。
- [ ] `QaService`：会话 + 消息；检索（`retrieveForGeneration`/`retrieveUnified` 跨三域）→ LLM 合成答案 + citations。
- [ ] 会话/消息表（`schema.sql` 幂等）+ entity/mapper；REST `/qa/sessions*`。
- [ ] 前端问答页（独立入口，非 Tab）+ 引用展示（复用 `CitationList` 范式）。
- [ ] 确认浏览/问答链路不读 `kb_enabled`（R8）。
- 验证：`mvn -q -DskipTests compile`；`npm run build`；多轮追问上下文连贯、引用正确。

## 验证命令（统一）

```bash
mvn -q -DskipTests compile          # 后端编译（只读 maven 仓库时加 -Dmaven.repo.local=/tmp/m2repo）
npm run build                       # 前端构建（frontend/）
./dev.sh restart backend            # 联调重启
./dev.sh logs backend -f            # 看日志
```

## 回滚点

- C1 索引 DDL：可 `DROP INDEX IF EXISTS idx_kb_chunk_emb_vec; CREATE INDEX ... ivfflat ...` 回退。
- C2 新闻表：新表，可 `DROP TABLE`；检索 UNION 改动可回退为 CAR+KB。
- C4 会话表：新表，可 `DROP TABLE`；`/qa` 路由可下线，不影响现有功能。

## 集成验收（父任务收尾）

- [ ] 四子任务全部归档。
- [ ] 端到端：车型同步（含七牛图）→ 新闻同步 → `/knowledge` 浏览 → `/qa` 多轮问答带引用。
- [ ] 回归：现有创作生成（简报/正文/深度）不受统一检索扩展影响。
- [ ] `docs/s0-spec.md` 与 `docs/knowledge-base.md` 更新。
