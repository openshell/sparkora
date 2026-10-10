# Flyway 版本化迁移

> 建表/改表的唯一入口。`spring.flyway.*` 见 `src/main/resources/application.yml`。

## 约定

- 命名：`V<n>__<desc>.sql`(版本号递增，两个下划线)，如 `V1__baseline.sql`、`V2__article_jsonb.sql`。
- **已应用的迁移脚本不得修改**(checksum 校验，`validate-on-migrate: true` 会阻止启动)；修正只能新增更高版本脚本。
- 结构变更三处同步：新增 `V<n>__<desc>.sql` + 对应 entity/mapper + `docs/spec/**` 对应模块字段级表格。
- 迁移脚本**无需**再写 `IF NOT EXISTS` 兜底幂等——Flyway 按版本只执行一次；但既有基线 V1 保留原幂等写法(历史结构 + 对既有库标记跳过的语义)。
- 不使用 `DO $$` 块(Spring/Flyway ScriptUtils 不支持 dollar-quote，会按 `;` 截断)；列搬数用「补列 → UPDATE 搬数据 → DROP 旧列」三条单语句。
- 破坏性 `DROP TABLE` 属例外（仅限退役类迁移，如 `V9` 旧向量表），须在代码退役 + 全绿 + 对拍后、作为任务最后一步执行。
- `clean` 保持 Flyway 10 默认禁用(`cleanDisabled=true`)，**不得**开启；迁移中不执行破坏性 DROP(索引类型切换的 `DROP INDEX IF EXISTS` 先例，与退役类迁移的 `DROP TABLE IF EXISTS` 例外，见上)。

## V1 基线

`V1__baseline.sql` = 改造前 `src/main/resources/db/schema.sql` 全文(逐字节固化，勿改)：

| 库状态 | Flyway 行为 |
|---|---|
| 既有库(已有表、无 `flyway_schema_history`) | 建历史表 → 插 `BSLN`@1(=`baseline-on-migrate` + `baseline-version=1`) → 跳过 V1 |
| 空库 | 执行 V1 全量 DDL/seed/回填 → 插 `V1` 记录 |
| 再次启动 | 历史表已存在、无 pending → 空跑 |

## 后续变更入口

