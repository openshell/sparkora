# 技术设计：文章创作配图建议（子C）

## 核心边界（用户硬约束）

- **系统只产出建议，绝不自动写入正文或 `body_image_ids`。** 配图进入正文的唯一路径是用户显式点击「采用」。
- **不提供任何自动插入开关**：不存在 `AUTO_ILLUSTRATE_ENABLED` 之类的配置，从设计上排除无人值守自动配图。
- 建议生成本身零副作用：只读正文与图库，不写 `content_md` / `body_image_ids`。
- 生成建议（检索）可自动触发，但结果仅是待批准状态。

## 勘察发现（改变 PRD 原始假设，必须记录）

1. **实际渲染只认正文 markdown 里的图片引用**：`PreviewService.buildMarkdown()` 的 `bodyImageUrls` 参数**完全未被使用**（`PreviewService.java:124-137` 仅拼接 frontmatter + `contentMd`）。正文插图落点由 `contentMd` 中的 `![](url)` 决定。
2. **手动插图只写 markdown，不登记 `body_image_ids`**：`StepPreview.vue:630-634` 的 `insertBodyImage` 仅调 `editorRef.insertMd()`；前端 **无任何调用** `imageApi.addBodyImage`（`frontend/src/api/index.js:174` 定义但为死代码）。
3. **`body_image_ids` 仍是遗留但有用的字段**：参与发布页「插图 N 张」展示（`StepPublish.vue:62`）与 `ImageService.delete` 的引用保护（`ImageService.java:559-568`）。现存 34 个版本中仅 4 个非空，且这 4 个版本正文 markdown 中 `![` 出现 **0 次**——历史数据显示两者本就脱节。
4. **PRD R4 的表述与实际不符**（PRD 写「正文插图由 `body_image_ids` + 渲染层插入」）。已在用户确认后修正为：采用时**同时**写 markdown（保证渲染）**与**登记 `body_image_ids`（保证计数正确 + 引用保护）。
5. **AI 侧禁止图片仍在生效**：`VersionService.java:188` 仿写 prompt 禁图片 + `:210` `stripImages` 二次清洗（仅仿写分支）。**本任务不改这两处**（R5 的推荐方案：保留「AI 不写图」，新增「系统建议」链路，二者不冲突）。

## 架构与边界

```
后端（只读建议生成，绝不写正文）：
  ArticleProjectController
    POST /{id}/illustration-suggestions   （三角色）→ 生成建议（可重算，零副作用）
    POST /{id}/illustration-suggestions/dismiss （ADMIN/EDITOR）→ 忽略某锚点建议组

  IllustrationSuggestionService（新建）
    ├─ AnchorExtractor（新建，纯静态可单测）：contentMd → [{anchorIndex, headingPath, text}]
    ├─ ImageEmbeddingService.searchImages（子B，以锚点文本为 query）
    └─ 组装建议：按锚点分组，每组 topN 候选（含 score/url/thumbUrl/tags/sourceRef）

  忽略记录：sparkora_illustration_dismiss（新建表，project_id + version_id + anchor_key + created_by）

前端（用户批准后才写入）：
  StepPreview.vue「智能配图建议」区
    ├─ 生成建议按钮 → 展示按锚点分组的候选
    ├─ 单张「插入到此段」→ 编辑器插入 markdown + 登记 body_image_ids（两者都做）
    ├─ 整组「全部采用」→ 同上批量
    └─ 「忽略」→ 调 dismiss 接口，该锚点不再推荐
```

## 数据模型（schema.sql，幂等）

```sql
-- 09-15 article-auto-illustrate:配图建议「忽略」记录。
-- 语义:用户忽略某锚点的建议组后,该锚点不再推荐(避免反复打扰)。
-- 锚点用 anchor_key(锚点定位指纹)而非序号——正文编辑后序号会漂移,指纹稳定。
-- 不建强外键(沿用图库表应用层维护惯例);物理删(同 tag/embedding 表)。
CREATE TABLE IF NOT EXISTS sparkora_illustration_dismiss (
    id          BIGSERIAL PRIMARY KEY,
    project_id  BIGINT       NOT NULL,
    version_id  BIGINT       NOT NULL,
    anchor_key  VARCHAR(200) NOT NULL,              -- 锚点指纹(见 AnchorExtractor.fingerprint)
    created_by  VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (version_id, anchor_key)
);
CREATE INDEX IF NOT EXISTS idx_illustration_dismiss_version ON sparkora_illustration_dismiss(version_id);
```

- 建议候选**不落库**：每次按需生成（可重算、结果稳定），落库只会引入「建议陈旧」问题。只有「忽略」这个用户决策需要持久化。
- `anchor_key`：锚点指纹。**不用序号**（正文编辑后序号漂移，忽略记录会错位到别的段落）。指纹 = 锚点标题路径 + 文本前 N 字符的短哈希（见下）。

## 锚点切分（`AnchorExtractor`，纯静态）

