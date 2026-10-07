# 自建汽车资讯信源与外部搜索融合基座

> 父任务：持有需求集、任务地图、跨子任务验收与最终集成评审；**本身不是实现目标**。
> 调研依据：`docs/汽车资讯信源调研报告-2026-10-05.md`（单日单机实测）。
> 姊妹任务：`10-04-brief-retrieval-sources`（外部搜索源增强，独立推进，不被本任务阻塞）。

## Goal

把现有「单一 BYD 官方新闻采集」泛化为**可配置的自建信源采集基座**：按信源各自的发布周期，用「普通 HTTP / Crawl4AI 无头浏览器 / RSS」三种通道采集权威汽车信源，清洗入库并作为**通用信源域**接入统一检索；再与外部搜索在事实手册层融合，形成「本地自有语料（可控、全文、可追溯）+ 外部搜索（实时、广覆盖）」的互补检索基座，提升简报与正文的**覆盖广度、时效性、可信度与可追溯性**。

用户价值：破解外部搜索的额度/稳定性/摘要级限制——自建信源提供可引用的全文与权威数值，外部搜索补实时与长尾覆盖。

## Background / Confirmed Facts（仓库证据 + 实测）

**现有可复用范式（仓库证据）**

- 既有完整采集链路（仅 BYD 一个来源）：`BydNewsClient`（`RestClient`+jsoup）→ `NewsService` 幂等 upsert → `NewsDocService` 切块+嵌入 → 单表 `vector_store`（`domain=NEWS`）→ `CarRagService` 统一检索 → 生成注入。见 `docs/spec/knowledge/news.md`、`docs/knowledge-base.md`。
- 任务/调度范式：`NewsSyncJobService`/`NewsSyncScheduler`（`@Scheduled` + 任务表 + 陈旧自愈 + 防重叠）+ `CarSyncJobService`/`CarSyncScheduler`。见 `src/main/java/com/sparkora/news/service/NewsSyncScheduler.java`、`src/main/java/com/sparkora/car/service/CarSyncScheduler.java`。
- 单表多域向量：`vector_store`（`metadata.domain`），`VectorStoreService` 读写/同步，HNSW cosine。见 `docs/spec/retrieval.md` §4.2/§11。
- **候选窗口必须按域隔离**（硬约束）：CAR+KB 合并窗 + NEWS 独立窗；不可共用一个全局 LIMIT，否则新闻块会挤占车型候选（实测 CAR 候选 32→0）。见 `docs/spec/knowledge/news.md` §5、`docs/spec/knowledge/kb.md` §5。
- 知识中心现为「车型/新闻」两 Tab，非编辑型聚合浏览。见 `docs/spec/knowledge/center.md`。
- **Crawl4AI 为预留未接入**：`CRAWL4AI_BASE_URL` 在 `docs/spec/brief-generation.md` §8 一直列为预留，正文补抓现由 Tavily `/extract` 承担。本任务把它从预留变为实装。
- 外部搜索现状（姊妹任务范围内，本任务只做融合消费）：`WebSearchRouter` 单 provider 短路、`WebProvider{TAVILY,SEARXNG}`、`TavilySearchTool` 等，见 `docs/spec/brief-generation.md` §4。

**调研报告实测结论（`docs/汽车资讯信源调研报告-2026-10-05.md`）**

