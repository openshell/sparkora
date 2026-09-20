# 配图模块（图库 / AI 生图 / 标签 / 语义检索 / 配图建议）

> 回链：[系统说明总览](../README.md)

职责：文章配图的全部来源与管理——图库选图、AI 文生图/图生图、车型同步图与新闻封面自动入库；图片标签与主题分类、图片语义检索、版本-图片关联、配图建议（系统建议 → 用户批准）。

> 2026-08-28 升格为正式字段级规格。配图支持**三种来源**：

| 来源 | 说明 | 接口形态（axonhub / OpenAI 兼容） |
|---|---|---|
| 图库选图 | 用户上传图进图库，从图库选用 | 不调 AI（上传即转存图床） |
| 文生图 | prompt → 生成封面/插图 | `images/generations` |
| **图生图** | 上传参考图 + prompt → 基于参考图生成 | `images/edits`（multipart 传参考图；若 axonhub/当前候选模型不支持则明确报错并提示改用文生图） |

> **2026-09-03 S6 决策**：配图并入预览步骤，不再有独立「配图」步与「完成配图」状态推进。配图入口在预览工具栏「配图」面板，提供**图库插入**（全量图库选图插入正文光标处/设封面）与 **AI 生图**（文生图/图生图，产物进图库后插入正文）两种来源。车型库图片接入**预留**（暂不开发）。
> 图床接入细节见 [img.md](../img.md)；预览/发布组装规则见 [preview.md](preview.md)/[publish.md](publish.md)。

---

## 1. 数据模型（`sparkora_image_asset`，S3b 新表；S10 增量见下）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | 主键 |
| project_id | Long | 关联项目（workspace 单租户 MVP，不单设 `workspace_id`；可空 = 全局图库） |
| file_name | String(255) | 原始文件名（生成图为 prompt 摘要命名） |
| source | String(20) | `upload` / `ai-text2img` / `ai-img2img` / `byd`（车型介绍图）/ `byd-news`（新闻封面图，09-13 image-tags 新增） |
| prompt_text | String | 生成 prompt（AI 来源时） |
| ref_image_id | Long | **图生图**的参考图 id（自引用 `sparkora_image_asset.id`，可空） |
| width / height | Integer | 尺寸（px；取不到时为空） |
| storage_key | String(300) | 图床 key（**入库即转存，非空**；URL 由图床域名实时拼） |
| created_by | String(64) | 审计：上传/生成操作人 |
| created_at | Datetime | 创建时间 |

- **S6 图库完全依赖图床，本地不留**：`storage_path` 字段已移除（历史本地图不迁移，作废）；`qiniu_key` 语义通用化为 `storage_key`。图片入库即直接转存图床，`/images/**` 静态映射已删除。
- 非持久化字段 `url`：由 `storage_key` 实时拼图床公网 URL，供前端直接展示/引用（`@TableField(exist=false)`）。

**S10 增量字段（幂等 ALTER；存量行为 NULL，旧代码兼容）**：

| 字段 | 类型 | 说明 |
|---|---|---|
| content_hash | VARCHAR(64) | 内容哈希（sha256 hex）。入库去重：命中已有记录**不重复上传图床**，返回已有记录（`dedupeHit=true`，前端提示「复用」）。仅新增入库必填；存量回填明确不做。索引 `idx_image_asset_hash` |
| gen_model | VARCHAR(100) | 生成留档：实际命中的模型名（AI 来源；上传/BYD 为空） |
| gen_size | VARCHAR(20) | 生成留档：请求尺寸（`auto`/未指定为 NULL）；regenerate 用它复现尺寸 |

- 非持久化字段新增：`thumbUrl`（七牛 `imageView2/2/w/360/format/webp` 派生；非七牛实现降级为 `url`）、`dedupeHit`（Boolean，去重命中标记）、`tags`（`List<String>`，09-13 image-tags 起由标签服务回填，按名称排序；无标签为空列表）。
- **去重管线（五来源统一）**：upload / 文生图 / 图生图 / byd（车型介绍图）/ byd-news（新闻封面）均走 `ImageService.persistOrReuse`（算哈希 → 查命中 → 复用或上传图床）。BYD 额外收益：车型同步幂等重跑不重复占图床对象。并发同哈希双写容忍（先查后插，竞态窗口最多多传一份对象）。

**09-15 img-classify 增量字段（幂等 ALTER；存量行为 NULL，旧代码兼容）**：

| 字段 | 类型 | 说明 |
|---|---|---|
| source_ref | VARCHAR(200) | **来源引用串**（通用，一个字段承载所有来源）：新闻图 = 官方 `news_id`（如 `/page/byd-cn/news-2026/detail632`），其他来源留空（未来可扩车型 `goods_id` 等）。索引 `idx_image_asset_source_ref`。**不建外键**（沿用图库表应用层维护惯例） |

- **写入语义**：增量（`NewsService.upsertOne`）随封面入库透传 `sourceRef`；去重命中已有图时**仅当已有 `source_ref` 为空才补写**（同一图片被不同新闻引用保留首次值，不覆盖，见 `ImageService.applyPresetSourceRef`）。该补写是**原子条件更新**（`WHERE id=? AND (source_ref IS NULL OR source_ref='')`，同 database-guidelines「原子抢占」范式）：WHERE 命中 0 行说明并发已写入，此时以库中现有值为准回填实体——只靠 Java 端先读后判存在 check-then-set 竞态（两条新闻并发同步同一张图会互相覆盖）。
- **存量回溯**：`ImageTagBackfillRunner` 启动任务按文件名 `detail<数字>` → `news_id` **精确后缀匹配**（先 LIKE 粗筛候选，再 Java 端精确比对，防 `detail63` 误配 `detail632`）反查新闻回填；只处理 `source='byd-news' AND source_ref IS NULL`，幂等可重跑。
- **追溯读取**：`GET /api/images/{id}/source`（见下方 API 表）。