```java
public record Anchor(int anchorIndex, String headingPath, String text, String key) {}

/** 正文 Markdown → 可配图锚点（保序，受上限约束）。 */
public static List<Anchor> extract(String contentMd, int maxAnchors)

/** 锚点指纹:headingPath + 文本归一化前 80 字符的 sha256 前 12 位(稳定,防序号漂移)。 */
public static String fingerprint(String headingPath, String text)
```

切分规则：
- 按 **ATX 标题（`##`/`###`）分段**：每个标题到下一个标题之间为一个锚点；标题前的前言也算一个锚点（`headingPath` 为空）。
- 无标题时（罕见）退化为按空行切分段落，每段一个锚点。
- 跳过：
  - 纯列表段落（每行以 `-`/`*`/`+`/`1.` 开头且无普通句式）
  - 引用块（`>` 开头）
  - 代码块（``` 围栏内）
  - 图片行（`![...](...)`，避免给配图建议区自己推荐）
  - 过短段落（去空白后 < 30 字）
- `text`：锚点段落正文，剔除 markdown 标记（标题符号、加粗 `**`、链接语法保留文字），trim。
- 上限 `maxAnchors` = **5**（默认，可配 `sparkora.ai.illustration-max-anchors`），超出取前 5 个（优先靠前段落）。

## 建议生成（`IllustrationSuggestionService`）

```java
/** 生成建议(只读,零副作用)。返回按锚点分组的建议。 */
public List<AnchorSuggestion> suggest(Long projectId, List<String> tags, Double minScore)

/** 忽略某锚点建议组(写入 dismiss 表,幂等)。 */
public void dismiss(Long projectId, String anchorKey, String operator)
```

流程：
1. 取项目当前版本（`currentVersionId`），无版本 → 空列表（不报错，或明确 400？见错误矩阵）。
2. `AnchorExtractor.extract(contentMd, maxAnchors)`。
3. 过滤已忽略锚点：查 `sparkora_illustration_dismiss`（按 version_id）得 `anchor_key` 集合，剔除。
4. 逐锚点调 `embeddingService.searchImages(anchor.text, topN=3, minScore, tags)`。
   - `tags` 为可选的项目级主题标签预过滤（AND，复用子B 语义）。
   - 无命中（空列表）→ 该锚点不出现在结果中（不报错）。
5. 组装：

```java
public record AnchorSuggestion(String anchorKey, int anchorIndex, String headingPath,
                               String anchorText, List<ImageSearchHit> candidates) {}
```

- 每锚点候选数 `topN` = **3**（默认，可配）。
- 门槛复用 `AiProperties.imageMinScore`（默认 0.3）。
- 逐锚点串行调用（≤5 次 embedding），无并发复杂度。任一锚点检索失败 → 该锚点跳过 + warn，不整体失败。

**幂等/稳定性**：同一版本同一正文 + 同一图库 → 相同结果（检索确定性 + 无随机）。正文变更后结果随锚点变化，符合预期。

## 建议的展示与「采用」（前端）

`StepPreview.vue` 配图抽屉新增第 3 个 tab「智能建议」（`imgTab = 'suggest'`）：

- 顶部：生成按钮 + 可选标签预过滤（多选，AND）+ 门槛说明 + 提示「系统只给建议，点采用才会写入正文」。
- 按锚点分组卡片：锚点标题（`headingPath` 或「开头段落」）+ 锚点文本摘要 + 候选图网格（缩略图 + 相关度 + 标签）。
- 每张候选：「插入到此段」（单张采用）。
- 每组：「全部采用」+「忽略此段」。
- 空态：未生成 / 生成后无候选（提示可调低门槛或换标签）/ 全部被忽略。

**「采用」的写入（用户批准后，唯一写入路径）**：

```js
const adoptSuggestion = async (group, img) => {
  // 1) markdown 插入(保证真正渲染)——定位到锚点标题之后
  editorRef.value?.insertMdAtAnchor?.(group.headingPath, `\n![](${originUrl(img)})\n`)
  // 2) 登记 body_image_ids(保证发布页计数 + 防误删)
  await imageApi.addBodyImage(projectId.value, img.id)
  // 3) 刷新快照,标记该候选已采用
}
```

- **插入位置**：锚点标题之后（该段首行）。`MarkdownEditor` 需新增 `insertMdAtAnchor(headingPath, text)`：按标题定位插入点，找不到则退回 `insertMd`（光标处），保证不丢内容。
- **两处都写**：markdown 保证渲染；`addBodyImage` 保证 `StepPublish` 计数与 `ImageService.delete` 引用保护正确。二者幂等（`addBodyImage` 已幂等；重复插入 markdown 由用户可见并自行编辑）。
- 采用后建议组**不自动消失**（用户可能要给同一段配多张），但候选项标记「已采用」。

## 「忽略」的语义

- 「忽略此段」→ 调 `POST /{id}/illustration-suggestions/dismiss` body `{anchorKey}`，写 dismiss 表（`UNIQUE(version_id, anchor_key)` 保证幂等，重复忽略不报错）。
- 后续生成建议时该锚点被跳过。
- **不提供「取消忽略」的 UI**（Non-goal，避免复杂度）；如需恢复，删表记录即可。

## 接口契约

### `POST /api/projects/{id}/illustration-suggestions`（三角色）

请求（全部可选）：
```json
{ "tags": ["主题/销量"], "minScore": 0.3 }
```
响应 `R<List<AnchorSuggestion>>`：
```json
{ "code": 0, "data": [
  { "anchorKey": "a1b2c3d4e5f6", "anchorIndex": 1, "headingPath": "续航实测",
    "anchorText": "实测高速工况下……",
    "candidates": [
      { "imageId": 37, "score": 0.62, "sourceText": "…", "fileName": "…", "source": "byd-news",
        "sourceRef": "…", "url": "https://…", "thumbUrl": "https://…", "tags": ["主题/销量"] } ] } ] }