- A 级直连可抓（普通 HTTP）：工信部 `www.miit.gov.cn` + 装备中心 `www.miit-eidc.org.cn`（列表页路径需人工定位一次）、盖世汽车 `www.gasgoo.com`（含 captcha 脚本，低风险）。
- **暂不接入（用户 2026-10-05）**：车质网 `www.12365auto.com`、汽车之家 `www.autohome.com.cn`。理由：汽车之家属泛行业资讯、外部搜索已能覆盖（含其榜单/车家号）；车质网投诉月榜对「BYD 优先」的正文写作非必需，且非 BYD 对比数据已由乘联会销量覆盖。后续按需再加（源注册表支持随时新增，无需改架构）。
- **盖世的互补性（实测 2026-10-05，保留）**：盖世 `auto.gasgoo.com` 产出**车企官宣销量转述**（正文「盖世汽车讯 10月1日，…公布了最新销量」），**月初 1 日**起即发，比乘联会（每月 8-11 日）**早约 7-10 天**；且带品牌矩阵细分（如比亚迪「王朝和海洋网」）。与乘联会口径不同（车企自报出货 vs 协会批发/零售）→ 构成**独立交叉**（可用于验证或解释口径差异）。**注意**：盖世的「销量排行」页来源不明（可能派生自乘联会/上汽险），须与「车企官宣」区分为不同 `sourceType`（见 E），排行页只作召回/选题，**不提升独立交叉计数**。
- B 级需无头浏览器：乘联会 `www.cpcaauto.com`（curl 403 边缘指纹拦截，Crawl4AI 无头 Chrome → 200）。
- C 级本机不可达（DNS 失败）：中汽协 `caam.org.cn`、崔东树个人站 `13238146.xyz` → 用转载渠道/公众号替代。
- 发布节奏（决定每源 cron）：乘联会月度销量每月 8-11 日；崔东树解读月中；工信部公示每月约 4 日/19 日两批；行业日更要闻实时（走外部搜索）。
- 资源红线：Crawl4AI 无头浏览器单实例 300-400MB、本机 swap 偏紧 → **并发 ≤2、定时任务串行执行**。
- **采集目标范围（用户 2026-10-05：BYD 优先）**：**BYD 申报/公告信息全收**（含工信部申报公示的 BYD 条目）；**其他车企仅收集销量对比数据**（乘联会月榜已覆盖），不做其他车企的逐条资讯采集。
- **频控按通道区分（评审修正 2026-10-05）**：原「同站每天请求 ≤2 次」是从乘联会单条频控推广而来，**不适用于多子页的 HTTP 源**（工信部 BYD 申报全收会被锁死）。修正为：**Crawl4AI 通道**保持同 host ≤2/天（指纹敏感+昂贵，且乘联会为月度单页够用）；**HTTP 通道**不设硬日上限，改用「同 host 最小请求间隔（默认 2s）+ 单轮条数上限」控速。

## Requirements（父级需求集，供子任务映射）

