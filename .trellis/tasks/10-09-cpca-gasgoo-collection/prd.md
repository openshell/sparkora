# prd.md — 乘联会与盖世信源采集计划

> 父任务：`10-05-self-hosted-sources`（自建信源需求集与设计论证持有者）。
> 本任务是把 B/E 已交付的信源采集基座**真正用于两个真实站点**，并补齐端到端可用所需的缺口。
> 前置已就绪：`10-05-crawl4ai-transport`（FetchTransport）、`10-05-source-crawl-base`（注册表/排期/任务/内容API/配图）、`10-05-source-domain-retrieval`（入库切块嵌入）、`10-05-source-center-ui`（信源管理 UI）。

## Goal

让**乘联会**与**盖世汽车**两个信源通过既有采集基座**端到端可采集**：定时/手动采集 → 入库 `sparkora_news` → 切块嵌入 → 统一检索命中；并满足两条业务诉求：

1. **汽车销量全收**：销量类内容采集**不按 BYD 过滤**，覆盖全部车企（用户 2026-10-09 明确）。
2. **盖世销量海报入图库**：盖世销量文章的正文海报图（车企销量海报）经转存进入图库，可作为文章配图素材被图片语义检索命中。

## 背景（实测事实，2026-10-09）

### 乘联会 `www.cpcaauto.com`
- **直连 403**（真实 WAF）→ 必须经 Crawl4AI。
- 列表页 JS 渲染：`/html` 中 `<a>` **无 href**；Crawl4AI **`/crawl`** 的 `cleaned_html` 里 `.list_d ul li.q` 有真实链接 `<a href="newslist.php?types=csjd&id=NNNN">`（实测 20 条）。
- 详情页正文在 `div.read_content > div.text`，结构为 `section > span`（**全页仅 1 个 `<p>`**）→ 现有 `SiteSourceClient.detail()` 只认 `<p>`，会**几乎取空**。
- 栏目：`车市解读`(types=csjd) 最有价值（月度销量快报/深度分析/周报/预测）；另有 `行业新闻`(news)、`乘联分会论坛`(yjsy)。
- 已知限制：部分「深度分析报告」的厂商级明细可能在附件（xls/pdf），**本任务不承诺附件解析**（见 Out of Scope）。

### 盖世 `auto.gasgoo.com`
- **直连 200，无反爬**（连发 5 次首页均 200/155508B/0.33s，无验证码/JS 挑战/限速）。
- 文章页标准结构（54 个 `<p>`），现有 HTTP 通道 + `SiteSourceClient` 基本可用。
- 排行榜 `/qcxl`：`div.data > ul > li` 结构（**无 HTML `<table>`**）→ 现有 `SourceTableParser` 只认 `<table>`，需补非 table 结构化抽取。
- 文章正文含**销量海报图**：正文容器 `div.articletab` 内，图链如 `imagecn.gasgoo.com/moblogo/News/UEditor/...`；同时混有图标/公司 logo/二维码（`c2.gasgoo.com/auto2019/images/common/*`、`/companyLogo/`、`moblogo/news/qrcode/`）须过滤。

### 采集基座现状缺口（本任务需补）
- `Crawl4aiFetchTransport` 的 wantHtml 走 `/html`（**未渲染**）→ JS 站点列表抓不到链接。
- `SiteSourceClient.detail()` 正文抽取**只认 `<p>`**，容器选择器未生效。
- 无「新建信源」接口（`POST /api/sources` 及栏目写入），UI 无法建源 → 只能手工写库（U 任务 AC-U1 已标 PARTIAL）。
- `SourceTableParser` 只认 `<table>`，不支持 `div.data li` 类结构化列表。

## Requirements

- **R1 乘联会采集**：注册乘联会源 + `车市解读` 栏目后，可手动/定时采集；列表经渲染 HTML 解析出条目；详情正文完整入库（含销量数值）。**不按 BYD 过滤**（销量全收）。
- **R2 盖世采集**：注册盖世源 + 销量/资讯栏目后，经 HTTP 通道直连采集；文章正文完整入库；**汽车销量全收（不限 BYD）**。
- **R3 盖世排行榜结构化**：`/qcxl` 销量排行榜经非 table 结构化抽取（`div.data li` → 逐行「排名/车企/销量」文本），数值不丢，可被检索命中。
- **R4 渲染 HTML 抓取通道**：扩展 `Crawl4aiFetchTransport`，wantHtml 返回**渲染后 HTML**（`/crawl` 的 `cleaned_html`），使 JS 渲染列表可被现有 jsoup 选择器解析；未配置时行为不变。
- **R5 正文容器优先抽取**：`SiteSourceClient.detail()` 改为「容器选择器优先」——当 `parse_rules.detail` 命中容器时，容器内取段（多 `<p>` 用段落；段少则整体文本），解决 `section>span` 型站点取空；无容器选择器时保持现状（零回归）。
- **R6 信源注册闭环**：新增 `POST /api/sources`（含 `channels[]`）与栏目写入，使乘联会/盖世可从 API（进而 UI）注册；权限 ADMIN/EDITOR。
- **R7 盖世销量海报入图库**：盖世销量文章正文海报图经 `saveExternalImage` 转存（`source="source"`，`sourceRef`=派生 `news_id`），过滤图标/logo/二维码，单图失败不阻断；可被 `ImageEmbeddingService.searchImages` 命中。
- **R8 排期**：乘联会 `车市解读` 月度窗口（每月 8–11 日）+ 周度（车市扫描）；盖世每日或周度。每源独立可配。
- **R9 端到端可验证**：注册 → 手动采集 → `sparkora_news` 入库 → 切块嵌入 → 检索命中；盖世海报入图库且语义可检索。
- **R10 零回归**：未注册/未启用信源、`SOURCE_COLLECT_ENABLED=false` 时，现有 BYD 新闻与生成链路逐位等价。
- **R11 前端运维面板（用户 2026-10-09 要求）**：信源「信源」Tab 的运维面板能清楚看到**抓取计划**与**抓取状态**：
  - **计划**：每源「排期」+ **下次运行时间**（`nextRunAt`，后端计算，含发布窗口语义）+ 源/全局启用态；
  - **状态**：最近采集状态/进度（`total/success/failed/degraded`）、运行中 2s 轮询、失败明细与重试——**U 已交付，本项目复用不重写**；
  - 增量仅在「计划可视化」（下次运行时间列）。**不做前端新建信源入口**（用户 2026-10-09 明确同意）。

