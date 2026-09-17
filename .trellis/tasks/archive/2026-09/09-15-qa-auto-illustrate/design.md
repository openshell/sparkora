# 技术设计：知识问答自动配图（子D）

## 已定决策（用户 09-17 确认）

**问答配图直接随答案展示**——只读、无副作用、无需用户批准。
理由：配图只是答案的附加展示，不写入任何用户内容；与子C「写入正文需批准」的风险模型不同（子C 改的是用户内容，子D 只是多显示几张图）。因此本任务**不引入批准流程、不引入任何写用户内容的路径**。

## 首要待查项结论（PRD Notes 要求，已勘察）

**问题**：`Citation`/NEWS chunk 是否携带可定位新闻的 id？

**结论：不携带，但链路可直接打通，只需补一个可空字段。**

证据链：
1. `Citation` record 仅 5 字段 `(source, modelName, chunkType, score, chunkText)`（`CarRagService.java:60`）——NEWS 的 `modelName` 是**新闻标题**，不是 id。
2. 但 `searchTopKUnified` 的 SQL **已经 SELECT `docId`**（三域都查：CAR=`car_doc.id`、KB=`kb_chunk.id`、NEWS=`sparkora_news_doc.id`，见 `CarDocEmbeddingMapper.java:67-94`）。
3. `retrieveUnified` 读结果行时**丢掉了 docId**（`CarRagService.java:366-373` 只读 chunkText/chunkType/score/source/modelId/modelName）→ `UnifiedHit` 无 docId → `Citation` 无 docId。
4. 目标链路完整可用（真机验证）：
   `sparkora_news_doc.id` → `news_id`(内部 BIGINT FK) → `sparkora_news.id` → `cover_image_id` → `sparkora_image_asset`
   实测：`detail588` → `news_doc.id=1688` → `news.id=48` → `cover_image_id=84`。

**所以只需**：给 `UnifiedHit` 与 `Citation` 各加一个可空 `Long docId`（纯增量，向后兼容）。

## 架构与边界

```
写路径（问答提问，只读扩展）：
  QaService.ask
    ├─ retrieveForGeneration → rag.citations()（NEWS 引用现带 docId）
    ├─ QaImageRefService.forAnswer(rag.citations(), question, limit)   ← 新增
    │    ├─ 新闻关联图：NEWS citations 的 docId → news_doc → news → cover_image_id → 图库资产
    │    └─ 语义检索图：问题命中图片意图词 → ImageEmbeddingService.searchImages(question)
    │    （两路合并去重 + 上限截断）
    └─ 落 assistantMsg.imageRefs（JSON）

读路径：
  GET /qa/sessions/{id} → messages 已含 imageRefs → 前端展示

前端：
  QaChat.vue 答案气泡下方新增图片缩略图行（CitationList 之后）
```

新增组件：
- `com.sparkora.qa.service.QaImageRefService` — 配图解析（新闻关联 + 语义检索 + 合并去重）
- `com.sparkora.qa.service.QaImageIntent`（或作为 QaImageRefService 静态方法）— 图片意图判定（纯静态可单测）
- `com.sparkora.domain.dto.QaImageRef` — 配图条目 DTO

修改：
- `CarRagService.UnifiedHit` / `Citation` 加 `docId`；`retrieveUnified` 读入 docId；boost 处透传 docId；Citation 构造处透传
- `QaService` 注入 `QaImageRefService`，落 `imageRefs`（失败仅 warn 不阻断）
- `QaMessageEntity` 加 `imageRefs`
- `ImageService` 加只读方法 `loadDerived(List<Long> ids)`（复用既有派生字段填充，避免 URL/缩略图逻辑二次实现）
- `schema.sql` 加 `sparkora_qa_message.image_refs TEXT`
- `QaChat.vue` 展示图片行
- `qaApi` 无需新增（`imageRefs` 随 message 返回）

## 数据模型（schema.sql，幂等）

```sql
-- 09-15 qa-auto-illustrate:问答答案配图(JSON 数组;仅 assistant 消息非空,历史行 NULL)。
-- 结构:[{imageId, url, thumbUrl, title, newsId, source}];只读展示用,不参与任何写入。
ALTER TABLE sparkora_qa_message ADD COLUMN IF NOT EXISTS image_refs TEXT;
```

- 单列可空，纯增量；历史行 NULL → 前端不展示图片区（R3 零回归）。

## docId 传递（最小改动，向后兼容）