```
- **零副作用**：不修改 `content_md` / `body_image_ids`。

### `POST /api/projects/{id}/illustration-suggestions/dismiss`（ADMIN/EDITOR）

请求：`{ "anchorKey": "a1b2c3d4e5f6" }` → `R<Void>`；幂等。

### 错误矩阵

| 条件 | 行为 |
|---|---|
| 未登录 | 401 |
| VIEWER 生成建议 | 200（读） |
| VIEWER dismiss | 403 |
| 项目不存在 | 400 `项目不存在` |
| 无当前版本 | 400 `尚未生成正文版本，无法生成配图建议` |
| 正文为空 | 400 `正文为空，无法生成配图建议` |
| `minScore` 非法（<0 或 >1） | 400 |
| `tags` 无交集 | 200，各锚点 `candidates: []`（或该锚点不出现） |
| embedding 调用失败（单锚点） | 该锚点跳过 + warn，其余照常返回（不整体 500） |
| `anchorKey` 为空/超长 | 400 |

## 保留「AI 不写图」约束（R5 边界）

- **不改** `VersionService.java:188`（仿写 prompt 禁图片约束）与 `:210`/`:264` `stripImages`。
- 理由：AI 编造的图片 URL 必然 404，且无法保证与图库一致；正文的图必须来自图库（可渲染、可发布）。系统补图走独立链路，与「AI 不写图」不冲突。
- 因此本项目**不存在**「AI 写占位标记 → 系统替换」的方案，也不需要改 prompt/清洗逻辑。

## 兼容与迁移

- 纯增量：新表 + 新接口 + 前端新 tab；`content_md` / `body_image_ids` / 渲染 / 发布链路全部不动。
- `MarkdownEditor` 新增 `insertMdAtAnchor`（**新增方法**，不改 `insertMd`，既有调用点不受影响）。
- `imageApi.addBodyImage` 从死代码变为被调用（接口已存在，后端已就绪，无后端改动）。
- 建议生成按需调用，无后台任务、无启动开销。
- `sparkora.ai.illustration-max-anchors`（默认 5）、`sparkora.ai.illustration-top-n`（默认 3）新增，`.env.example` 同步。

## 权衡记录

- **建议不落库**：建议是「当前正文 + 当前图库」的派生视图，落库会陈旧；按需计算 + 结果稳定已足够。只有「忽略」（用户决策）需持久化。
- **锚点用指纹而非序号**：正文编辑后序号漂移，忽略记录会错位。指纹（标题路径 + 文本短哈希）在正文未编辑时稳定，编辑后仅该锚点失效，不误伤其他。
- **采用时同时写 markdown 和 body_image_ids**：只写 markdown 则发布页计数错、图片可能被误删；只写 body_image_ids 则根本不渲染（当前遗留缺陷）。两者都写才自洽——这是本次勘察最重要的修正。
- **不修 `body_image_ids` 遗留缺陷本身**：修复方向是「改为基于正文解析」，波及 `delete` 引用保护、`projectImages`、发布页计数，超出本任务范围（用户选择「markdown + 登记」，非大规模修复）。记为已知债务。
- **串行逐锚点检索**：≤5 次调用，并发带来的复杂度不值当。
- **忽略不可撤销（无 UI）**：避免为低频操作增加 UI 复杂度；数据可手动清理。

## 回滚

- 前端：还原 `StepPreview.vue` / `api/index.js` / `MarkdownEditor.vue`。
- 后端：还原 Java 文件；新表留存无害。
- 数据：`DROP TABLE IF EXISTS sparkora_illustration_dismiss;`
- 无状态迁移、无存量数据变更。

## 风险

- **`MarkdownEditor` 新增按标题定位插入**：需处理「标题不存在」「多个同名标题」——按首次出现定位，找不到退回光标处，保证不丢内容。
- **建议质量依赖图库丰富度**：图库仅 ~170 张，多数锚点可能无候选（门槛 0.3）。属预期（空态有指引），需在文档说明「图库越丰富，建议越有用」。
- **忽略记录与版本耦合**：`version_id + anchor_key`，版本切换后忽略记录不跨版本（符合语义：不同版本的正文不同）。
- **embedding 调用成本**：每次生成建议 ≤5 次 embedding（约 1~2 秒），按需触发可接受。
