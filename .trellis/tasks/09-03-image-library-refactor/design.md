# 技术设计：图库系统性重构

## 1. 边界与总体形状

改动集中在四条纵切面，不新建大组件、不换技术栈：

| 层 | 文件 | 变化 |
|---|---|---|
| schema | `src/main/resources/db/schema.sql` | 幂等 ALTER 补 3 列 + 3 索引 |
| 实体/DTO | `ImageAssetEntity`、`domain.dto` 新增 `ImageGenDTO` | 补字段、收口入参 |
| 服务 | `ImageService`、`AiImageClient`、`QiniuProperties` | 查询重构、去重管线、批量生成、派生 URL |
| 控制器 | `ImageController` | list 参数扩展、regenerate/n、快照改写 |
| 前端 | `ImageLibrary.vue`、`StepPreview.vue`(抽屉部分)、`api/index.js` | 服务端筛选、缩略图、重生成/批量 |

不变式：`ImageStorage` 抽象接口签名不动（供应商可替换性保持）；预览/发布渲染链路（PreviewService/PublishService）不改——它们经 `ImageService.publicUrl(id)` 取原图 URL，交付层 webp 不影响 wenyan 拉原图。

## 2. schema 变更（幂等，追加在 S6 段后）

```sql
-- R3 去重:内容哈希(sha256 hex,64 字符);存量 NULL 允许,仅新增入库必填
ALTER TABLE sparkora_image_asset ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_image_asset_hash ON sparkora_image_asset(content_hash);
-- R4 生成留档:实际命中模型 / 请求尺寸(size 原样存,auto 存 NULL)
ALTER TABLE sparkora_image_asset ADD COLUMN IF NOT EXISTS gen_model VARCHAR(100);
ALTER TABLE sparkora_image_asset ADD COLUMN IF NOT EXISTS gen_size VARCHAR(20);
-- R1 筛选辅助索引
CREATE INDEX IF NOT EXISTS idx_image_asset_source ON sparkora_image_asset(source);
```

keyword 检索 MVP 用 `ILIKE '%kw%'`（图库规模 ≤ 数千行，pg trgm/全文索引属过度设计；契约不变，规模上来再迁移）。

## 3. 接口契约（spec §10 将同步改写）

### 3.1 `GET /api/images`（改）

```
?projectId=&source=&keyword=&page=1&size=24
→ R<PageResult<ImageAssetEntity>>   // {rows,total,page,size};rows 内每条已填 url(+thumbUrl)
```

- 兼容：旧调用（仅 projectId）语义不变（page/size 缺省 1/24 分页）。唯一 breaking 是「无参返回全量」消失，消费方仅 ImageLibrary 与抽屉，均本次同步改造。
- `source` 校验白名单（upload/ai-text2img/ai-img2img/byd），非法值 400。
- `keyword` 对 `file_name` / `prompt_text` 做 `ILIKE`。

### 3.2 `POST /api/images/generate-text` / `generate-from-image`（改）

- 入参从裸 Map 收口为 `@Valid` DTO（`ImageGenDTO`：prompt 必填、size 白名单复用 `ALLOWED_SIZES`、n 默认 1 上限 4；projectId/refImageId 沿用现有健壮解析）。
- 响应改 `R<List<ImageAssetEntity>>`（n 张候选，逐张入库，顺序即生成顺序）。**响应形状从对象变数组**——消费方仅 StepPreview 抽屉，同步改造，不做双写兼容。

### 3.3 `POST /api/images/{id}/regenerate`（新）

```
→ R<List<ImageAssetEntity>>   // 复用源图 prompt/gen_size/n=1,产新图(不覆盖源图)
```

- 源图必须 source∈{ai-text2img,ai-img2img} 且 prompt_text 非空；img2img 的 ref_image_id 复用（参考图已删则 400 提示）。

### 3.4 `GET /api/projects/{id}/images` 快照（改写语义）

```
→ { currentVersionId, coverImageId, bodyImageIds[], coverImage?, bodyImages[] }
```

- `images`（全量图库）**移除**；新增 `coverImage`（服务端解析对象含 url）/`bodyImages`（按 bodyImageIds 顺序）。消费方：
  - `StepPreview.vue`：已选封面/插图回显改用 coverImage/bodyImages；选图网格改调 3.1 分页接口（抽屉内加同款筛选 + 触底加载）。
  - `StepPublish.vue:209`：封面 URL 改读 `coverImage.url`（语义天然兼容，仅字段来源变化）。

## 4. 服务层数据流

### 4.1 入库管线（统一入口，去重内聚）

