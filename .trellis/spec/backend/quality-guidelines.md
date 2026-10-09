# Quality Guidelines

> Code quality standards for backend development.

---

## Overview

本项目质量约定聚焦两条最容易出事的边界：**验证/测试不得触碰生产数据**，以及**不可逆操作必须可回滚**。

---

## Forbidden Patterns

### Don't: 验证/测试阶段直接修改既有生产数据行

**Problem**（09-15 article-auto-illustrate 真实事故）：

```bash
# 为验证「正文为空 → 400」用例，直接改生产行
psql ... -c "UPDATE sparkora_article_version SET content_md='' WHERE id=25"
# 还原时语句写错（CREATE TEMP TABLE t_bak 跨 psql 会话无效）
# → version 25 的 content_md(785 字) 永久丢失，全库无备份/PITR/WAL 可恢复
```

**Why it's bad**：生产库无 PITR、无 WAL 归档、无转储时，一次 `UPDATE` 写错就是永久数据丢失。上例中「临时置空再还原」的写法跨进程/跨会话失效，且验证者往往不会逐行核对还原结果。**验证数据的成本远高于它带来的信心。**

**Instead**：

| 需求 | 正确做法 |
|---|---|
| 「正文为空 → 400」类边界 | **JUnit + Mock** 覆盖（`IllustrationSuggestionServiceTest` 先例），不碰 DB |
| 需要真实端到端路径 | 新建**自己的**临时数据（project/version），用完按 id 精确删除并复核总数 |
| 需要临时改状态 | `BEGIN` + 改 + `ROLLBACK`，且**仅针对自己新建的行** |
| 只读核对 | `SELECT` 不受限，鼓励使用 |

- 核对/验证结束时必须复核总量（如 `versions/projects/images/dismiss` 计数）恢复到基线，并**显式声明未修改既有生产行**。
- 结构变更只允许新增 Flyway 迁移脚本（`db/migration/V<n>__<desc>.sql`），不手工 DDL、不改已应用脚本。

---

## Required Patterns

### Convention: 不可逆副作用必须与主流程隔离，且失败可回滚

- **best-effort 写入**（向量/派生缓存/埋点）若在调用方事务内失败，会让 PostgreSQL 把整个事务置 aborted，`catch` 无法挽回 → 必须走 `REQUIRES_NEW` 独立事务（见 database-guidelines.md「向量/派生数据写入需与调用方事务隔离」）。
- **多步写入**（如「插正文 markdown + 登记版本插图关联行」）必须**定义失败顺序**：先做可失败的写、后做不可回滚的写；或对已完成部分显式回滚。09-15 先例：改为「先登记（可失败）→ 再插正文」，插入未成功则回滚登记，并跳过「该图此前已登记」的误删。
- **返回值语义必须可判定**：封装函数（如 `insertMdAtAnchor`）的返回值要能区分「成功（含降级路径）」与「未执行」——`false` 同时表示两种情况会让调用方无法安全回滚。

---

## Testing Requirements

- 纯函数/算法优先抽为**无 Spring 依赖的静态方法**并单测（先例：`NewsImageClassifier`、`AnchorExtractor`）。
- **契约级硬约束要有断言**：如「零副作用」用反射断言依赖不含写组件 + 逐方法 `never()`（`IllustrationSuggestionServiceTest`）。
- 前端字段名错误（record `imageId` vs 实体 `id`）**编译与后端单测都发现不了** → 采用类链路需真机点击或用 curl 打通 API 层（09-15 P0 先例）。

> **Warning（Spring 装配是 `mvn test` 的盲区，10-05-crawl4ai-transport Check 实测）**: 本仓**无 `@SpringBootTest`、无 `src/test/resources`**，
> `mvn test` 从不启动 Spring 容器。因此凡「Spring 只在真实启动时才会报错」的缺陷——`mvn test` 与编译**双双全绿**，只在部署/`./dev.sh` 启动时才爆。
> 最典型：一个 `@Component` 同时有「生产构造器 + 包级测试构造器」两个构造器，却**漏标 `@Autowired`** → Spring 无唯一构造器、也无默认构造器 →
> `BeanInstantiationException: No default constructor found`，应用启动即失败。
>
> **规避**：`@Component` 若有多构造器（含包级测试构造器），**必须显式** `@Autowired` 标注生产构造器——先例 `SerperSearchTool`/`TavilySearchTool`。
> 仅有一个构造器时不标注也安全；但只要为可测性加了第二个构造器，就必须补 `@Autowired`。
>
> **测试守卫**：为「可被容器实例化」这类装配契约补一个轻量 `AnnotationConfigApplicationContext` 探针测试（只 register 被测 bean + 其依赖），
> 无需 `@SpringBootTest`；先例 `FetchTransportWiringTest`（`com.sparkora.source.fetch`）。新增多构造器 `@Component` 时应照此加守卫，否则该缺陷仍会静默回潮。