**新闻-图库封面关联（`sparkora_news`，09-15 img-classify 幂等补列）**：

| 字段 | 类型 | 说明 |
|---|---|---|
| cover_image_id | BIGINT | 封面图对应的图库资产 id（可空，**不建外键**）。列表/详情返回非持久化 `coverImageUrl`（图床公网 URL，由图库实时拼），前端**优先用它展示**，官网 `image_url` 保留为回退（未同步封面的新闻走回退链路） |

- 回填时机：新闻增量入库（`upsertOne` 拿到 asset id 后）与存量回溯（`ImageTagBackfillRunner`）两处；**仅在新闻侧 `cover_image_id` 为空（或指向已失效图）时才写**，重同步不覆盖已同步的有效值（`NewsService.coverImageValid`）。

---

## 2. 图片标签（09-13 image-tags，独立标签表）

**数据模型（`sparkora_image_tag`，schema.sql S-tags 段幂等 `CREATE TABLE IF NOT EXISTS`）**：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL | 主键 |
| image_id | BIGINT NOT NULL | → `sparkora_image_asset.id`（应用层维护，**不建强外键**） |
| tag_name | VARCHAR(50) NOT NULL | 标签名（trim 后 1~50 字符；超长 `R.fail(400)`） |
| created_by | VARCHAR(64) NOT NULL | 操作人（用户名或 system） |
| created_at | TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP | 创建时间 |

- 标签**按名称使用**（不建标签字典表），同一图片同一标签不重复：`UNIQUE(image_id, tag_name)` 数据库级防重，应用层捕 `DuplicateKeyException` 静默吞（幂等语义）。
- 索引：`idx_image_tag_name (tag_name)`（按标签查图）；`image_id` 走唯一约束前缀（按图查标签）。
- **无 `deleted` 逻辑删除列**：关系行生命周期 = 图片生命周期，物理删（`ImageService.delete` 先清 tag 行再物理删图行；图库表本身也无逻辑删除）。
- 实体 `ImageTagEntity` / mapper `ImageTagMapper` / 服务 `ImageTagService` 三件套；`ImageAssetEntity` 仅加非持久化 `tags` 字段（主表**不加列**）。

**打标语义**：

| 场景 | 行为 |
|---|---|
| 新图入库（上传/AI 生图/车型图/新闻封面） | `saveTags`：`preset.tags` 批量写 tag 行 |
| 去重命中（`dedupeHit=true`） | `mergeTags`：已有图标签 ∪ 本次预选，只插差集（**用户预选必须生效**）；不传标签时保留已有标签 |
| 重生成（regenerate） | `copyTags(源图→新图)`：新图继承源图标签（同主题成组）；不读全局预选 |
| 单图编辑 | `replaceTags`：**全量覆盖**（先 delete 后 insert，事务内），空数组 = 清空 |
| 批量管理 | `batchApply`：`action=add` 逐图 merge；`action=remove` 逐图按名删；逐张幂等 |
| 删图 | `deleteByImageId`：物理删该图全部 tag 行（同 KB embedding 兜底清理先例） |

**BYD 图片自动分类（R2b）**：

| 来源 | 自动标签 | 实现 |
|---|---|---|
| 车型介绍图（`source=byd`） | `车型-<车型名>`（如 `车型-大唐EV`） | `CarModelService.persistIntroImages` preset 传 tags，走统一管线自动落标；**存量追溯**由 `ImageTagBackfillRunner` 启动一次性遍历 `car_model.intro_images` asset id 列表 mergeTags（幂等可重跑，异常不阻断启动；URL 旧格式跳过） |
| 新闻封面图（`source=byd-news`） | `新闻` + **`主题/<主题名>`** + **`年份/<年>`**（09-15 img-classify） | `NewsService.upsertOne` 下载 `imageUrl` 字节走 `saveExternalImage` 入库（相对 URL 拼 `https://www.byd.com`，带 `sourceRef=news_id`）；标签由 `NewsImageClassifier.toTagsFrom(title, publishDate)` 派生（零 AI）。单图下载失败仅告警**不阻断新闻入库**；`sparkora_news.image_url` 保留原 URL 留痕 |

- 新闻正文内嵌图**不入库**（图片型新闻多为装饰长图，量级/噪音风险，范围外）。
- 来源白名单 `SOURCES` = `upload` / `ai-text2img` / `ai-img2img` / `byd` / **`byd-news`**（非法值 400）。

---

## 3. 新闻图片主题分类（09-15 img-classify，子A）

**分类器**：`com.sparkora.news.classify.NewsImageClassifier`（纯静态、无 Spring 依赖、零 AI 调用、可单测）。词表是**受控产品定义**，以代码常量 `LinkedHashMap<String, Pattern>` 固化（保序 = 展示序；改词表 = 改代码发版，不入 DB 字典表）。输入新闻标题 → 命中主题集合（保序 0~n，**允许重叠**，如「海外销售再创新高」同时命中「销量」+「出海」）；无命中不打主题标签（不强制归「其他」，避免噪音标签）。

| 顺序 | 主题 | 关键词（正则） |
|---|---|---|
| 1 | 销量 | `销售` |
| 2 | 出海 | `海外\|出海\|出口\|全球化\|国际化\|欧洲\|拉美\|东南亚\|巴西\|泰国\|印尼\|印度\|日本\|澳洲\|澳大\|乌兹\|匈牙利\|墨西哥\|文莱\|哥伦比亚\|香港\|德国\|慕尼黑\|东京\|曼谷\|首尔\|韩国\|英国\|智利\|罗马尼亚\|瑞士\|尼日利亚\|柬埔寨\|贝宁\|加蓬` |
| 3 | 合作签约 | `合作\|签约\|携手\|战略` |
| 4 | 技术发布 | `发布\|技术\|刀片\|云辇\|智驾\|平台\|闪充\|芯片\|系统` |
| 5 | 里程碑 | `下线\|里程碑\|万辆\|纪录` |
| 6 | 荣誉 | `荣\|获\|奖\|榜\|500强\|冠军` |
| 7 | 财报ESG | `财报\|业绩\|ESG` |
| 8 | 车展上市 | `上市\|首发\|车展\|亮相` |
| 9 | 社会责任 | `捐赠\|慈善\|公益\|驰援\|救灾\|基金` |