```java
// UnifiedHit：加可空 docId（域内语义：CAR=car_doc.id / KB=kb_chunk.id / NEWS=news_doc.id）
public record UnifiedHit(String chunkText, String chunkType, double score,
                         String source, Long modelId, String modelName, Long docId) {}

// Citation：加可空 docId；保留 5 参兼容构造器，既有调用方（QaServiceTest/KnowledgeSearchTool）不受影响
public record Citation(String source, String modelName, String chunkType, double score,
                       String chunkText, Long docId) {
    public Citation(String source, String modelName, String chunkType, double score, String chunkText) {
        this(source, modelName, chunkType, score, chunkText, null);
    }
}
```

改动点（3 处构造 + 1 处读取）：
- `retrieveUnified`（:373）：读 `row.get("docId")` 传入
- boost 重排（:251）：透传 `h.docId()`（**易漏**，漏了 NEWS 的 docId 会在加权后丢失）
- Citation 构造（:347）：透传 `h.docId()`

## 新闻关联图解析（`QaImageRefService.byNewsCitations`）

```java
/** NEWS 引用 → 新闻封面图。批量查询,去重,上限截断。 */
List<QaImageRef> byNewsCitations(List<Citation> citations, int limit)
```

流程（批量，避免 N+1）：
1. 过滤 `source=NEWS && docId != null`，收集 `docId`（去重）。
2. `newsDocMapper.selectBatchIds(docIds)` → 取 `news_id`（内部 BIGINT），按 docId 保序去重。
3. `newsMapper.selectBatchIds(newsIds)` → 取 `cover_image_id`（跳过 null），并记 `news.title`。
4. `imageService.loadDerived(imageIds)` → 得 url/thumbUrl；过滤 url 为空者。
5. 上限截断（默认 3），保持引用相关度顺序（citations 本身按 score 降序）。

- 任一步失败 → 返回已成功部分 + warn（不抛出，答案优先）。

## 语义检索图（`QaImageRefService.bySemanticQuery`）

```java
/** 图片意图问题 → 语义检索图片(依赖子B)。 */
List<QaImageRef> bySemanticQuery(String question, int limit)
```

- 复用子B `ImageEmbeddingService.searchImages(query, topK, minScore, tags=null)`。
- query 清洗：剥离意图短语（`给我看`/`我想看`/`看一下`/`看图`/`图片`/`海报`/`照片`/`配图`）后若剩余为空，则用原问题。
- 默认 `topK = limit`，门槛用 `AiProperties.imageMinScore`。
- 失败 → 空列表 + warn。

## 图片意图判定（`QaImageIntent.isImageIntent`，纯静态）

```java
private static final List<String> INTENT_WORDS = List.of(
    "看图", "看图片", "看张图", "图片", "海报", "照片", "配图", "给我看", "我想看", "看一下");
public static boolean isImageIntent(String question)
```

- 命中任一 → true，触发语义检索（R4）。
- 关键词法（不用 LLM）：零成本、可测、可解释；误判成本仅是「多显示几张图」，可接受。
- **非图片意图不触发语义检索**（R4 AC「无性能浪费」）——新闻关联图路径始终执行（它已随检索结果产生，无额外 embedding 调用）。

## 合并与去重（`QaImageRefService.forAnswer`）

```java
List<QaImageRef> forAnswer(List<Citation> citations, String question, int limit)
```

1. `byNewsCitations(citations, limit)`（便宜路径，始终执行）
2. 若 `isImageIntent(question)` → `bySemanticQuery(question, limit)`
3. 按 `imageId` 去重（新闻关联图优先，因其与答案引用强相关），截断至 `limit`
4. 全部包 try/catch：任何异常 → 返回 `List.of()` + warn（**绝不阻断答案生成**）

DTO：
```java
public record QaImageRef(Long imageId, String url, String thumbUrl,
                         String title, String newsId, String source) {}
```
- `title`：新闻关联图=新闻标题；语义图=`sourceText` 首段或 `fileName`
- `newsId`：新闻关联图=官方 `news_id` 字符串；语义图=null
- `source`：`byd-news` 等图库来源

## 接入 QaService

```java
// 4.5) 解析答案配图(只读;失败仅 warn,答案优先)
List<QaImageRef> imageRefs = List.of();
try {
    imageRefs = qaImageRefService.forAnswer(rag.citations(), q, IMAGE_REF_MAX);
} catch (Exception e) {
    log.warn("问答配图解析失败(忽略) session={}: {}", sessionId, e.getMessage());
}
assistantMsg.setImageRefs(toJson(imageRefs.isEmpty() ? null : imageRefs));
```

- `IMAGE_REF_MAX = 3`（常量，或 `AiProperties.qaImageRefMax`）。**用常量即可**，避免过度配置化。
- `imageRefs` 为空 → 存 null（前端 `v-if` 不展示，且与历史行行为一致）。

## 前端展示（`QaChat.vue`）