### Convention: 同一逻辑值分散在「数据库列」与「JSONB metadata」两处时必须同源写入（10-05 U Check 实测）

**What**：当同一语义值（如内容分类 `category`）既存于实体的**普通列**（供 SQL 筛选 `WHERE category=?`）又写进 `vector_store.metadata`（供向量检索过滤）时，两条写入路径**必须同步**，否则会出现「检索能命中、按列筛选却查不到」的静默不一致。

**Problem（10-05-source-center-ui Check 真事故）**：`category='官方新闻'` 早期只写进 `vector_store.metadata` JSONB（并回填 metadata），但 `sparkora_news.category` 列始终为 NULL →
`GET /api/source-contents?category=官方新闻` 用 `eq("category", ...)` 查列 → **BYD 新闻全部漏掉**；而向量检索按 metadata 过滤又能命中。单测（只测写 metadata 的分支）与 `npm run build` 全绿，缺陷只在按列筛选的真实查询里暴露。

**规避**：
- 写入侧：凡「列 + metadata 双载体」的字段，在**同一 upsert 路径**同时写两处——先例 `SourceCollectService`（列）与 `SourceDocService`（metadata）；BYD 侧原先只写 metadata，已补 `NewsService.upsertOne` 写列。
- 迁移侧：回填脚本要**同时**覆盖列与 JSONB——先例 `V14`（回填 metadata）遗漏了列，须由 `V15` 补回填 `sparkora_news.category`。
- 排查口径：出现「检索有、列表筛选无」时，先比对该字段在**列**与 **metadata** 两处是否都存在。

### Convention: 接入外部站点采集前必须真机验证「可达性」，HTTP 200 不等于可采（10-09-cpca-gasgoo-collection 实测）

**What**：为站点写采集配置（`sparkora_source_channel.parse_rules`）前，先对**目标 URL 真机拉一次**，判定三件事，再定选择器与通道。

**Problem（10-09 实测）**：
- **JS 渲染站点**：乘联会列表页原始 `/html` 的 `<a>` **无 href**（链接由 JS 注入）；只有 Crawl4AI **`/crawl` 的 `cleaned_html`** 才有真实链接。即 `SiteSourceClient.list` 靠 `href` 取条目时，必须走「渲染后 HTML」通道，否则静默 0 条。
- **WAF 验证码站点**：盖世 `/qcxl/article/*` 等返回 **HTTP 200 + 1543B「腾讯验证码挑战页」**——状态码是 200，但正文是验证码脚本（`TencentCaptcha`/`ssl.captcha.qq.com/TCaptcha.js`），**无业务数据**；Crawl4AI 也无法过（需人工交互）。若只按「直连 200」判定可达，会写出一个永远采不到数据的源。
- **图片过滤裸词误杀**：盖世海报 CDN 是 `moblogo/News/UEditor/…`，若 deny 用裸子串 `logo` 会**误杀全部海报**；须用精确子串（`companylogo`、`/logo/`）。

**规避**：
- 判定可达性看**正文特征**而非状态码：抓回后检查是否含预期选择器/关键字段；含 `captcha`/`TencentCaptcha` 即视为不可采。
- JS 渲染站点列表：用 `Crawl4AI /crawl` 的 `cleaned_html`（`Crawl4aiClient.fetchHtml` 已改走此路径）。
- 站点确实不可采时：**不要保留一个永远失败的信源栏目**（运维面板会误导）；退役该栏目并如实记录限制（先例 `V18` 退役盖世排行）。
- 图片 deny/allow 用足够精确的子串，避免误杀（先例 `SiteSourceClient` 默认 deny）。

---

## Code Review Checklist

- [ ] 是否存在直接改生产数据的验证脚本/SQL？若有，改为单测或临时数据。
- [ ] 新增写入是否与调用方事务隔离（best-effort 场景）？
- [ ] 多步写入的失败顺序是否安全、可回滚？
- [ ] 前端消费的字段名是否与后端 DTO/record 完全一致（不靠类型系统兜不住的假设）？
- [ ] 涉及不可逆操作的改动，是否记录了回滚步骤？
- [ ] 新增/改动的 `@Component` 若有多构造器，是否显式 `@Autowired` 标注生产构造器（并加装配守卫测试）？
- [ ] 新增外部站点采集配置前，是否真机验证可达（非仅凭 HTTP 200；JS 站点走渲染 HTML `cleaned_html`；WAF 验证码站点不保留死栏目）？