- `V2__article_version_image.sql`（P1-⑦）：`body_image_ids` 逗号列规范化为 `sparkora_article_version_image` 关联表（建表 + 回填 + DROP 旧列，单迁移内完成）。
- `V3__embedding_model.sql`（P1-⑧）：4 张向量表加 `embedding_model VARCHAR(100)`，回填存量行 = 实际配置模型（Flyway placeholder `${embeddingModel}` ← `spring.flyway.placeholders.embeddingModel` ← `AI_EMBEDDING_MODEL`）；写入盖名、检索按当前模型过滤（换模型后旧行自动失效，不静默混空间）。
- `V4__content_description_and_brief_reasoning.sql`（10-02-brief-reasoning-maxtokens）：项目表 `extra_info` → `content_description`（补列 → UPDATE 搬数 → DROP），删除 `keywords`/`remark`；brief 表加 `research_reasoning TEXT`（澄清阶段 AI 思考过程）。单语句（无 `DO $$` 块）。
- `V5__pgvector_store.sql`（10-03 E1）：建 Spring AI PgVectorStore 单表 `vector_store`（`id uuid / content text / metadata json / embedding vector(1024)`，HNSW cosine + metadata GIN）；4 域旧向量表由 `VectorStoreBackfillRunner` 搬入（**回填完成后该 runner 已随 10-03 E6 退役，旧表由 V9 删除**）。
- `V6__kb_normalize.sql`（10-03 E3）：`sparkora_kb_doc` 加 `source`/`effective_from`/`effective_to`（可空，向后兼容）；存量 `domain` 收敛到受控词表（精确匹配否则「通用」）；建标签关联表 `sparkora_kb_doc_tag`（镜像 `sparkora_image_tag`）。
- `V7__rename_car_doc_to_chunk.sql`（10-03 E4）：`sparkora_car_doc` → `sparkora_car_chunk`（块语义，仅内部命名）；索引 `idx_car_doc_model` → `idx_car_chunk_model`。旧向量表 `sparkora_car_doc_embedding` 不动。
- `V8__embedding_cache.sql`（10-03 E5）：建内容寻址嵌入缓存 `sparkora_embedding_cache`（`content_hash CHAR(64)` + `embedding_model` + `embedding TEXT`（pgvector 字面量，不做 ANN） + `created_at`，主键 `(content_hash, embedding_model)`）；相同文本同模型复用向量、换模型天然 miss。
- `V9__drop_legacy_embedding_tables.sql`（10-03 E6）：**物理删除**旧 4 张向量表 `sparkora_{car_doc,kb_chunk,news_doc,image}_embedding`（单一只真源 = `vector_store`）。**不可逆**，仅在 E1–E5 + E6 代码退役全绿后执行。注意 `sparkora_car_doc_embedding` 表名未随 E4 重命名（E4 只改主表），按旧名删除。
- `V10__cognitive_layer.sql`（10-03-gen-cognitive-redesign C1）：brief 表新增 `clarify_session TEXT`（多轮澄清会话 JSON）、`task_brief TEXT`（结构化意图契约 JSON）、`clarify_status VARCHAR(20)`（ASKING/CONVERGED/ABORTED）；部分唯一索引 `uq_brief_clarify_asking` 约束同项目至多一条 ASKING 会话。
- `V11__writing_blueprint.sql`（10-03-gen-cognitive-redesign C3）：brief 表新增 `writing_blueprint TEXT`（写作蓝图 JSON：thesis/argumentStructure/evidenceMap/narrativeArc/constraints/gaps）、`blueprint_status VARCHAR(20)`（人工评审门 REVIEWING/CONFIRMED）、`blueprint_quality TEXT`（质量信号 JSON）。评审门不新增项目状态位，用 brief 侧 `blueprint_status` 表达。
- `V12__widen_web_provider_order.sql`（10-04-serper-provider A）：`sparkora_setting.web_provider_order VARCHAR(20)→VARCHAR(50)`（三源串 `TAVILY,SERPER,SEARXNG`=21 字符超原宽）。
- `V13__source_registry.sql`（10-05-source-crawl-base B）：建信源注册表 `sparkora_source` + 栏目 `sparkora_source_channel` + 采集任务 `sparkora_source_job`；`sparkora_news` 增可空 `source_id`/`channel_id`/`category`（通用信源复用 `sparkora_news*` id 空间，见父 design §2.5.1）。
- `V14__news_source_metadata.sql`（10-05-source-domain-retrieval E）：`vector_store` 中 `domain=NEWS` 行幂等补 `sourceType=byd-news`/`category=官方新闻`/`publishDate`（只补缺键、`id`/`embedding` 不动 → 零重嵌）。
- `V15__news_byd_category_backfill.sql`（10-05-source-center-ui）：把存量 BYD 行（`source='byd-news'` 或 `source_id IS NULL`）补 `sparkora_news.category='官方新闻'`（幂等，仅回填列值）。
- `V16__news_source_metadata_url_tier.sql`（10-09-source-metadata-completion M）：`vector_store` 中 `domain=NEWS` 行幂等补 `url`（经 `refId→sparkora_news_doc.news_id→sparkora_news.url` 派生）与 `authorityTier`（byd-news 固定 `official`；通用信源经 `news.source_id→sparkora_source.authority_tier` 派生）；只 `metadata || jsonb`、`id`/`embedding` 不动 → 零重嵌；补全 F-R3 跨源同 URL 去重 / F-R4 权威分档的生产侧数据。
- `V17__seed_cpca_gasgoo_sources.sql`（10-09-cpca-gasgoo-collection）：预置乘联会 / 盖世汽车两源 + 4 栏目（`INSERT ... WHERE NOT EXISTS` 幂等，不改表结构，默认 `enabled=false`）；`parse_rules` 含 `listRows`/`rowCells`/`imageDeny` 增量字段。回滚=删除预置行/禁用源。
- `V18__retire_unreachable_gasgoo_ranking_channel.sql`（10-09-cpca-gasgoo-collection 收口）：逻辑删除 V17 预置的盖世「销量排行」栏目——其详情页（`/qcxl/article/*` 等）由腾讯 WAF 验证码拦截（HTTP 200/1543B，Crawl4AI 亦无法渲染）真机不可采。`listRows` 解析能力保留在代码中（已单测），未来接入可达排行源可复用。幂等可重入。
- `V19__align_gasgoo_sales_channel.sql`（10-10-gasgoo-sales-channel）：把 V17 预置的盖世 C-110 栏目正名为「车企销量」、分类对齐为「销量数据」（`/auto-news/C-110` ≡ `/sales/C-110`，即各车企单独发布的销量稿类目）；`UPDATE ... FROM` 显式关联 `c.source_id = s.id`，幂等（重复跑 UPDATE 0）。不改 `list_url`/选择器/表结构。
- 后续结构变更一律新增 `V20+` 脚本，不再触碰 V1~V11。
- **JSON 存 TEXT 为有意约定**（P1-⑦ 复核裁定，不转 JSONB），理由见 `.trellis/spec/backend/database-guidelines.md`「JSON 存 TEXT 是有意约定」。
- **Boot 4 注意**：Flyway 自动配置已从 `spring-boot-autoconfigure` 拆到独立 `spring-boot-flyway` 模块，pom 必须引 `spring-boot-starter-flyway`（+ 显式 `flyway-database-postgresql`），否则迁移**静默不执行**（详见 database-guidelines.md「Boot 4 下只引 flyway-core」）。