- **P-R1 信源注册与采集（→ `10-05-source-crawl-base`）**：信源以数据/配置注册（源级：name/type/垂直/cron 或发布窗口/enabled/权威档；**栏目级（一源多栏目）：list_url/detail_base_url/category/解析规则/fetchMode**）；支持 `type=RSS|SITE` 混合形态；**一源可挂多个栏目（列表页）**，每栏目独立解析/去重/降级；每源独立排期；采集产出规范化去重 + 原始留存 + 幂等 upsert；单条失败不阻断并落 `failed_items`。**扩展方式：新增栏目 = 追加一条 channel 配置，代码零改动**。
- **P-R2 抓取通道（→ `10-05-crawl4ai-transport`）**：抽象抓取 transport（HTTP fetch / Crawl4AI 无头浏览器）；Crawl4AI 实装（`CRAWL4AI_BASE_URL`），全局并发 ≤2、串行调度；**频控按通道区分**（Crawl4AI 同 host ≤2/天；HTTP 走最小间隔 + 单轮上限，无硬日限）；抓取失败按源降级并可见。
- **P-R3 检索接入（→ `10-05-source-domain-retrieval`）**：**保留 `domain=NEWS` 不改名**（避免确定性向量 id 失配/全量重嵌），以 `sourceType/category` 细分「官方新闻/自建信源」；采集内容切块+嵌入进 `vector_store`；统一检索按域隔离窗口、**域内按 sourceType 二级隔离窗口/配额（保护 BYD 不被采集源挤占）**、独立配额、来源标注、新鲜度；**结构化（表格）内容走保留行列语义的切块策略，不得被 `TextChunker` 压平丢数**（见设计 §6）；生成链路可注入。**四态 `rag_status` 语义不变。**
- **P-R7 事实来源类型（→ `10-05-source-web-fusion`，评审新增）**：自建信源事实使用**新增 `SOURCE` 类型 + 权威分档**（官方/政务 0.9、行业媒体 0.7、论坛/自媒体 0.5，默认不启用分档）。`SubAgentRunner.validateFacts` 白名单由 `KB|WEB` 扩为 `KB|WEB|SOURCE`；`FactSheetService` 新增 `SOURCE` 分级分支。**不扩展则自建信源事实会被拒或误当 KB 拿 0.9。**
- **P-R8 采集信源配图接入图库（→ B 采转存 + E 图库可检索；用户 2026-10-05 新增）**：采集详情页**正文图片**抽取后经既有 `ImageService.saveExternalImage` 转存图库（图库完全依赖图床，本地不留），使采集信源的配图可作为**文章配图**素材被语义检索命中。复用既有图库设施，**不新建图库**：
  - **B（转存侧）**：`SiteSourceClient` 详情解析时抽取正文图片；单图失败不阻断（照新闻封面图容错）；图库 `source` 白名单（`ImageService.SOURCES`，现 `{upload,ai-text2img,ai-img2img,byd,byd-news}`）新增 **`source`** 来源值；`source_ref` = 该内容对应 `sparkora_news_doc` 或 `news_id`，用于反查标题。
  - **E（检索侧）**：`ImageEmbeddingTextBuilder` 新增 `source` 分支（用信源标题作嵌入文本主信号，同 `byd-news` 模式，`sourceRef` 反查）；使新来源图可被 `ImageEmbeddingService.searchImages` 命中并进入既有配图/问答配图链路。
  - **不做**：视频入库；反盗链/水印处理；图片版权审核（沿用「采集公开内容供内部创作参考」定位）。
- **P-R4 知识中心重构（→ `10-05-source-center-ui`）**：信源注册管理与采集任务监控 UI（启停/手动触发/进度/失败重试）；采集内容统一浏览（新闻并入「信源内容」视图）。
- **P-R5 外部+本地融合（→ `10-05-source-web-fusion`）**：在 `FactSheetService` 层融合本地信源与外部搜索；本地优先/外部补缺、跨源同 URL 去重、自有语料置信与时效规则；与 `10-04-brief-retrieval-sources` 的 `usedProviders`/`SearchMeta` 契约兼容。**须遵守 §2.6 的 `SOURCE` 类型与权威分档**，且同 provider 多 endpoint 不得提升独立交叉计数。
- **P-R6 贯穿：零回归与降级**：不启用任何自建信源时，现有 BYD 新闻链路与生成行为逐位等价；自建信源采集/检索失败一律降级不阻断创作。

## Acceptance Criteria（跨子任务 / 集成）

