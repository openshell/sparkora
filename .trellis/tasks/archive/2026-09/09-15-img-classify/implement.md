# 执行计划：图片主题分类与来源追溯地基（子A）

## 实施顺序

### M1 数据层（schema + 实体）

- [x] 1. `schema.sql` 追加 09-15 img-classify 段（幂等）：
  - `ALTER TABLE sparkora_image_asset ADD COLUMN IF NOT EXISTS source_ref VARCHAR(200);`
  - `CREATE INDEX IF NOT EXISTS idx_image_asset_source_ref ON sparkora_image_asset(source_ref);`
  - `ALTER TABLE sparkora_news ADD COLUMN IF NOT EXISTS cover_image_id BIGINT;`
- [x] 2. `ImageAssetEntity` 加 `private String sourceRef;`；`NewsEntity` 加 `private Long coverImageId;` + 非持久化 `coverImageUrl`

### M2 分类器（独立可单测）

- [x] 3. 新建 `NewsImageClassifier`（纯静态、无 Spring 依赖）：
  - `THEMES`：有序 `LinkedHashMap<String, Pattern>`（design.md 词表）
  - `classifyThemes(String title)` → `List<String>`（保序，0~n）
  - `toTags(String title, LocalDate publishDate)` → `List<String>`（`主题/x` + `年份/y`）
  - 边界：title null/空 → 空列表；publishDate null → 只出主题
- [x] 4. 用真实 167 条标题回归验证分类结果：
  - 从 DB 取全部标题，跑分类器，人工核对命中分布与误分类
  - 记录实测误命中与词表调整到本文件「实测词表调整」段
  - 至少确认：`detail63` vs `detail632` 类数字后缀匹配在回溯里精确；`获`/`发布` 误命中可接受

### M3 增量打标接入

- [x] 5. `ImageService.persistOrReuse` 落 `preset.getSourceRef()`；`saveExternalImage` 加 `sourceRef` 形参（向后兼容重载/可选）
- [x] 6. `NewsService.upsertOne`：
  - 调 `NewsImageClassifier.toTags(title, publishDate)` 得标签
  - 传 `sourceRef = newsId` 给 `saveExternalImage`
  - 拿到 asset id 后写回 `sparkora_news.cover_image_id`（仅当为 null 或指向不存在图时更新，避免覆盖已同步的）
  - 失败仅告警不阻断（沿用 09-13）
- [x] 7. `NewsService.list`/`get`：填充非持久化 `coverImageUrl`（由 `cover_image_id` 经 `ImageService` 取公网 URL；取不到留空），并返回分类主题（`classifyThemes(title)`）

### M4 存量回溯

- [x] 8. `ImageTagBackfillRunner` 增新闻分支：
  - 查 `source='byd-news' AND source_ref IS NULL` 的图（分批，避免全量内存）
  - 文件名正则 `detail(\d+)` 提取数字 → `news_id` **精确后缀匹配** `%detail<数字>`（用 `LIKE` 取候选后 Java 端精确比对，防 `detail63` 命中 `detail632`）
  - 反查新闻 → `classifyThemes` + 年份 → `mergeTags` + 回填 `source_ref` + 回填 `news.cover_image_id`
  - 幂等：重跑只补缺失；异常仅 warn；日志汇总「处理 X / 跳过 Y / 失败 Z」
- [x] 9. 验证：重启后日志显示回溯条数，DB 抽查标签与 `source_ref`

### M5 多标签 AND 查询

- [x] 10. `ImageService.list`：tag 参数改 `List<String>`，逐标签取 id 集求交集（空集直接返回空页）；`ImageController` 参数改 `List<String> tag` + 逗号拆分
- [x] 11. 验证：单标签行为不变；双标签 AND 正确；与 source/keyword 组合正确

### M6 来源追溯接口

- [x] 12. `ImageService.getSource(imageId)` + `ImageController` `GET /api/images/{id}/source`（三角色）：返回 `{sourceRef, news, imageUrl}`，非新闻图 `news:null`

### M7 前端

