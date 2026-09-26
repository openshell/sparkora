# 设计：后端多参考图契约

## Signatures

```java
// AiImageClient.java
public GenResult generateImage2Image(String prompt,
                                     List<byte[]> refImageBytesList,
                                     List<String> refFileNames,
                                     String size)

// ImageService.java
public List<ImageAssetEntity> generateImage2ImageFromUpload(
        Long projectId, List<MultipartFile> files, List<Long> refImageIds,
        String prompt, String size, int n, List<String> tags, String operator)

// ImageController.java
@PostMapping("/generate-from-image-upload")
public R<List<ImageAssetEntity>> generateFromImageUpload(
        @RequestParam(value = "files", required = false) List<MultipartFile> files,
        @RequestParam(value = "refImageIds", required = false) List<Long> refImageIds,
        @RequestParam(value = "prompt", required = false) String prompt,
        @RequestParam(required = false) Long projectId,
        @RequestParam(required = false) String size,
        @RequestParam(defaultValue = "1") Integer n,
        @RequestParam(required = false) List<String> tags)
```

## Contracts

### `POST /api/images/generate-from-image-upload`（multipart）

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `files` | MultipartFile[] | 否* | 逐张 png/jpg/webp、≤`IMAGE_MAX_UPLOAD_MB`、魔数校验 |
| `refImageIds` | Long[] | 否* | 图库参考图 id，须存在 |
| `prompt` | String | 是 | 非空 |
| `projectId` | Long | 否 | 存在则校验 |
| `size` | String | 否 | 归一化 |
| `n` | Integer | 否 | 默认 1，1~4（生成张数） |
| `tags` | String[] | 否 | 同名多值/逗号分隔 |

\* `files.size()+refImageIds.size()` 须 **1~4**。

- **顺序**：先 `files`（按请求顺序）后 `refImageIds`（按请求顺序）。
- **响应**：`R<List<ImageAssetEntity>>`。
- **`ref_image_id` 落值规则**：参考图数==1 且该参考图来自 `refImageIds` → 落该 id；否则 NULL。

### 环境键

| Key | 默认 | 说明 |
|---|---|---|
| `IMAGE_MAX_UPLOAD_MB` | 10 | 单张上限（已有） |
| `IMAGE_MAX_REQUEST_MB` | 45 | multipart 请求体上限（新增；≥4×单张 + 开销） |

`application.yml`：`spring.servlet.multipart.max-request-size: ${IMAGE_MAX_REQUEST_MB:45}MB`。

## Validation & Error Matrix

| 条件 | 结果 |
|---|---|
| 参考图数 == 0 | 400「请至少选择 1 张参考图」 |
| 参考图数 > 4 | 400「最多支持 4 张参考图」 |
| 某文件超 `IMAGE_MAX_UPLOAD_MB` | 400「图片超过大小上限 {N}MB」 |
| 某文件扩展名非白名单 | 400「仅支持 png/jpg/webp 格式图片」 |
| 某文件魔数不符 | 400「文件内容不是有效的 png/jpg/webp 图片」 |
| 某 `refImageId` 不存在 | 400「参考图不存在」 |
| prompt 空 | 400「请输入生成提示词（prompt）」 |
| multipart 超请求体上限 | 400「上传失败: ...」 |
| AI 全模型失败 | 500（`AiException` 文案聚合） |

## Good / Base / Bad

- **Good**：`files=[a.png,b.png]` + `refImageIds=[7]` → 3 张参考图，保序 [a,b,#7]，`ref_image_id=null`。
- **Base**：仅 `refImageIds=[7]` → 1 张图库参考图，`ref_image_id=7`（等价既有单图行为）。
- **Bad**：`files=[a,b,c,d,e]` → 400「最多支持 4 张参考图」；`files=[]`,`refImageIds=[]` → 400「请至少选择 1 张参考图」。

## Tests Required

> 项目 `src/test` 为空。若新增测试，断言点：
- `AiImageClient`：以 mock RestClient/本地 stub 断言发出 N 个 `image` part 且顺序一致；空/长度不匹配抛 `AiException`。
- 若无法加集成测试：以 `mvn -q -DskipTests compile` + 手工 curl（见 implement.md）为证据。

## Wrong vs Correct

```java
// Wrong：多图时用单值 file 参数，或重排参考图（前端顺序被打乱）
body.add("image", resource);                      // 仅第一张
// Correct：保序追加
for (int i = 0; i < refImageBytesList.size(); i++) body.add("image", resourceOf(i));
```

```java
// Wrong：多图结果仍落 ref_image_id（列只能存一个 → 误导重生成）
saveGenerated(..., refImageId, ...);
// Correct：仅单图库来源落 id，其余 null
Long refForResult = (refs.size() == 1 && fromLibrary) ? libId : null;
```

## Rollback

revert 提交 + `docker compose up -d --build`；无 DB 迁移。