## Acceptance Criteria

- [x] **AC-1 乘联会可采**：注册乘联会「车市解读」后手动采集成功；列表解析出 ≥N 条（N≥10）；详情正文入库非空且含销量数值（如「万辆」）；`sparkora_news` 出现对应行。
- [x] **AC-2 盖世可采**：注册盖世销量栏目后手动采集成功；文章正文完整（段落 >1）；**采集结果包含非 BYD 车企**（断言存在至少 1 条非 BYD 销量内容）。
- [x] **AC-3 排行榜结构化**：`SourceTableParser.parseListRows` 能力经单测通过（`div.data li` → 逐行「排名/车企/销量」，`CELL_SEP` 分隔、数值不丢）。**真机限制（2026-10-09 复核）**：盖世 `/qcxl` 排行详情页被腾讯 WAF 验证码拦截（HTTP 200/1543B，Crawl4AI 无法渲染），故本条按「能力级交付、真机未达成」判定，V17 预置的排行栏目由 `V18` 退役。
- [x] **AC-4 渲染 HTML 通道**：wantHtml 返回渲染后 HTML（JS 站点能解析出 ≥1 条带 href 的条目）；未配置 `CRAWL4AI_BASE_URL` 时返回 `UNCONFIGURED`，行为不变。
- [x] **AC-5 容器优先正文**：`section>span` 型详情（乘联会）正文非空且长度 ≥ 阈值；多 `<p>` 型站点（盖世）正文与现状等价（回归）。
- [x] **AC-6 注册闭环**：`POST /api/sources` 携带 `channels[]` 可创建源与栏目；`GET /api/sources/{id}` 回读 `channels[]`；viewer 写被拒（403）。
- [x] **AC-7 海报入图库**：盖世销量文章海报图经转存入图库（`source="source"`、`sourceRef` 可反查标题）；图标/logo/二维码被过滤；单图失败不阻断；图可被语义检索命中。
- [x] **AC-8 排期**：乘联会/盖世源可配置并运行时注册；同源防重叠；发布窗口语义正确。
- [x] **AC-9 端到端**：完整链路（采集→入库→切块→检索）在真实数据上跑通，检索命中采集内容。
- [x] **AC-10 零回归**：`mvn test` 全绿 + `npm run build`（若动前端）；BYD 新闻/生成行为不变；无 `SOURCE_COLLECT_ENABLED` 时不变。
- [x] **AC-11 运维面板可视化**：`GET /api/sources` 每源返回 `nextRunAt`（启用且有排期时非空、含窗口语义；停用/无排期为空）；信源面板「排期」列展示「计划 + 下次运行时间」；运行中 2s 轮询与失败重试沿用既有；停用源显示「已停用」。`npm run build` 通过。

## Out of Scope

- **工信部新车申报信息入库**（用户 2026-10-09 明确暂缓）：含 `.doc`/PDF 附件解析、申报图抽取、**视觉识图/图片分类**——均不在本任务。
- 乘联会「深度分析报告」附件（xls/pdf）解析（如正文不含数据，则记限制，不解析附件）。
- 视频/音频入库。
- 图片版权审核、反盗链、水印处理（沿用「采集公开内容供内部创作参考」定位）。
- 视觉/多模态能力（`AiVisionClient` 等）——本任务海报入库沿用**文本代理嵌入**（来源标题+标签），不做图像内容识别。

## Notes

- **销量全收的落点**：`parse_rules.bydOnly=false`（default）即可；R1/R2 明确不做 BYD 过滤。BYD 优先仅体现在工信部申报（本任务不含）。
- **海报识别规则**：以「正文容器内 + URL 白名单/黑名单」为准（白名单如 `imagecn.gasgoo.com/moblogo/News/UEditor/`；黑名单含 `/common/`、`/companyLogo/`、`qrcode`、缩略图 `160_110`）；必要时以 `<img>` 宽高属性过滤小图。
- **测试基线**：本仓 `mvn test` 当前 1076 全绿；`mvn test` 不启动 Flyway/DB/Spring。
- 迁移编号：既有最大 V16；本任务如需种子数据用 **V17**。