- [x] 13. `frontend/src/api/index.js`：`imageApi.list` tag 支持数组；新增 `getSource(id)`
- [x] 14. `ImageLibrary.vue`：
  - 标签筛选改 `multiple`，`tagFilter` 改数组，chip 条逐个展示可单独清除
  - 标签下拉按 `/` 前缀 `el-option-group` 分组（主题/年份/其他）
  - 卡片 hover 层加来源行（调 `getSource`，显示标题·日期，点击跳原文），主题标签点击即筛
- [x] 15. `NewsKnowledgePanel.vue`：封面 `coverImageUrl || resolveUrl(imageUrl)`；卡片展示主题标签，点击跳图库筛选
- [x] 16. `npm run build`

### M8 规格同步

- [x] 17. `docs/s0-spec.md`：§10 加 `source_ref` 字段/主题词表/多标签 AND/来源接口/命名空间；§7 加 `cover_image_id` 与封面接图库；页面职责段补图库多选筛选与新闻页标签

## 验证命令

```bash
mvn -q -DskipTests compile            # 后端
cd frontend && npm run build          # 前端
./dev.sh restart backend              # 重启触发回溯
./dev.sh logs backend | grep 回溯      # 看回溯日志
```

### 分类器回归（M2 必须）

```bash
# 取全部标题跑分类器，统计各主题命中数与无命中数
# 人工抽查：销量类标题是否全命中、无关注标题是否误命中
```

## 风险点与回滚

- **词表误命中**：最大风险。缓解：M2 强制真实标题回归 + 记录调整；误命中可接受阈值由回归结果判定。若某主题误命中严重，收窄正则即可（单点改动）。
- **数字后缀匹配精度**：`detail63` 误配 `detail632`——必须 Java 端精确比对，不能只靠 LIKE。
- **`cover_image_id` 回填覆盖**：新闻重同步时不得覆盖已有有效值（只在 null/失效时写）。
- **多标签 AND 性能**：标签数少（≤3）时交集规模小；id 集超 500 截断沿用 09-13 保底。
- **前端 tagFilter 类型变更**：从 `''` 改 `[]` 波及 `hasFilter`/`clearAllFilters`/`activeChips`/`locateInList`，需全量核对（09-13 这些函数已有一处遗漏记录）。
- 回滚见 design.md。

## 实测词表调整

M2 回归以真实 **167 条** `sparkora_news` 标题（`deleted=0`）跑分类器，逐条核对命中与无命中明细。

### 各主题命中数（最终词表）

| 主题 | 命中数 | design.md 预估 | 差异 |
|---|---|---|---|
| 销量 | 45 | 45 | 一致 |
| 出海 | **65** | 59（含车展重叠口径） | 补国别词后 +6 |
| 合作签约 | 18 | 18 | 一致 |
| 技术发布 | 22 | 22 | 一致 |
| 里程碑 | 25 | 25 | 一致 |
| 荣誉 | 22 | 24 | 略低（预估含重叠口径差异） |
| 财报ESG | 6 | 6 | 一致 |
| 车展上市 | 16 | 16 | 一致 |
| 社会责任 | 3 | 3 | 一致 |
| 无命中 | 21 | — | 见下 |

### 词表调整记录

1. **出海补国别/城市词**（design.md 草案只列区域与部分国别，漏了「比亚迪进入 <国> 市场」「登陆 <国>」类标题）：
   - 新增：`智利`（detail584）、`罗马尼亚`（582）、`瑞士`（574）、`尼日利亚`（575）、`柬埔寨`（580）、`贝宁`（579）、`加蓬`（591）、`首尔`/`韩国`（573）、`英国`（488）。
   - 新增：`国际化`（592「国际化进程再加速」——该标题「出口/海外/全球化」皆不命中，属出海语义）。
   - 回归确认：新增词无误命中（均为市场进入/海外事件标题）。
2. **宽泛词保留**（`荣/获/奖/榜`、`发布`）：逐条核对命中明细，8 条含「获」（获得/再获/荣获/获…测试牌照/同获）全部属荣誉语义；11 条含「发布」全部为产品/报告/技术发布，无无关误命中（如「发布会」类噪音）。**维持 design.md 原词表**。
3. **「榜」仅命中 1 条**（BrandZ 全球汽车品牌榜）——荣誉语义，保留。
4. **里程碑 `万辆`**：命中「8月销量…万辆」（销量+里程碑重叠）；语义上「销量破 X 万辆」确属产量里程碑，接受重叠（AND 筛选天然处理多标签）。