在答案气泡内 `CitationList` 之后追加：

```html
<div v-if="m.role === 'assistant' && imgRefsOf(m).length" class="qa-imgs">
  <div v-for="img in imgRefsOf(m)" :key="img.imageId" class="qa-img-cell"
       @click="previewImg(img)">
    <el-image :src="img.thumbUrl || img.url" fit="cover" class="qa-img-thumb" />
    <span class="qa-img-title">{{ img.title }}</span>
  </div>
</div>
```

- `imgRefsOf(m)`：兼容 `imageRefs` 为字符串（后端 JSON 字符串）或数组——**复用 `CitationList` 已有的「字符串或数组」兼容写法**（`CitationList.vue:32-38`），避免踩子C 的字段名混用坑。
- 预览大图：`el-image` 的 `preview-src-list` + `preview-teleported`，用 `url`（原图，**不用 thumbUrl**——参照 StepPreview「插入/预览必须用原图 URL」的既有教训）。
- 移动端横向滚动，触控目标 ≥44px。

## 错误矩阵

| 条件 | 行为 |
|---|---|
| 无 NEWS 引用 | 新闻关联图为空（不报错） |
| NEWS 引用无 `cover_image_id` | 跳过该条 |
| 图库资产已删/无 url | 跳过该条（`loadDerived` 后过滤 url 空） |
| 语义检索失败 | 该路径返回空 + warn；答案正常 |
| 配图解析整体异常 | `imageRefs=null` + warn；答案正常（**不阻断**） |
| 非图片意图问法 | 不触发语义检索（无额外 embedding 调用） |
| 历史消息 `image_refs` NULL | 前端不展示图片区（行为不变） |
| 权限 | 沿用既有问答权限（`QaController` 三角色），**无新接口** |

## 兼容与迁移

- 纯增量：1 个可空列 + 2 个 record 加可空字段（`Citation` 保留 5 参构造器）+ 1 个新 service + 前端展示。
- `Citation` 多一个 JSON 字段：`BriefService.citationsJson`（简报）与 `KnowledgeSearchTool`（深度检索）会多输出 `docId`，前端 `CitationList` 不读该字段 → 无影响。
- 不改答案文本、`citations` 既有字段、`ragStatus` 语义（R3）。
- 不改三域检索 SQL 与配额行为（只多读一列）。
- 无后台任务、无启动开销（配图在提问时按需解析）。

## 权衡记录

- **直接在答案上展示而非批准流程**：配图是只读附加展示，不写用户内容；批准流程只对「写入用户内容」（子C）有意义（用户已确认）。
- **复用 `Citation.docId` 而非标题匹配**：标题可能重复/含全角标点，`docId` 是稳定主键；且 SQL 本就查了该列，补读取几乎零成本。
- **`docId` 通用命名（非 `newsDocId`）**：三域语义不同（car_doc/kb_chunk/news_doc），一个字段承载域内 id，配合 `source` 判别即可；命名 `newsDocId` 会误导 CAR/KB。
- **意图判定用关键词而非 LLM**：零成本、可单测、可解释；误判代价只是多显示几张图，不值得引入 LLM 调用。
- **常量而非配置化 `IMAGE_REF_MAX`**：上限 3 是产品展示决策，无运维调参需求；过度配置化增加面。
- **`ImageService.loadDerived` 只读复用**：避免在问答侧重新实现「storageKey→url / 七牛 thumbUrl」逻辑导致两处漂移（子B 已因派生规则漂移吃过亏）。

## 回滚

- 前端：还原 `QaChat.vue`。
- 后端：还原 Java 文件（`Citation` 多出的可空字段与新 service 可留可删）；列 `image_refs` 留存无害。
- 数据：`ALTER TABLE sparkora_qa_message DROP COLUMN IF EXISTS image_refs;`
- 无状态迁移、无存量数据变更。

## 风险

- **`docId` 在 boost 重排处漏传**：`CarRagService.java:251` 会重建 `UnifiedHit`，漏传则 NEWS 的 docId 在锚点加权后丢失 → 配图静默失效。单测须覆盖「boost 后 docId 保留」。
- **`Citation` record 加字段破坏既有 5 参调用**：已用兼容构造器规避；须确认 `QaServiceTest:73`、`KnowledgeSearchTool`、`BriefService` 编译通过。
- **误判图片意图**：非图片问题含「图片」会多一次 embedding 调用（约百毫秒）。可接受。
- **答案配图与答案内容不相关**：语义路径按相似度，泛查询可能给弱相关图。缓解：门槛（`AI_IMAGE_MIN_SCORE` 默认 0.3）；前端只作缩略图展示，不干扰正文阅读。