> 词表以**真实 167 条新闻标题回归**校准（`NewsImageClassifierTest` + 任务 implement.md「实测词表调整」）：实测命中 销量 45 / 出海 65 / 合作签约 18 / 技术发布 22 / 里程碑 25 / 荣誉 22 / 财报ESG 6 / 车展上市 16 / 社会责任 3，无命中 21。design.md 草案的出海词表未覆盖「进入/登陆 <国> 市场」类标题，回归时补入国别词（智利/罗马尼亚/瑞士/尼日利亚/柬埔寨/贝宁/加蓬/首尔/韩国/英国）并将「国际化」并入；宽泛词「荣/获/榜」「发布」回归确认无误命中，保留。

**标签命名空间**（与用户自由标签隔离，筛选下拉可按前缀分组）：

| 标签 | 命名 | 说明 |
|---|---|---|
| 主题标签 | `主题/<主题名>`（如 `主题/销量`） | 受控词表命中，0~n 个 |
| 年份标签 | `年份/<年>`（如 `年份/2026`） | 由来源新闻 `publish_date` 派生；取不到日期则不打 |

- 命名空间前缀的目的：与用户自由标签（用户自建的「销量」）隔离；`GET /api/images/tags` 返回的**名称即含前缀**（响应结构不变，仍是 `[{name,count}]`），前端按 `/` 前缀 `el-option-group` 分组展示（`主题` / `年份` / `其他`）。
- 人工修正：复用既有单图编辑（`PUT /{id}/tags` 全量覆盖）与批量打标（`POST /tags/batch`），无需新 UI。
- **存量回溯**：`ImageTagBackfillRunner` 新闻分支只处理 `source='byd-news' AND source_ref IS NULL` 的图（分批 200，避免全量内存），解析文件名 → 反查新闻 → `mergeTags`（只插差集，幂等）+ 回填 `source_ref`/`cover_image_id`；异常仅 warn 不阻断启动，日志汇总「处理 X / 跳过 Y / 失败 Z」。2026-09-16 实测：157 张全部处理（跳过 0 / 失败 0），重跑零新增。

---

## 4. 图片语义检索（09-15 img-semantic-search，子B）

**语义**：让图片可被**自然语言检索**（「销量海报」「出海签约的照片」），为配图建议与问答语义配图提供检索能力。图片本身没有可嵌入文本，用**描述性文本代理**（来源新闻标题 / 标签 / AI prompt / 文件名）向量化。

**数据模型（`sparkora_image_embedding`，schema.sql 09-15 img-semantic-search 段幂等 `CREATE TABLE IF NOT EXISTS`）**：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL | 主键 |
| image_id | BIGINT NOT NULL | → `sparkora_image_asset.id`（应用层维护，**不建强外键**；与 `sparkora_image_tag` 同惯例） |
| embedding | VECTOR(1024) NOT NULL | **与 car/kb/news 三域同模型（Qwen3-Embedding-8B）同维度（1024）同向量空间**——硬约束，否则跨域检索无意义，故**不存模型名/维度列** |
| source_text | TEXT NOT NULL | 嵌入原文（调试 + 重建可追溯） |
| created_at | TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP | 创建时间 |

- **一图一向量**：`CREATE UNIQUE INDEX IF NOT EXISTS uk_image_emb_image ON sparkora_image_embedding(image_id)` —— 唯一约束即幂等保证（重建先物理删后插，重复插入不可能；并发重复嵌入第二插入报唯一冲突由 `embedQuietly` 吞掉并 warn）。
- 向量索引 `idx_image_emb_vec_hnsw`：`USING hnsw (embedding vector_cosine_ops)`（与三域统一 HNSW cosine）。
- **不加 `deleted` 列**（物理表，同三域 embedding 表）；**不建 FK**：删图时应用层同事务物理清向量（`ImageService.delete` → `embeddingService.deleteByImageId`），防残留向量命中已删图。
- 实体：本表**无 entity**（VECTOR 类型 MyBatis-Plus `BaseMapper` 无法处理），用注解 SQL mapper `ImageEmbeddingMapper`（`insert`/`deleteByImageId`/`findImageIdsWithoutEmbedding`/`searchTopK`，参照 `CarDocEmbeddingMapper` 先例）。

**嵌入文本构造（`com.sparkora.image.embed.ImageEmbeddingTextBuilder`，纯静态可单测）**：

| source | 文本构成 |
|---|---|
| `byd-news` | 来源**新闻标题**（优先，由 `source_ref` 反查 `sparkora_news.news_id`）+ 标签（含 `主题/*`、`年份/*`）；查不到标题退化为只用标签 |
| `ai-text2img` / `ai-img2img` | `prompt_text` + 标签 |
| `upload` | 文件名（去扩展名）+ 标签 |
| `byd`（车型图） | 文件名（去扩展名）+ 标签（含 `车型-*`） |

- 各段**空格连接、去空段**；整体 trim 后为空 → 兜底 `(图片 <id>)`，**仍写向量**（避免图库里出现永远搜不到的缺向量图，低质命中由检索门槛过滤）。
- 标签**原样拼**（保留 `主题/` 前缀）：「销量」等关键词本身就是检索信号，剥前缀反而丢信息。
- 文本长度上限 **2000 字符**截断（防超长输入打爆 embedding；标题+标签实际远小于此）。
- 嵌入文本构造在 `ImageEmbeddingService` 内按需拼（新闻标题反查），**重建路径自给自足**——无需调用方补上下文。

**向量化时机**：