- [ ] **AC-P1 端到端**：至少接入 **工信部（BYD 申报全收）+ 盖世汽车** 与 **≥1 个** B 级源（乘联会，经 Crawl4AI）后，能定时/手动采集入库，且采集内容可被统一检索命中并进入简报事实手册。
- [ ] **AC-P2 发布节奏**：每源 cron/窗口可独立配置并生效（乘联会月度窗口、工信部双批不会互相阻塞）；同源重叠任务被防重跳过。
- [ ] **AC-P3 资源红线**：Crawl4AI 并发 ≤2 强制生效（构造并发场景验证被限流而非击穿）；**Crawl4AI 同 host ≤2/天生效；HTTP 通道按最小间隔限速且不做硬日限**（验证工信部多子页 BYD 申报可一轮采完）。
- [ ] **AC-P4 域隔离不回归**：新增信源内容后，CAR/KB 候选窗口不被信源块挤占（复现 news.md §5 验证口径，CAR 候选不因信源块增加而下降）；**且 NEWS 域内 BYD 官方新闻不被用户采集源挤占**（域内按 sourceType 二级隔离，构造并发命中验证）。
- [ ] **AC-P8 结构化内容不丢数**：乘联会销量表等表格类内容的数值在切块后仍可被检索命中并注入（断言行列数值不丢失），不被 `TextChunker` 段内换行转空格压平。
- [ ] **AC-P9 SOURCE 事实可用**：自建信源事实以 `SOURCE` 类型进入 `fact_sheet`（`validateFacts` 不拒、不误标 KB 0.9），并按权威档取置信；构造「误标 KB」与「产出 SOURCE」两反例单测。
- [ ] **AC-P5 融合可观测**：同一项目下，融合前后 `fact_sheet` 的本地来源占比、同 URL 去重数、本地 vs 外部置信分层可见；`MULTI` 交叉规则不把同源多通道误判为独立交叉。
- [ ] **AC-P6 零回归基线**：未启用自建信源时，`mvn test` 现有全绿 + `npm run build` 通过；BYD 新闻同步与深度研究行为逐位等价。
- [ ] **AC-P7 契约与文档**：`docs/spec/knowledge/{news,center}.md`、`docs/spec/retrieval.md`、`docs/spec/brief-generation.md`、`docs/spec/image.md`、`.env.example` 同步字段级契约；URL 类配置键一律 `_BASE_URL` 结尾。
- [ ] **AC-P10 采集配图可用（用户 2026-10-05）**：采集信源详情页正文图片经转存进入图库（`source=source`），且可被 `ImageEmbeddingService.searchImages` 语义检索命中（嵌入文本以信源标题为主信号）；单图失败不阻断采集；未配图/无图源行为与现状等价。

## Out of Scope（父级范围外）

- **外部搜索源本身的改造**（Serper 接入、多源 fanout、补检索预算）→ 归 `10-04-brief-retrieval-sources`，本任务只消费其契约。
- **公众号 / B站 / 微博**采集：走 SearXNG `sogou wechat`/`bilibili` 搜索聚合，属外部搜索线，不纳入自建采集（实测其跳转链接撞搜狗 `antispider`，服务端取不到正文与可引用 URL）。
- **中汽协 / 崔东树站**直连：本机 DNS 不可达，用转载/公众号替代。
- 海外站（Autocar/InsideEVs 等）本轮不接（需代理出口，二期）。
- 采集内容的**人工编辑/审核**工作流。
- **采集配图以外的媒体**：视频/音频内容下载与入库（正文内嵌图见 P-R8 已纳入；视频不入库）。
- LLM 驱动的采集结果语义清洗（沿用确定性清洗优先原则）。
- 采集内容的去重合并去重策略变更（复用 `title+domain` 幂等语义）。

## Key Decisions