### 无命中 21 条（人工核对，均属词表外语义，不强行归类）

运输船/滚装船启航下线（42/43/58/73/125，实为出海物流但标题无国别词，可接受漏检）、
赛事/公益赞助（30/70/91/100）、赛车场/科普馆开业（33/82/87）、出行报告（117/127）、
梦想日/D-Talk（123/141）、智驾升级（76、「天神之眼」）、ACC 测试（136）、门店开业（142）、
可持续发展成果（64）、COP28（134）。多数命中「发布/亮相」等词表词，未被误归「其他」是符合 R1「无命中不打标签」设计的。

### 回归验证方式

- 单测：`src/test/java/com/sparkora/news/classify/NewsImageClassifierTest.java`（保序/重叠/边界/命名空间/词表固定）。
- 全量回归：临时 harness 读取 `sparkora_news` 导出的 167 条标题跑 `classifyThemes`，统计命中分布并导出逐条结果人工核对（harness 已删除，方法与结果如上）。

### 数字后缀精确匹配验证（M4）

存量回溯 157 张全部成功，DB 校验：
- `substring(file_name from 'detail([0-9]+)') <> substring(source_ref from 'detail([0-9]+)')` 的错配行 = **0**；
- `source_ref` 无对应 `sparkora_news.news_id` 的孤儿引用 = **0**；
- 同名 detail 数字重复的图片 = **0**（无 `detail63` vs `detail632` 误配）。

## 与 design.md 的偏离项（实施记录）

| # | design.md | 实施 | 理由 |
|---|---|---|---|
| 1 | 出海词表（不含国别扩展） | 补 `国际化` + 智利/罗马尼亚/瑞士/尼日利亚/柬埔寨/贝宁/加蓬/首尔/韩国/英国 | M2 回归发现「进入/登陆 <国> 市场」类标题漏检（design.md 明确「最终词表以回归结果为准」，见「实测词表调整」） |
| 2 | `source_ref` 增量写入未细化去重命中语义 | 去重命中已有图时**仅当已有 `source_ref` 为空才补写**（`ImageService.applyPresetSourceRef`） | design.md 明确「同图被不同新闻引用：source_ref 保留首次值（不覆盖）」；实现为条件 UPDATE 保证并发/幂等安全 |
| 3 | `GET /images/{id}/source` 响应示例含 `news:null` | 用显式 DTO `ImageSourceDTO` + `@JsonInclude(ALWAYS)` 强制输出 `sourceRef`/`news` 的 null | 全局 Jackson `default-property-inclusion: non_null` 会吞掉 null 键，违反「非新闻图返回 news:null」契约；record 显式注解是最小改动 |
| 4 | 前端 `api/index.js` 未提及数组序列化细节 | `imageApi.list` 在 API 层把 `tag[]` 拼成逗号单参数 | axios 1.x 默认序列化数组为 `tag[]=a&tag[]=b`，Spring `@RequestParam List` 不识别；后端 `splitTags` 两种传法都支持，在前端收敛保证联调可用 |
| 5 | 来源追溯行「懒加载或列表随带」二选一 | 列表加载后**页内批查**（`Promise.all` 并发，结果按当前页收敛） | 复用既有 `GET /{id}/source` 契约，无需改列表响应结构；页内 24 条并发可控，结果不跨页累积 |
| 6 | 新闻卡片主列表**只读** `resolveUrl(imageUrl)` | 列表/详情主题标签为**可点击**跳图库（`/images?tag=主题/<名>`） | prd R7 AC 明确要求「新闻卡片的主题标签可点击，点击后跳图库并按该主题筛选」，需 `ImageLibrary` 支持 `?tag=` 路由预置 |
| 7 | 未提及 10 条新闻无图库封面 | 保留官网 `imageUrl` 回退，未额外下载 | 那 10 张封面在 09-13 就未成功入库（历史缺口，非本任务引入）；R7 AC 明确「未同步封面不报错」 |

## Review gate

- task.py start 前需用户确认本计划与 prd/design。