| 时机 | 行为 |
|---|---|
| 增量（入库） | `ImageService.persistOrReuse` 在**新图 insert 成功后**与**去重命中分支**均调 `embeddingService.embedQuietly(id)`（best-effort：捕获全部异常仅 warn，**绝不影响图片入库**——图片可用性优先于可检索性；钩子在入库成功之后，不掩盖入库本身异常） |
| 重生成继承标签后 | `ImageService.regenerate` 复制源图标签后重嵌（嵌入文本与最终标签保持一致） |
| 存量补齐 | `ImageEmbeddingBackfillRunner`（`ApplicationRunner`，`@Order(20)`）启动调 `rebuildMissing()`——只处理 `LEFT JOIN` 差集为空向量的图；异常仅 warn **不阻断启动**；日志 `图片向量补齐完成:total=X success=Y failed=Z(耗时Nms)`。2026-09-16 首启实测 166/166、0 失败；重跑「无缺失,跳过(total=0)」。**独立守护线程执行**（实测 166 图串行 embedding 约 173s，不占启动主线程；应用就绪不被拖慢） |
| 全量重建 | `POST /api/images/embeddings/rebuild`（ADMIN/EDITOR）：遍历全部图片逐图重新嵌入（**先物理清旧向量再插**，幂等），单图失败跳过并计数 |

**启动补齐顺序（09-15 修订，`@Order` 硬约束）**：`ImageTagBackfillRunner`（`@Order(10)`，补 `主题/*`/`年份/*`/`车型-*` 标签与 `source_ref`）必须**先于** `ImageEmbeddingBackfillRunner`（`@Order(20)`）——嵌入文本依赖标签信号，先嵌入后补标会让存量图拿到「无标签」低质向量，且因「已有向量」`rebuildMissing()` 不再修（静默、需人工调全量重建）。两个 runner 都显式标注 `@Order` 固定该契约。

**事务隔离（09-15 修订，关键契约）**：向量写入（`ImageEmbeddingService.embedOne` → `persistVector`）经自注入代理走 `@Transactional(REQUIRES_NEW)`，**绝不加入调用方的环境事务**：

- 入库链路（新闻同步 `NewsService.upsertOne`、车型同步 `persistModel`）自身是事务性的；若向量 SQL 在其中失败（维度不符 / 唯一索引并发冲突），PostgreSQL 会把**整个调用方事务**置为 aborted——此后调用方任何 SQL 都抛 `current transaction is aborted`，Java 侧 `catch` 无法挽回，「嵌入失败不阻断图片入库」契约即被打破（图片 INSERT 也会随事务回滚）。独立事务后向量失败只回滚自身，调用方照常提交。
- 独立事务同时让「先删后插」**原子化**：重嵌失败回滚保留旧向量，不留「删了没插上」的空洞。
- embedding 网络调用放在事务之外（不长时间占连接）。

**检索实现（`ImageEmbeddingService.searchImages`）**：`query` 空校验 → **标签 AND 预过滤**（复用图库列表的 `resolveTagIds` 交集语义；交集为空**直接返回空列表且不调用 embedding**，省一次调用）→ `EmbeddingClient.embed(query)` → `ImageEmbeddingMapper.searchTopK`（HNSW cosine 排序，**门槛写在 SQL 的 WHERE**，不传输注定被丢弃的行；白名单候选集 ≤500 截断保底，与 `GET /api/images` 一致）→ 批查主表回填 `fileName/source/sourceRef` + 派生 `url/thumbUrl`（复用 `ImageService.fillDerived` 静态实现，同一派生规则只此一处）+ `ImageTagService.fillTags` 回填 `tags`。

**接口契约（全部 `R<T>`，HTTP 200 业务失败）**：

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| POST | `/api/images/search` | 三角色 | `{query, topK?, minScore?, tags?[]}`（`@Valid ImageEmbedDTO`；`tags` AND 语义，与 `GET /api/images` 一致） | `data = [{imageId, score, sourceText, fileName, source, sourceRef, url, thumbUrl, tags[]}]`，按 `score` 降序 |
| POST | `/api/images/embeddings/rebuild` | ADMIN/EDITOR | 无 body | `data = {total, success, failed}`（幂等：重复调用结果稳定；失败图不阻断整体，原因见后端日志） |

**错误矩阵**：

| 条件 | 行为 |
|---|---|
| `query` 空/空白/缺失 | 400 `R.fail(400,"检索内容不能为空")`（DTO `@NotBlank` 中文消息） |
| `topK` > 50 / < 1 | 收敛为 50 / 默认 10（**不报错**） |
| `minScore` 为 null | 用 `AI_IMAGE_MIN_SCORE`（默认 0.3） |
| `tags` 无交集成空集 | 200 `data: []`（**不调用 embedding**） |
| `AI_EMBEDDING_MODEL` 未配置 | 500（消息含「未配置」，来自 `EmbeddingClient.embedList`） |
| embedding 调用失败 | 500 `R.fail(500,"图片语义检索失败: …")` |
| VIEWER 调 search | 200（三角色可读） |
| VIEWER 调 rebuild | 403（`@PreAuthorize`） |

**配置**：`AI_IMAGE_MIN_SCORE`（默认 0.3，对齐 `AI_RAG_MIN_SCORE` 口径）→ `sparkora.ai.image-min-score`。

**已知限制**：标签变更**不触发实时重嵌**——`source_text` 会与当前标签漂移（检索仍能命中旧文本）；用重建接口修正即可（实时重嵌留给后续任务）。

---

## 5. 版本-图片关联（挂版本，不挂项目）

`sparkora_article_version` 增列（幂等 ALTER）：

| 字段 | 类型 | 说明 |
|---|---|---|
| cover_image_id | BIGINT | 该版本封面（`sparkora_image_asset.id`，可空；每版本一张） |
| body_image_ids | String(1000) | 正文插图 id 列表（逗号分隔，有序） |

