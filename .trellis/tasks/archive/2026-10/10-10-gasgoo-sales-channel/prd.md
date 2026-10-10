# prd.md — 盖世车企销量采集通道

> 父任务：`10-05-self-hosted-sources`。
> 前置：`10-05-source-crawl-base`（注册表/采集/任务/内容API）、`10-05-source-domain-retrieval`（入库切块嵌入）、`10-09-cpca-gasgoo-collection`（渲染通道/容器正文/结构化/配图过滤/注册API/nextRunAt，已归档）。

## Goal

让**盖世汽车「车企销量」**（各车企单独发布的销量稿）通过既有采集基座**持续增量采集**并端到端可用：定时/手动采集最新一页 → 入库 `sparkora_news` → 切块嵌入 → 统一检索命中；作为公众号可直接引用的「车企官方口径销量」素材（与乘联会协会统计互补、可交叉）。

## 关键背景（2026-10-10 实测，决定本任务为轻量）

- 盖世 `C-110` 即「汽车销量」类目：`/sales/C-110` 与 `/auto-news/C-110` **同源同类**（首条完全一致）。
- 列表含**各车企单独销量稿**：长城皮卡9月15020辆、通用Q3在华35.8万辆、北汽前9月116.7万辆、长安启源9月46507辆、上汽通用五菱前三季105.8万辆、零跑/鸿蒙智行、极氪/理想/深蓝/阿维塔…（非 BYD 为主，符合「销量全收」）。
- **现有种子栏目「销量资讯」（`10-09` V17 预置）已指向 `/auto-news/C-110`**，用现有选择器 `div.contentList dl` / `h2 a` / `span.time` 跑真实 jsoup → **20 条全解析成功**；详情 `#ArticleContent` 直连 200、标准 `<p>`。
- 列表/详情**直连可采（无反爬）**，`need_crawl4ai=false`（HTTP 通道）。
- 分页 `/C-110/{page}`（约 378 页）—— **本任务不做分页**（用户 2026-10-10 选择「持续增量，最新页」）。

## 用户决策

- **覆盖范围 = 持续增量**：定时/手动采集均取**最新一页**（~20 条），幂等去重入库；定期跑自然覆盖各车企月度销量稿。**不改采集器加分页。**

## Requirements

- **R1 栏目对齐**：确认盖世「车企销量」栏目（复用/更名自现有「销量资讯」）指向 `C-110`（`/auto-news/C-110` 或 `/sales/C-110`），`parse_rules` 选择器与真机结构一致（`list=div.contentList dl`、`title=h2 a`、`link=a`、`date=span.time`、`detail=#ArticleContent`、`images=#ArticleContent img`）；分类对齐为**「销量数据」**（更准确，且与检索标注/结构化切块语义一致）。
- **R2 持续增量采集**：启用后手动/定时采集最新页；单条详情失败不阻断；正文空仍入库元数据；幂等键 `(sourceId, channelId, externalId)` 重跑不重复。
- **R3 端到端**：采集内容入库 `sparkora_news`（`source="source"`）→ `SourceDocService` 切块 + 嵌入 `vector_store(domain=NEWS)` → `CarRagService` NEWS 域可检索命中；来源标注 `【信源：…】`。
- **R4 零回归**：未启用源 / `SOURCE_COLLECT_ENABLED=false` 时，现有 BYD 新闻与生成链路逐位等价；`mvn test` 全绿。
- **R5 文档同步**：`docs/spec/knowledge/sources.md` 登记盖世「车企销量」栏目（C-110、选择器、来源类型、持续增量语义、分页未做）。

## Acceptance Criteria

- [x] **AC-1 栏目指向正确**：盖世「车企销量」栏目 `list_url` 命中 C-110；真机解析最新页 **≥10 条**车企销量稿，且含**非 BYD**车企（长城/通用/北汽/长安/五菱/零跑/极氪/理想 等至少 3 家）。→ 真机采集 **20/20 成功，19 条非 BYD**（长城皮卡/通用/北汽/长安/五菱/零跑/极氪/理想…）。
- [x] **AC-2 增量采集可用**：手动采集成功；`sparkora_news` 出现对应行（`source="source"`、`source_id`/`channel_id`/`category` 正确）；幂等重跑行数不增。→ `POST /sources/6/collect {channelId:11}` → job SUCCESS 20/20，`sparkora_news(source_id=6)`=20 行。
- [x] **AC-3 入库切块嵌入**：采集内容生成 `sparkora_news_doc` 切块并写入 `vector_store(domain=NEWS)`；`SourceDocService.rebuildForNews` 成功（best-effort 不阻断）。→ 188 切块 + 188 `vector_store` 行（`sourceType=gasgoo-announce`）。
- [x] **AC-4 检索命中**：以销量关键词（如「比亚迪 销量」或「车企 交付」）在 NEWS 域检索可命中采集内容（`AI_RAG_SOURCE_TOPK>0` 灰度下注入验证，验证后可回落）。→ 真机检索命中采集内容（如「长城皮卡9月全球销售15020辆」正文块）。
- [x] **AC-5 零回归**：`mvn test` 全绿；未启用源时行为不变。→ `mvn test` **1117/0/0/0**；V19 仅元数据对齐（幂等 UPDATE 1→0）。
- [x] **AC-6 文档同步**：`docs/spec/knowledge/sources.md` 记录栏目契约。→ §7.5 更新为「车企销量/销量数据」+ C-110 说明 + V19 正名 + 持续增量语义。

> **验证说明**：AC-1~AC-4 经**真实站点端到端**验证（登录 admin → 手动采集 盖世 source=6/channel=11 → 20/20 入库 → 188 切块嵌入 → 检索命中 → 49 张海报入图库）。验证产生的一次性采集数据已**按 id 精确清理**（news/docs/vectors/image+tags/job 全部还原基线：`IMAGE` 向量 180、`gasgoo-announce` 0），生产库保留 V19 配置、源仍 `enabled=false`（零回归）。

## Out of Scope

- **分页/历史回填**（用户选择持续增量；分页能力留待后续需要时）。
- 盖世 `/qcxl` 排行榜（`10-09` 已证 WAF 验证码拦截，已退役）。
- 工信部新车申报（含 `.doc` 附件解析/视觉识图）——暂缓。
- 视觉识图/图片分类（海报入库沿用文本代理嵌入，`10-09` 已建）。
- 新增前端入口（复用 U 面板 + `10-09` 的 nextRunAt/状态视图）。

## Notes

- 若核实发现现有种子选择器与真机不符，则以 V19 迁移或 `PUT /api/sources`/栏目 CRUD 修正（避免改已应用的 V17）。
- 来源类型：`SourceCatalog.sourceTypeOf("盖世汽车","车企销量")` → 命中「销量」→ `gasgoo-announce`（`crossCounted=true`，可与乘联会构成独立交叉）；栏目名保持含「销量」以保证映射。
- 测试基线：当前 `mvn test` 1117 全绿（本任务预计**零或极小代码改动**，多为验证与配置对齐）。
