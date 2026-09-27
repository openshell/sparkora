# P1-⑦ body_image_ids 规范化 技术设计

> 范围 A：只做 body_image_ids → 关联表；JSONB 判定不予处置（见 prd Background C）。纯 DDL + Java 读写替换，对外 API 契约不变。

## 1. 迁移 V2

`src/main/resources/db/migration/V2__article_version_image.sql`

```sql
-- P1-⑦ body_image_ids(逗号列) → 一对多关联表;消除 LIKE 粗筛与两份重复 split 解析。
-- 幂等单语句(无 DO $$);应用层维护外键(不建强 FK,同 sparkora_image_tag 范式)。
CREATE TABLE IF NOT EXISTS sparkora_article_version_image (
    id          BIGSERIAL PRIMARY KEY,
    version_id  BIGINT      NOT NULL,
    image_id    BIGINT      NOT NULL,
    sort_order  INTEGER     NOT NULL DEFAULT 0,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (version_id, image_id)
);
CREATE INDEX IF NOT EXISTS idx_article_version_image_version ON sparkora_article_version_image(version_id);
CREATE INDEX IF NOT EXISTS idx_article_version_image_image   ON sparkora_article_version_image(image_id);

-- 回填:逗号串按原顺序展开(trim 后非空),WITH ORDINALITY 保序。
INSERT INTO sparkora_article_version_image (version_id, image_id, sort_order)
SELECT v.id,
       trim(parts.token)::bigint,
       parts.ord - 1
FROM sparkora_article_version v
CROSS JOIN LATERAL regexp_split_to_table(v.body_image_ids, ',') WITH ORDINALITY AS parts(token, ord)
WHERE v.body_image_ids IS NOT NULL
  AND trim(v.body_image_ids) <> ''
  AND trim(parts.token) <> ''
ON CONFLICT (version_id, image_id) DO NOTHING;

ALTER TABLE sparkora_article_version DROP COLUMN IF EXISTS body_image_ids;
```

**要点**
- `sort_order` 从 0 起（与 `bodyIdListOf` 原「有序」语义一致）。
- `trim(parts.token) <> ''` 过滤空元素（防 `"5,,7"`）。
- `::bigint` 若遇非法 token 会致迁移失败——现状写入恒为 `String::valueOf`，安全；实施前先跑一次 `SELECT ... WHERE token !~ '^\s*\d+\s*$'` 核对零异常（见 implement.md）。
- 空库（V1 后）执行：SELECT 无行，INSERT 零行，DROP no-op，幂等。

## 2. 实体 + Mapper

`ArticleVersionImageEntity`（`sparkora_article_version_image`，无 `@TableLogic`）：

```java
@Data @TableName("sparkora_article_version_image")
public class ArticleVersionImageEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long versionId;
    private Long imageId;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
```

`ArticleVersionImageMapper extends BaseMapper<ArticleVersionImageEntity>`：
- 有序取图 id：`selectList(eq version_id orderByAsc sort_order)` → Java 提 `imageId`（保持既有「List<Long>」出口形态）。
- 追加：`selectOne(version_id+image_id)` 判存在 → `insert(sortOrder = max+1)`；并发靠 `UNIQUE(version_id,image_id)` 兜底捕 `DuplicateKeyException`。
- 删除：`delete(eq version_id + eq image_id)`（幂等）。
- 引用反查：`selectList(eq image_id)` → 收集 `versionId`（替代 LIKE）。
- 版本清空：`delete(eq version_id)`（版本删除时——当前无版本删除路径，留作工具）。

## 3. ImageService 改造

| 位置 | 现状 | 改造 |
|---|---|---|
| `bodyIdListOf(v)` (L770-776) | split 字符串 | **删除**；新增 `List<Long> bodyImageIds(Long versionId)` 查关联表有序 |
| `modifyBodyImage` (L735-746) | 读串→改 List→拼串→updateById | `add`：存在校验 + 幂等 insert(sortOrder=max+1)；`remove`：delete(versionId,imageId) 幂等；**不再 updateById version 行** |
| `delete(id)` 引用检查 (L659-670) | `like("body_image_ids", id)` 粗筛 + Java 精确 | 直接 `SELECT version_id FROM sparkora_article_version_image WHERE image_id=?`（+ 封面 `eq cover_image_id`）；**消除 LIKE 与误匹配** |
| `projectImages` (L687-714) | `bodyIdListOf(current)` | `bodyImageIds(current.getId())`；`m.put("bodyImageIds", ...)` **不变** |
| 其他 `bodyIdListOf` 调用 | L666/L692/L739 | 随上表替换 |

**注意**：`setCover`（L720-728）仍 `versionMapper.updateById(v)` 写 `cover_image_id`（不同列，保留；非本次范围——属版本行部分更新，另有 P0 已修的项目行问题不涉及此）。

## 4. PreviewService 改造

- 删 L321-325 本地 `bodyIdListOf`；构造注入 `ArticleVersionImageMapper`；`render`(L79) 用 `bodyImageIds(v.getId())` 查关联表。渲染输出（frontmatter/正文图 URL 顺序）不变。

## 5. 兼容性 / 回滚

- **对外契约不变**：`GET /api/projects/{id}/images` 的 `bodyImageIds: List<Long>`、`ModifyBodyImageDto`、前端零改动。
- **行为差异（正向）**：引用检查由「LIKE 粗筛 + 精确过滤」变为「精确 SQL」，结果等价或更严；`modifyBodyImage` 不再全量回写 version 行（顺带消除一次读改写竞态）。
- **回滚**：需重加列并 `string_agg(image_id::text, ',' ORDER BY sort_order)` 聚合回填（数据在关联表，无损）；文档化于 implement.md。
- **风险**：迁移 `::bigint` 强转；DROP 列不可逆（但数据已搬）；实施前两重校验（预检非法 token + 回填行数核对）。

## 6. 测试

- `ArticleVersionImageMapper` 读写：顺序保持、add 幂等、remove 幂等、UNIQUE 冲突。
- `ImageService.delete`：被引用（封面/插图）拒绝、id=5 vs 15/51 不误伤、无引用可删。
- `ImageService.projectImages`：`bodyImageIds` 顺序与内容。
- `PreviewService`：插图 URL 顺序（既有测试若 mock 了 `getBodyImageIds` 需同步——见 implement.md 排查）。
- 迁移：空库/V1 后 与 既有库回填（check 代理在隔离 pgvector 容器，同 ⑥ 做法）。

## 7. 保持不变

- 其他所有 TEXT JSON 列；`sparkora_image_tag`；`cover_image_id`；版本/项目删除逻辑（当前无版本级联删除路径）。