> 理由：多版本各有排版，预览/发布按「当前版本」取图；项目级关联无法表达版本间差异。

**配图建议「忽略」记录（09-15 article-auto-illustrate 子C 新表，幂等建表）**：

`sparkora_illustration_dismiss`（**只有用户的「忽略」决策落库**；建议候选本身不落库）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL | 主键 |
| project_id | BIGINT | → `sparkora_article_project.id`（应用层维护，不建强 FK） |
| version_id | BIGINT | → `sparkora_article_version.id`（忽略记录不跨版本） |
| anchor_key | VARCHAR(200) | 锚点指纹（`headingPath` + 归一化文本前 80 字符的 sha256 前 12 位 hex） |
| created_by | VARCHAR(64) | 操作人（用户名或 system） |
| created_at | TIMESTAMP | 默认 `CURRENT_TIMESTAMP` |

- `UNIQUE (version_id, anchor_key)` 数据库级防重（重复忽略幂等，不报错）；索引 `idx_illustration_dismiss_version`。
- 无 `deleted` 逻辑删除列：关系行生命周期 = 版本生命周期，物理删（同 `sparkora_image_tag` 惯例）；不建强外键（沿用图库表应用层维护惯例）。
- 语义见下文「配图建议」。

---

## 6. 配图 API（全部 `R<T>` 包装；HTTP 200；S10 起检索/生成契约升级）

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| GET | `/api/images` | 三角色 | `?projectId=&source=&keyword=&tag=&page=1&size=24` 组合查询（`source` 白名单 `upload/ai-text2img/ai-img2img/byd/byd-news`，非法值 400；`keyword` 命中 `file_name`/`prompt_text`，ILIKE；**09-15 起 `tag` 支持多值**——重复参数或单值内逗号分隔，语义为 **AND**（图片须同时具备所有指定标签），逐标签查 `idx_image_tag_name` 取 id 集求交集，交集为空直接返回空页；其余筛选照常组合；单值行为与旧版单标签等价） | `PageResult`：`{rows[], total, page, size}`；rows 内每条含 `url` + `thumbUrl` + **`tags[]`**（按名称排序）+ **`sourceRef`**。**S10 起不再返回全量列表** |
| GET | `/api/images/{id}/source` | 三角色 | —（09-15 img-classify 新增） | `data = {sourceRef, news, imageUrl}`：`news` 为 `{id, newsId, title, publishDate, url}`（`source_ref` 为官方 `news_id` 且能反查到新闻时）；**非新闻图（upload / AI 生成图 / 车型图）或查无新闻 → `news: null`**（显式输出，HTTP 200 不报错）；图片不存在 `R.fail(400)` |
| GET | `/api/images/tags` | 三角色 | — | `data` = `[{name, count}]`（全库标签 + 引用数量，count 降序「常用优先」；预选控件与筛选联想同源复用；**09-15 起名称含 `主题/`、`年份/` 前缀**，响应结构不变） |
| POST | `/api/images/search` | 三角色 | `{query, topK?, minScore?, tags?[]}`（09-15 img-semantic-search 新增；`tags` AND 语义同 `GET /api/images`；`topK` 默认 10 上限 50，超限收敛不报错；`minScore` null 时用 `AI_IMAGE_MIN_SCORE` 默认 0.3） | `data = [{imageId, score, sourceText, fileName, source, sourceRef, url, thumbUrl, tags[]}]`（`score` 余弦相似度降序）。`query` 空 → `R.fail(400,"检索内容不能为空")`；`tags` 交集空 → `data:[]`（不调 embedding）；模型未配置/调用失败 → `R.fail(500,…)`。契约详解见上文「图片语义检索」 |
| POST | `/api/images/embeddings/rebuild` | ADMIN/EDITOR | 无 body（09-15 img-semantic-search 新增） | `data = {total, success, failed}`（全量重建图片向量：先物理清旧向量再插，**幂等**；单图失败不阻断整体、原因见日志）。VIEWER 调用 403 |
| POST | `/api/images/upload` | ADMIN/EDITOR | multipart `file` + `projectId?`（可空 = 全局图库）+ `tags?`（同名多值或单值内逗号分隔均可） | `{image}`（含 `dedupeHit` 与 `tags`）；类型限 png/jpg/webp，≤10MB（`IMAGE_MAX_UPLOAD_MB`），超限 `R.fail(400)` |
| DELETE | `/api/images/{id}` | ADMIN/EDITOR | — | `{ok:true}`；被封面/插图引用时 `R.fail(400, 提示引用方)`；删记录 + 图床对象 + **标签行物理清** + **向量行物理清**（09-15 img-semantic-search：防残留向量命中已删图） |
| POST | `/api/images/generate-text` | ADMIN/EDITOR | `{projectId?, prompt, size?, n?, tags?[]}`（`@Valid` DTO；n 1~4 默认 1） | **S10 起响应为数组** `{images[]}`：n 张候选逐张入库（后端循环 n 次单张调用，单张失败跳过，全部失败 `R.fail(500)` 含候选模型错误明细）；每张含 `genModel`/`genSize`/`dedupeHit`/`tags` |
| POST | `/api/images/generate-from-image` | ADMIN/EDITOR | `{projectId?, refImageId, prompt, size?, n?, tags?[]}`（`@Valid` DTO） | **S10 起响应为数组** `{images[]}`（同上）；provider 不支持 edits 时 `R.fail(500, 明确提示)` |
| POST | `/api/images/{id}/regenerate` | ADMIN/EDITOR | —（S10 新增） | `{images[]}`（1 张）：用源图 prompt/gen_size 重新生成**新图**（不覆盖源图）。源图须 `source∈{ai-text2img,ai-img2img}` 且 prompt 非空，img2img 复用源图 `ref_image_id`（参考图已删则 400）；**09-13 起新图继承源图标签** |
| PUT | `/api/images/{id}/tags` | ADMIN/EDITOR | `{tags:[...]}`（**全量覆盖**语义，空数组 = 清空；单项 1~50 字符，超长 400） | `{ok:true, tags[]}`；图片不存在 `R.fail(400)`（防写孤儿标签行） |
| POST | `/api/images/tags/batch` | ADMIN/EDITOR | `{ids:[...], tags:[...], action:"add"\|"remove"}`（逐张执行，全部幂等） | `{ok:true}`；`ids` 空 `R.fail(400)`；`tags` 空 `R.fail(400)`；action 非 add/remove `R.fail(400)` |
| GET | `/api/projects/{id}/images` | 三角色 | — | `{images[], coverImageId, bodyImageIds[], coverImage?, bodyImages[]}`。**S10 语义改写**：`images` 从全量图库收缩为**当前版本引用的图**（封面+插图）；新增服务端解析的 `coverImage`（对象含 url）/`bodyImages`（按 `bodyImageIds` 顺序）。全量图库浏览改走 `GET /api/images` 分页接口 |
| POST | `/api/projects/{id}/images/{imageId}/cover` | ADMIN/EDITOR | — | `{ok:true}`（`version.cover_image_id`）；重复选同一张幂等 |
| POST | `/api/projects/{id}/images/{imageId}/body` | ADMIN/EDITOR | `?action=add/remove` | `{ok:true}`（增删 `version.body_image_ids`）；重复添加幂等 |
| POST | `/api/projects/{id}/illustration-suggestions` | 三角色 | `{tags?[], minScore?}`（09-15 article-auto-illustrate 子C 新增）；`tags` 为**标签 AND 预过滤**（同 `GET /api/images`）；`minScore` null → `AI_IMAGE_MIN_SCORE`（默认 0.3），须在 [0,1] 否则 400 | `data = [{anchorKey, anchorIndex, headingPath, anchorText, candidates[]}]`，`candidates` 为 `ImageSearchHit`（同 `/api/images/search`，按 score 降序）。**零副作用**：只读正文 + 图库，**不修改 `content_md` / `body_image_ids`**。无候选的锚点不出现在结果中（不报错）；单锚点检索失败仅跳过该锚点（其余照常返回）。无当前版本 → `R.fail(400,"尚未生成正文版本，无法生成配图建议")`；正文空 → `R.fail(400,"正文为空，无法生成配图建议")`；项目不存在 → `R.fail(400,"项目不存在")`。契约详解见下文「配图建议」 |
| POST | `/api/projects/{id}/illustration-suggestions/dismiss` | ADMIN/EDITOR | `{anchorKey}`（09-15 article-auto-illustrate 子C 新增） | `{ok:true}`；写 `sparkora_illustration_dismiss`（`UNIQUE(version_id, anchor_key)`），**幂等**（重复忽略不报错、不重复插入）。`anchorKey` 空/超长(>200) → `R.fail(400)`；无当前版本 → `R.fail(400)`。VIEWER 调用 403 |