| # | 决策 | 内容 | 决策者 |
|---|---|---|---|
| D1 | 信源形态 | **混合：站点抓取为主 + RSS/Atom 支持**。实测 A/B 级数据型源几乎都无 RSS，RSS 是少数（海外/微博）——注意原文「RSS 优先」与实际权重相反 | 用户 |
| D2 | 检索域模型 | **保留 `domain=NEWS` 不改名**，以 `sourceType/category` 细分来源（评审修正：改名会使 `docId=UUID(domain+":"+refId)` 失配、须全量重嵌；改为域内来源细化 + 域内二级窗口/配额隔离） | 用户（方向）+ 评审修正（实现代价） |
| D6 | 自建信源事实类型 | **新增 `SOURCE` 类型 + 权威分档**（官方/政务 0.9、行业媒体 0.7、论坛/自媒体 0.5，默认不分档）；`validateFacts` 白名单扩 `SOURCE` | 用户 |
| D7 | 结构化内容 | 表格类内容走保留行列语义的切块策略，不得被 `TextChunker` 段内换行转空格压平 | 评审 |
| D3 | 首轮范围 | **BYD 优先收窄**：工信部（BYD 申报/公告全收）、乘联会（全行业销量对比）、盖世汽车（车企官宣销量 + 行业资讯）。**车质网、汽车之家暂不做**（用户 2026-10-05）；日更要闻与公众号仍走外部搜索 | 用户 |
| D10 | 盖世与乘联会互补 | 盖世「车企官宣销量」（月初起）= 与乘联会**独立交叉**（不同口径、更早、更细）；盖世「销量排行」页来源不明，仅作召回/选题，**不计独立交叉**。两者用不同 `sourceType` 区分 | 实测 2026-10-05 |
| D8 | 采集目标 | **BYD 优先**：BYD 申报/公告**全收**；其他车企**仅收集销量对比数据**（乘联会月榜覆盖），不做逐条资讯 | 用户 2026-10-05 |
| D9 | 频控策略 | **按通道区分**：Crawl4AI 同 host ≤2/天；HTTP 无硬日限，用最小间隔+单轮上限控速（原统一 ≤2/天会锁死工信部 BYD 全收） | 评审 2026-10-05 |
| D4 | 任务拆分 | 父任务 + 5 个可独立验收子任务（见 Delivery） | 用户 |
| D5 | 抓取通道 | Crawl4AI 从预留变为实装，独立成子任务（B 级 WAF/SPA 必需，且潜在复用正文补抓） | 实测驱动 |
| D11 | 采集配图接入图库 | **纳入**（用户 2026-10-05）。采集信源正文图转存既有图库作文章配图素材；**扩 B（转存）+ E（图库可检索），不新建任务**。复用 `sparkora_image_asset`/`saveExternalImage`；新增图库来源值 `source`；关键修正 `saveExternalImage` 相对 URL 硬编码 `byd.com` 的问题（见设计 §5.5） | 用户 2026-10-05 |

## Delivery（任务地图）

父任务 `10-05-self-hosted-sources` 持有需求集、设计与跨子任务验收，**本身不是实现目标**。

| 子任务 | 交付内容 | 前置依赖（显式） |
|---|---|---|
| [`10-05-crawl4ai-transport`](../10-05-crawl4ai-transport/prd.md) | P-R2：抓取 transport 抽象 + Crawl4AI 实装 + 并发/频控 | **无，可立即开工** |
| [`10-05-source-crawl-base`](../10-05-source-crawl-base/prd.md) | P-R1：信源注册表 + 调度 + RSS/站点解析 + 任务监控 + **内容查询 API** + **正文配图转存（P-R8）** | `10-05-crawl4ai-transport`（复用其 `FetchTransport` 接口；Crawl4AI 实现未配置时 A 级源走 HTTP） |
| [`10-05-source-domain-retrieval`](../10-05-source-domain-retrieval/prd.md) | P-R3：泛化 SOURCE 域 + 入库切块嵌入 + 检索/配额/标注/新鲜度 + 注入 + **采集配图进入图库检索（P-R8）** | `10-05-source-crawl-base` |
| [`10-05-source-center-ui`](../10-05-source-center-ui/prd.md) | P-R4：信源管理 UI + 采集任务监控 + 内容浏览 | `10-05-source-crawl-base`、`10-05-source-domain-retrieval` |
| [`10-05-source-web-fusion`](../10-05-source-web-fusion/prd.md) | P-R5：本地+外部融合、置信与去重规则 | `10-05-source-domain-retrieval`、`10-04-brief-retrieval-sources` |

依赖关系已写入各子任务 `prd.md` 的「依赖」小节，**不依赖任务树位置隐含**。

## Notes

- 调研报告为**单日单机实测**，WAF 策略可能变化；乘联会抓取后须校验返回体（含「行业新闻」字样为有效）。
- 工信部公示列表页准确路径首次接入需人工从官网导航定位一次，之后固定。
- 任何真实密钥/内网地址不写入本仓；本机服务地址（Crawl4AI/SearXNG）走 `.env` 配置。
- 变更检索/新闻相关文档须同步 `docs/spec/knowledge/news.md`、`docs/spec/knowledge/center.md`、`docs/spec/retrieval.md`、`docs/spec/brief-generation.md` 与 `.env.example`。
