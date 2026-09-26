# Design：图生图参考图直传

> 父任务：`.trellis/tasks/09-26-image-gen-ux-paste`

## 1. 边界与职责

| 层 | 文件 | 职责 |
|---|---|---|
| Controller | `src/main/java/com/sparkora/web/controller/ImageController.java` | 新增 `POST /generate-from-image-upload`，multipart 解析、权限、参数校验、`R<T>` 包装 |
| Service | `src/main/java/com/sparkora/service/ImageService.java` | 新增 `generateImage2ImageFromUpload(...)`；提取可复用的参考图校验（大小/扩展名/魔数） |
| AI 客户端 | `src/main/java/com/sparkora/ai/AiImageClient.java` | **不改**：复用 `generateImage2Image(prompt, bytes, fileName, size)` |
| DTO | `src/main/java/com/sparkora/domain/dto/*` | **不新增** DTO：multipart 用 `@RequestParam`（对齐现有 `upload` 写法） |

## 2. 数据流

```
前端 multipart(file, prompt, projectId?, size?, n?, tags?)
  → ImageController.generateFromImageUpload
      ├─ SecurityUtil.require() → operator
      ├─ size 校验（ImageService.normalizeSize）; n 收敛
      ├─ service.generateImage2ImageFromUpload(projectId, file, prompt, size, n, tags, operator)
      │     ├─ ensureProject(projectId)            // 非空时
      │     ├─ 校验 file（大小/扩展名/魔数，复用提取的校验方法）→ byte[] bytes
      │     ├─ loop n:
      │     │     aiImageClient.generateImage2Image(prompt, bytes, fileName, normSize)  // 不落库
      │     │     saveGenerated(projectId, g.url(), prompt, null /*refImageId*/, "ai-img2img", operator, g.model(), normSize, normTags)
      │     └─ 返回 List<ImageAssetEntity>
      └─ R.ok(list) / 异常 → R.fail(400/500)
```

关键点：参考图字节**只在内存**，不调 `imageStorage.upload`，不 `imageMapper.insert`，不 `embedQuietly`。生成结果仍走 `saveGenerated`（内部 `persistOrReuse`：去重/转存图床/落标/嵌向量）。

## 3. 契约细节

- **参考图校验提取**：现有 `ImageService.upload` 内联的「大小 → 扩展名 → 读字节 → 魔数嗅探」逻辑抽为一个私有方法（如 `private byte[] readValidatedImage(MultipartFile file, String emptyMsg)`），`upload` 与新方法共用，避免逻辑分叉。注意 `upload` 的错误文案为「请选择要上传的图片」；新方法空文件文案「请选择要上传的参考图」——若共用，空文件判定留调用方。
- **`ref_image_id` 落 NULL**：语义透明——参考图未入库，无法自引用。文档需显式记录，避免后人误以为遗漏。
- **fileName 传递**：`aiImageClient.generateImage2Image` 的 `refFileName` 用参考图原文件名（`file.getOriginalFilename()`，非法/空回退 `reference.png`）；仅用于 multipart filename，扩展名以魔数为准更稳妥（可复用 `ensureExt` 思路，或直接传嗅探后的扩展名）。
- **`n` 语义**：与 `generateText2Image`/`generateImage2Image` 一致——`n<1→1`，`min(n,4)`，循环单张、单张失败跳过、全失败抛 `AiException`。
- **`size`**：`normalizeSize` 校验白名单，非法 400。

## 4. 兼容性 / 回归

- 现有 `generate-from-image`（JSON/refImageId，图库参考图）保持不变。
- 现有 `/regenerate` 保持不变；本接口产生的图 `ref_image_id=null` 时，后端 `/regenerate` 会因「源图无参考图」→ 400（这是既有校验，符合预期；前端用会话缓存代偿，见前端任务 R4）。
- 不改 `ImageGenDTO` / `schema.sql`（无新列）。

## 5. 权衡

| 方案 | 取舍 |
|---|---|
| **新增 multipart 接口（选定）** | 参考图不落库，语义干净；代价是多一个接口 |
| 复用 upload + refImageId | 零后端改动，但图库被参考图污染（用户否决） |
| 后端临时存储 + TTL | 跨会话可重生成，但引入临时表/清理（用户否决） |

## 6. 风险与回滚

- 风险：`/v1/images/edits` 部分模型不支持（既有已知）；错误信息已含「改用文生图或更换 AI_IMAGE_MODELS」提示，沿用。
- 回滚：删除新增接口 + service 方法即可，无 DB 变更、无数据迁移。