- 图片访问：**图床公网 URL**（`url` 字段，由 `storage_key` 实时拼）。`/images/**` 静态映射已删除（S6 本地不留）。
- **缩略图交付（S10）**：列表/网格用 `thumbUrl`（七牛 `imageView2/2/w/360/format/webp`，交付层转换零转码成本）；大图预览、正文插入、wenyan 拉图、公众号发布均用原图 `url`。非七牛图床实现降级 `thumbUrl=url`（`ObjectProvider` 可选注入，`ImageStorage` 接口不掺七牛特性）。
- 文生图/图生图返回的 axonhub URL **必须转存图床**（临时 URL 会过期），转存失败则该次生成报错（不留死链）。
- 请求体数字字段（`projectId`/`refImageId`）统一健壮解析：兼容数字与字符串形式（前端路由参数为字符串）。
- **S6 起 `complete-images` 接口已删除**（配图并入预览，不再有「完成配图」状态推进）。

---

## 7. 配图建议（2026-09-16，09-15 article-auto-illustrate 子C）

把图库从「手动选图」升级为「**系统建议、用户定夺**」：正文生成后按段落语义检索图库，产出配图**建议**。

> **硬约束（不可违背）**：系统**只产出建议，绝不自动写入**。配图进入正文的唯一路径是用户在预览页显式操作（单张「插入到此段」/ 整组「全部采用」）。**不存在任何自动插入开关**（无 `AUTO_ILLUSTRATE_ENABLED` 之类配置），从设计上排除无人值守自动配图。生成建议本身**零副作用**：不写 `content_md`、不写 `body_image_ids`。

### 7.1 锚点切分（`AnchorExtractor`，纯静态可单测）

