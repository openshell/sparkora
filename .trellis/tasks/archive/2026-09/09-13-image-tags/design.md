# 技术设计：图库图片标签功能与交互优化

## 架构与边界

```
ImageLibrary.vue ── imageApi ── ImageController ── ImageTagService ── ImageTagMapper ── sparkora_image_tag
                     │                │                                 └──(FK 语义)── sparkora_image_asset
                     │                └── ImageService.persistOrReuse（入库管线挂打标钩子）
                     └── docs/s0-spec.md §10（契约同步）
```

- 标签域新开 `ImageTagService`（独立于 `ImageService` 的写路径），读路径（列表 tags 回填）由 `ImageService.list`/`projectImages` 调用 tag 服务回填。
- `sparkora_image_asset` 表**不加列**：标签关系全部放 `sparkora_image_tag`（符合「独立标签表」决策），entity 仅加 `@TableField(exist=false) List<String> tags` 非持久化字段。

## 数据模型（schema.sql 新增 S-tags 段，幂等）

```sql
CREATE TABLE IF NOT EXISTS sparkora_image_tag (
    id          BIGSERIAL PRIMARY KEY,
    image_id    BIGINT      NOT NULL,   -- → sparkora_image_asset.id（应用层维护，不建强 FK）
    tag_name    VARCHAR(50) NOT NULL,   -- 标签名（trim 后 1~50 字符）
    created_by  VARCHAR(64) NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (image_id, tag_name)
);
CREATE INDEX IF NOT EXISTS idx_image_tag_name ON sparkora_image_tag(tag_name);
```

- `UNIQUE(image_id, tag_name)` 数据库级防重；并发补打冲突捕 `DuplicateKeyException` 静默吞（幂等语义）。
- 不建强外键：图库删除图时同步 `DELETE FROM sparkora_image_tag WHERE image_id=?`（ImageService.delete 内联清理，同 KB embedding 兜底清理先例，避免物理行残留）。
- 不设 `deleted` 逻辑删除列（关系行生命周期 = 图片生命周期，物理删）。

## 接口契约（全部 `R<T>` 包装）

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| GET | `/api/images` | 三角色 | 现有参数 + `?tag=`（可与其他筛选组合） | rows 每条新增 `tags: string[]`（按名称排序） |
| GET | `/api/images/tags` | 三角色 | — | `{tags: [{name, count}]}`（count 降序，供预选/筛选联想复用） |
| POST | `/api/images/upload` | ADMIN/EDITOR | multipart `file` + `projectId?` + `tags?`（逗号分隔，可多值重复参数） | `{image}`（含 tags） |
| POST | `/api/images/generate-text` | ADMIN/EDITOR | `{projectId?, prompt, size?, n?, tags?[]}` | `{images[]}`（每张含 tags） |
| POST | `/api/images/generate-from-image` | ADMIN/EDITOR | `{projectId?, refImageId, prompt, size?, n?, tags?[]}` | `{images[]}` |
| POST | `/api/images/{id}/regenerate` | ADMIN/EDITOR | — | `{images[]}`（新图**继承源图标签**） |
| PUT | `/api/images/{id}/tags` | ADMIN/EDITOR | `{tags: [...]}`（**全量覆盖**语义） | `{ok:true, tags}` |
| POST | `/api/images/tags/batch` | ADMIN/EDITOR | `{ids:[...], tags:[...], action:"add"\|"remove"}` | `{ok:true}`（逐张执行，全部幂等） |

- multipart tags 解析：`request.getParameterValues("tags")` 收多值 + 单值内逗号拆分，与 batch 共用同一 normalize（trim/去空/去重/长度校验）。
- `ImageGenDTO` 加 `List<String> tags` 字段（默认 null = 不打标）。
- regenerate 无入参：`ImageService.regenerate` 内部读源图 tags 复制给新图。

## 打标钩子（入库管线）

`ImageService.persistOrReuse` 签名扩展（`ImageAssetEntity preset` 已含领域字段，tags 走 preset 非持久化字段传递）：

- **新入库**：insert 后批量写 tag 行。
- **dedupeHit 复用**：合并补上——取已有图 tags ∪ 本次 tags，只插差集（决策：用户预选必须生效）。
- **regenerate 继承**：调 tag 服务 copy 源→新图。
- **BYD 车型图**：`CarModelService.persistIntroImages` preset 传 tags=`[车型-<车型名>]`（走同一管线自动落标，零额外逻辑）。
- **BYD 新闻封面**：`NewsService.upsertOne` 下载 imageUrl 字节走 `persistOrReuse`，preset `source=byd-news`、`fileName=<newsId>.<ext>`、tags=`[新闻]`；`sparkora_news.image_url` 保留原 URL 不变（新闻页展示不动）。
- **存量追溯**：启动时一次性任务（幂等，可重跑）——遍历 `car_model.intro_images` asset id 列表，逐车型 mergeTags(`车型-<车型名>`)；不追溯新闻（历史新闻封面可重同步触发入库，手动可控）。

## 查询与回填