```
bytes ──sha256──▶ contentHash
        │
        ├─▶ imageMapper 查 content_hash 命中?
        │     ├─ 命中 → 不传图床,返回已有 entity(dedupeHit=true,前端提示复用)
        │     └─ 未命中 → imageStorage.upload → insert(hash, genModel?, genSize?)
```

- 去重作用于全部四来源（upload/文生图/图生图/BYD）——BYD 额外收益：车型同步幂等重跑不重复占图床。
- 并发竞态（同哈希双写）容忍：不加唯一索引（存量 NULL 行冲突），先查后插，竞态窗口最多多传一份对象，可接受。
- entity 增非持久化字段 `dedupeHit:Boolean`（同 url 先例），复用提示用。

### 4.2 批量生成

```
ImageService.generateText2Image(projectId, prompt, size, n, operator)
  → for i in 1..n: aiImageClient.generateText2Image(prompt, size) → saveGenerated(…)
  → List<ImageAssetEntity>   // 单张失败:跳过继续,全部失败才抛 AiException
```

- `AiImageClient` 改造：`parseFirstUrl` → `parseAllUrls`（取 `data[].url/b64_json` 全量），返回结构新增「URL 列表 + 实际命中模型名」（留档 gen_model）。
- `n` 语义：**后端循环 n 次单张调用**而非传 provider 的 n——axonhub 兼容层 n 不可靠，循环使单张失败隔离、每张独立走模型轮询。

### 4.3 七牛派生 URL（交付层 webp）

```
QiniuProperties.thumbUrl(key) = publicUrl(key) + "?imageView2/2/w/360/format/webp"
```

- `ImageService.fillUrl()` 同源产出 `url`(原图) + `thumbUrl`(imageView2 w=360/webp)；列表/抽屉网格用 thumbUrl，大图预览用 url。
- `ImageStorage` 接口**不加**派生方法（派生 URL 是七牛特性，塞进抽象接口会污染供应商中立性）——`ImageService` 经 `ObjectProvider<QiniuProperties>` 可选注入取用；未来换供应商时注入为空则 thumbUrl=url 降级，零业务改动。
- wenyan 拉图、公众号发布均用原图 url，不受影响。

### 4.4 regenerate

`ImageService.regenerate(id, operator)`：读源图 → 校验 source/prompt → 按 source 分派 t2i/i2i（i2i 的 ref 经 storageKey 取 bytes）→ 复用源图 gen_size（空则 auto）→ 产新记录。产物为新记录（不覆盖源图），前端生成后定位到新图。

## 5. 前端改造

- `ImageLibrary.vue`：筛选条加来源下拉 + 关键字输入（el-input 防抖 300ms）+ el-pagination（size 24）；移除 matchFilter 前端过滤；网格 src 改 thumbUrl。
- `StepPreview.vue` 抽屉：「从图库选择」网格改分页接口 + 来源筛选 + 触底加载（page++）；文生图表单加 n 选择(1/2/4)；响应按列表渲染候选逐张可选用；AI 图卡加「重新生成」按钮。
- `api/index.js`：imageApi.list 参数扩展、generateText/generateFromImage 传 n、新增 regenerate。
- 移动端单列、触控 ≥44px 沿用现有网格样式。

## 6. 兼容与回滚

- schema 全部 `ADD COLUMN IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS`，幂等可重放；新列可空，旧数据不受影响。
- 契约变化两处（快照 images→coverImage/bodyImages、generate 响应对象→数组），消费方均本次同步改造，无外部 API 消费者。
- 回滚点：M1/M2/M3 各自独立成 commit，按里程碑 revert。快照语义改写是最高风险点（两个消费方），放 M1 最后一步并配手测清单。

## 7. 权衡记录

| 决策 | 备选 | 取舍理由 |
|---|---|---|
| webp 走交付层 | 入库转码(原生库/pfop) | Java 无原生 webp 编码器，pfop 异步+回调复杂度高；交付层零转码成本即时生效 |
| 批量生成后端循环 n 次 | provider n 参数一次调 | axonhub 兼容层 n 不可靠；循环使单张失败隔离、模型轮询独立 |
| keyword 用 ILIKE | pg trgm/全文索引 | 图库规模小(数千)，索引复杂度不值；契约不变留升级空间 |
| 快照收缩+新字段 | 保持全量 images 加分页参数 | 全量口径与分页化根本矛盾；服务端解析 coverImage 消灭前端 find 逻辑 |
| hash 无唯一索引 | partial unique index | 存量 NULL 行兼容复杂；先查后插竞态代价(偶尔多传一对象)可接受 |