- 按 ATX 标题（`##`/`###+`）分段：每个标题到下一个标题之间为一个锚点；标题前的前言也算一个锚点（`headingPath` 为空串）；`#` H1 视为文章标题（不产生锚点、不计入正文）。`###` 挂到最近的 `##` 下（`headingPath` 形如「续航实测 > 高速工况」）。
- 无标题时（罕见）退化为按空行切分段落，每段一个锚点。
- **跳过**：纯列表段落（非空行全部是 `-`/`*`/`+`/`1.` 列表项）、引用块行（`>`）、代码块（``` 围栏内）、图片/链接-only 段落（避免给配图建议区自己推荐）、去空白后 **< 30 字**的过短段落。
- `text` 剔除 markdown 标记（标题符号/加粗/行内代码；链接保留文字），单空格连接。
- **上限** `AI_ILLUSTRATION_MAX_ANCHORS`（默认 5），保序取前 N 个（优先靠前段落，避免配图过密）。
- **锚点指纹 `anchor_key`** = `headingPath` + 归一化（剥标记 + 去全部空白）文本前 80 字符的 **sha256 前 12 位 hex**。用指纹而非序号：正文编辑后序号会漂移，忽略记录会错位到别的段落；指纹在正文未编辑时稳定，纯格式调整（加粗/换行）不改变指纹。

### 7.2 建议生成（`IllustrationSuggestionService.suggest`）

1. 取项目当前版本（`current_version_id`）→ 无版本/正文空 → 400（见接口表）。
2. `AnchorExtractor.extract(contentMd, maxAnchors)`。
3. 过滤**已忽略**锚点（按 `version_id` 查 `sparkora_illustration_dismiss` 的 `anchor_key` 集合）。
4. 逐锚点调 `ImageEmbeddingService.searchImages(anchorText, topN=AI_ILLUSTRATION_TOP_N, minScore, tags)`（图片语义检索；串行 ≤5 次，无并发复杂度）。**单锚点失败仅 warn 跳过**，其余照常返回（图库/模型偶发失败不应让整页建议不可用）；门槛以下无候选 → 该锚点不出现。
5. 组装 `{anchorKey, anchorIndex, headingPath, anchorText, candidates[]}`。

- **建议候选不落库**：建议是「当前正文 + 当前图库」的**派生视图**，按需重算且结果稳定（检索确定性 + 无随机）；落库只会引入「建议陈旧」问题。只有用户的「忽略」决策需要持久化。
- **可重算/幂等**：同一版本同一正文 + 同一图库 → 相同结果；正文变更后结果随锚点变化（符合预期）。

### 7.3 「采用」的写入（用户批准后，唯一写入路径）

采用必须**两处都写**（2026-09-16 勘察修正）：

1. **编辑器插入 markdown `![](图床原图URL)` 到锚点位置**（`MarkdownEditor.insertMdAtAnchor(headingPath, text)`：按标题文本**首次出现**定位插到该标题行之后；找不到标题则**退回光标处**，保证不丢内容）——保证**真正渲染**；
2. **调既有 `POST /api/projects/{id}/images/{imageId}/body?action=add`** 登记 `body_image_ids`——保证**发布页「插图 N 张」计数正确 + 图片受删图引用保护**。

二者均幂等（`addBodyImage` 幂等；重复插入 markdown 用户可见可自行编辑）。不新增关联模型。

### 7.4 「忽略」的语义

- 「忽略此段」→ `POST /{id}/illustration-suggestions/dismiss` body `{anchorKey}` 写 dismiss 表；后续生成建议该锚点被跳过（不再反复打扰）。
- **不提供「取消忽略」的 UI**（非目标）；如需恢复，删表记录即可。
- 忽略记录与版本耦合（`version_id + anchor_key`），版本切换后不跨版本（符合语义：不同版本正文不同）。

### 7.5 可关闭（R6）

- `AI_ILLUSTRATION_SUGGEST_ENABLED`（默认 `true`）→ `sparkora.ai.illustration-suggest-enabled`：关闭后 `suggest` 直接 `R.fail(400,"配图建议功能已关闭")`，**不产生建议、不调 embedding**。
- **关闭的是「建议的生成」，与 R3「禁止自动写入」是两件事**：本开关关闭后系统仍然不会自动插入任何配图（系统本就无自动写入能力）。**不存在**任何自动插入开关。

### 7.6 与「AI 不写图」约束的边界（R5）

- **保留** `VersionService` 的仿写 prompt 禁图片约束与 `stripImages` 二次清洗——AI **仍不生成图片占位**（避免 AI 编造必 404 的图 URL、且无法保证与图库一致）。
- 配图由「系统建议 → 用户批准」在生成后补入，与「AI 不写图」不冲突；本项目**不存在**「AI 写占位标记 → 系统替换」方案。

### 7.7 已知债务（本任务不修复，记录在案）

- `body_image_ids` **不参与渲染**：`PreviewService.buildMarkdown()` 的 `bodyImageUrls` 参数完全未被使用，正文插图落点只由 `contentMd` 中的 `![](url)` 决定。
- **手动插图（预览页图库/AI 生图面板）只写 markdown、不登记 `body_image_ids`**（前端 `insertBodyImage` 仅调 `editorRef.insertMd()`）；仅「智能建议采用」两处都写。历史 34 个版本中 4 个 `body_image_ids` 非空且正文 `![` 出现 0 次，两者本就脱节。
- 修复方向是「`body_image_ids` 改为基于正文解析」，波及 `delete` 引用保护、`projectImages`、发布页计数，超出本任务范围（用户选择「markdown + 登记」双写，非大规模修复）。
- 另注：`ImageService.modifyBodyImage` 清空 `body_image_ids` 时用 `updateById`（MyBatis-Plus `NOT_NULL` 策略）会把 `null` 跳过，导致**移除最后一张插图后字段不清空**（`add` 正常）。既有缺陷，与本任务无关。

---

## 8. 页面职责（2026-08-30 调整；2026-09-03 S6 配图并入预览；2026-09-06 S10 检索/生成升级；2026-09-13 image-tags 标签能力）

- **图库独立页 `/images`**（`ImageLibrary.vue`，TopBar 入口）：上传、浏览、删除（ADMIN/EDITOR）。**S10 起**：筛选（来源下拉/关键字 300ms 防抖/项目）全部走服务端分页接口（size=24，`el-pagination` 翻页）；网格缩略图走 `thumbUrl`（imageView2/webp），点开大图预览用原图；上传内容哈希命中时提示「复用」；AI 来源图卡提供**一键重生成**；**AI 生图抽屉**（文生图/图生图，EDITOR 及以上；图生图从当前列表选参考图；n(1/2/4) 张候选生成，`projectId` 传空 = 全局图库，产物即进图库）。**UI 重设计（S10+）**：卡片瘦身——默认仅缩略图+来源小标，元数据/操作入 hover 浮层（移动端常显文件名行+「···」更多操作）；工具条两段式（主操作|浏览控制）；大图预览支持当前页连续浏览；筛选状态 chip 条（单独清除/一键全清）；批量选择模式（多选→单次确认删除，被引用图后端拒绝逐张提示）；舒适/紧凑密度切换（localStorage 记忆）。素材管理归图库，不在文章流程内。
  - **标签能力（09-13 image-tags）**：工具条「上传标签」预选控件（multiple allow-create，上传与 AI 生图共读，不持久化）；工具条标签筛选下拉（数据源 `GET /api/images/tags`，与 chip 条联动，可与其他筛选组合）；卡片 hover 层/移动端常显区展示标签，**点标签直接触发筛选**；卡片 hover 操作区/移动端 ··· 菜单「编辑标签」→ 对话框全量覆盖（`PUT /{id}/tags`）；批量选择态「打标签」→ 对话框（标签多选 + add/remove 单选 → `POST /tags/batch`）。**R5 交互修复**：AI 抽屉文生图/图生图 prompt 拆为独立 ref（切换 tab 不再互相污染）；参考图选择弹窗独立数据源 + 页内搜索（300ms 防抖）+ 分页（不再只看主列表第一页）；来源标签补「比亚迪新闻」（`byd-news`，红色点）。
  - **主题分类筛选与来源展示（09-15 img-classify）**：标签筛选改 **multiple**（`tagFilter` 由字符串改数组，多标签 **AND**），chip 条**逐个展示可单独清除**（点已选标签再点即取消）；下拉按 `/` 前缀用 `el-option-group` **分组展示**（`主题` / `年份` / `其他`）；卡片 hover 层（移动端常显行）显示**来源行**「来源：<新闻标题> · <日期>」，点击跳新闻原文（走 `GET /api/images/{id}/source`，页内批查懒加载，非新闻图不显示）；支持外部入口 `/images?tag=主题/销量`（预置筛选，供新闻卡片点主题标签跳转）。**路由与筛选双向同步**：挂载时按 `route.query.tag` 预置筛选；chip 单独清除 / 全清 / 点卡片标签后 `router.replace` 把 URL 同步为当前选中（`syncRouteTag`）——否则清掉 chip 后 URL 仍留旧 tag，再次从新闻页点同一主题时 query 未变、vue-router 判定重复导航、watch 不触发，出现「点了没反应」。
  - **语义检索能力（09-15 img-semantic-search，后端就绪）**：图库图片已完成向量化（`sparkora_image_embedding`，与 car/kb/news 三域同向量空间），可被 `POST /api/images/search` 用自然语言检索（如「销量海报」）；支持叠加标签 AND 预过滤在「`主题/销量` + `年份/2026`」范围内语义搜。**本任务纯后端**（前端检索入口与自动配图 UI 由配图建议/问答配图承载）。
- **新闻知识页封面与主题标签（09-15 img-classify）**：`NewsKnowledgePanel.vue` 封面 URL 取 `coverImageUrl || resolveUrl(imageUrl)`（图库图优先，官网原始 URL 回退，未同步封面不报错）；卡片/详情展示**主题标签**（`news.themes`，后端用同一分类器按标题重算，不查图库避免 N+1），**点标签跳图库并按 `主题/<名>` 筛选**。详见 [knowledge/news.md](knowledge/news.md)。
- **预览步配图面板（项目向导预览步）**：工具栏「配图」面板提供**图库插入**（**S10 起走分页接口 + 来源/关键字筛选 + 触底加载**，选图插入正文光标处/设封面）与 **AI 生图**（文生图/图生图，**S10 起可一次生成 n(1/2/4) 张候选，逐张插入/设封面/重生成**；产物进图库后展示候选列表）两种来源。图不够时引导去图库页。车型库图片接入**预留**（暂不开发）。详见 [preview.md](preview.md)。
- **预览页「智能建议」tab（09-15 article-auto-illustrate 子C）**：配图抽屉第 3 个 tab（`imgTab='suggest'`）。顶部：相似度门槛（默认 0.3）+ 标签预过滤多选（AND，数据源 `GET /api/images/tags`）+「生成建议/重新生成」按钮 + 提示「系统只给建议，点采用才写入正文」。按锚点分组卡片：锚点标题（`headingPath` 或「开头段落」）+ 锚点文本摘要 + 候选网格（缩略图/相关度百分比/标签）。每张候选「插入到此段」；每组「全部采用」/「忽略此段」。**空态三态**：未生成（引导点生成）/ 生成后无候选（提示调低门槛、换标签或先去图库补图）/ 全部被忽略。**建议不自动触发**——须用户点「生成建议」（避免打开抽屉即产生 embedding 调用）。移动端单列、触控目标 ≥44px。

---

## 9. 关键实现路径

- 后端：`web.controller.ImageController`、`service.ImageService`（入库/去重/派生/删图）、`service.ImageTagService`、`service.ImageEmbeddingService`、`service.IllustrationSuggestionService`、`image.embed.ImageEmbeddingTextBuilder`、`news.classify.NewsImageClassifier`、`storage.ImageStorage`（抽象）+ `service.QiniuService`（实现）、`mapper.ImageEmbeddingMapper`/`ImageTagMapper`、`ImageTagBackfillRunner`(`@Order(10)`)/`ImageEmbeddingBackfillRunner`(`@Order(20)`)。
- 前端：`views/ImageLibrary.vue`、`views/project/StepPreview.vue`（配图面板 + 智能建议 tab）、`components/MarkdownEditor.vue`（`insertMd`/`insertMdAtAnchor`）、`api/index.js`（`imageApi`）。
- 表：`sparkora_image_asset`、`sparkora_image_tag`、`sparkora_image_embedding`、`sparkora_illustration_dismiss`、`sparkora_article_version`（`cover_image_id`/`body_image_ids`）、`sparkora_news.cover_image_id`。

---

## 10. 已知限制

- 标签变更不触发实时重嵌（`source_text` 与当前标签漂移；用重建接口修正）。
- `body_image_ids` 不参与渲染 + 手动插图不登记（见「已知债务」）。
- 车型库图片接入预留（暂不开发）。
- 非七牛图床实现下 `thumbUrl` 降级为原图 URL。