- `list(projectId, source, keyword, tag, page, size)`：tag 非空时先 `SELECT image_id FROM sparkora_image_tag WHERE tag_name=?` 得 id 集（走 `idx_image_tag_name`），空集直接返回空页；非空时 `qw.in("id", ids)`（图库分页规模 ≤100/页，tag 命中集若超 500 截断为前 500 保查询稳定）。
- rows 回填 tags：收集页内 image_id 批查 `WHERE image_id IN (...)` 按图分组，一次查询，避免 N+1。
- `projectImages` 引用图集合同样回填 tags（选封面/插图弹窗可见标签）。
- `SOURCES` 白名单加 `byd-news`（与 byd 并列，图库「来源」下拉可区分车型图/新闻图）。

## 前端设计（ImageLibrary.vue + api/index.js）

- `imageApi` 新增：`listTags()` / `updateTags(id, tags)` / `batchTags(ids, tags, action)`；upload/generate 系列透传 tags。
- **上传标签预选控件**：工具条「上传图片」旁一个可清空多选（`el-select` multiple allow-create filterable，值存 ref `presetTags`，不持久化），占位「上传标签」；上传与 AI 生图共读。上传走 multipart 附加 `tags` 字段；AI 生图 body 加 `tags`。
- **筛选**：工具条加标签下拉（同控件风格，单选清空），选中触发 onFilterChange；chip 条加 tag chip。
- **卡片展示**：hover 层元数据区加标签行（小号 tag）；移动端常显区加一行（溢出省略）。点标签 → 设 tagFilter 并刷新。
- **单图编辑**：hover 操作区/移动端 ··· 菜单加「编辑标签」，弹小对话框（多选 allow-create），确认调 `updateTags`（全量覆盖）。
- **批量管理**：bulk-bar 加「打标签」按钮 → 对话框选标签 + add/remove 单选，确认调 `batchTags`。
- **已知缺陷修复**（R5）：AI 抽屉 text2img/img2img 双 tab prompt 拆为两个独立 ref（`aiPromptText`/`aiPromptImg`）；参考图弹窗复用主列表筛选+分页数据（加页内搜索框 + 翻页，或等价）。
- **前端**：`SOURCE_LABELS` 加 `byd-news: '比亚迪新闻'`，来源色点补配色；其余无新页面。
- **标签自动分类**：车型图 `车型-<名>` / 新闻图 `新闻` 标签在预选控件联想中自然出现，无需专门 UI。

## 兼容与迁移

- 纯增量：新表 + entity 非持久化字段 + 新接口 + 现有接口可选参数。旧前端不传 tags 时行为与现状完全一致。
- schema.sql 幂等（IF NOT EXISTS），存量库启动自动建表，无数据回填。
- 删图联动清 tag 行：物理图行删除前清 tag（图库表本身无逻辑删除，物理删；tag 行同步物理删）。
- BYD 车型管线改传 tags（预设 `车型-<名>`）：去重命中时 merge 语义同样生效（同车型重同步幂等）；新闻封面新增下载步骤，失败仅告警不阻断新闻记录入库（同车型图容错先例）。
- 存量车型图追溯补标做成启动一次性任务（幂等）：遍历 car_model.intro_images 列表 mergeTags；重复启动零副作用（merge 只插差集）。新闻历史封面不追溯（用户需要时可重跑新闻同步触发入库）。
- `sparkora_news.image_url` 语义不变（原 URL 留痕），新闻知识页展示链路零改动。

## 权衡记录

- **tag 筛选用两段查询（先查 id 集再 IN）而非 JOIN**：MyBatis-Plus QueryWrapper 无 JOIN，两段查询简单直观；命中集截断 500 是保底（当前图库规模远小于此）。
- **PUT 全量覆盖 vs PATCH 增删**：单图编辑弹窗天然全量语义（用户在多选框里勾/删后提交），实现最简；批量操作才需要 add/remove action。
- **tags 数组放 multipart 多值参数**：FormData append 同名多值最自然，后端 `getParameterValues` 收齐后内部再按逗号拆，兼容前端任一传法。
- **标签列表接口带 count**：预选控件与筛选联想同源复用一个接口；count 降序即「常用优先」。
- **BYD 分类用标签而非新表/新列**：车型↔图已由 `car_model.intro_images`（asset id 列表）表达，标签层做的是**让图库页能筛**；`车型-<名>` 前缀命名约定简单且可组合（再点「新闻」可对比）。不建强关系表，避免双事实源。
- **新闻封面 source 单列 `byd-news` 而非共用 `byd`**：来源下拉是用户第一眼分类维度，混在一个 source 里只能靠标签细分，可发现性差；source 白名单扩展成本极低。
- **存量追溯只做车型图**：车型↔图映射现成（intro_images 列表），追溯是纯本地操作零网络成本；新闻封面重下载有网络成本且历史图可能失效，留给「重新同步」可控触发。

## 回滚

- 前端回滚：还原 ImageLibrary.vue / api/index.js 即可，无依赖。
- 后端回滚：还原 Java 文件；`sparkora_image_tag` 表留存无害（无代码引用即惰性数据），或手动 DROP。
- schema 回滚：`DROP TABLE IF EXISTS sparkora_image_tag;`（幂等）。