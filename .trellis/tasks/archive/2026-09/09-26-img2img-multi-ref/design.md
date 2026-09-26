# 设计：图生图多参考图（父任务集成契约）

## Architecture & Boundaries

```
[前端] AiImageDrawer 参考图集合(≤4)
         │  本地/粘贴 File[] + 图库 id[]
         ▼  multipart  files[] + refImageIds[] + prompt + ...
[后端] ImageController.generateFromImageUpload(...)
         │  逐张校验(readValidatedImage / 图库下载)
         ▼  有序字节列表 List<byte[]>
[AI]   AiImageClient.generateImage2Image(prompt, List<ref>, List<name>, size)
         │  重复 image part(保序)
         ▼  POST {AI_BASE_URL}/v1/images/edits
```

## Unified Contract（权威·父子共守）

### 接口：`POST /api/images/generate-from-image-upload`（multipart）

取代旧的 `generate-from-image-upload`（单 file）**与** `generate-from-image`（JSON 单 refImageId）在「图生图」场景下的用法——前端图生图统一走此多图接口；旧 JSON 接口保留（兼容，不在本任务删除）。

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `files` | MultipartFile[] | 否* | 本地/粘贴参考图；逐张 png/jpg/webp、≤`IMAGE_MAX_UPLOAD_MB`、魔数校验 |
| `refImageIds` | Long[] | 否* | 图库参考图 id；须存在 |
| `prompt` | String | 是 | 非空 |
| `projectId` | Long | 否 | 存在则校验 |
| `size` | String | 否 | 归一化（沿用 `normalizeSize`） |
| `n` | Integer | 否 | 默认 1，1~4（生成张数，与参考图数无关） |
| `tags` | String[] | 否 | 同名多值/逗号分隔（沿用 `splitTags`） |

\* `files` 与 `refImageIds` 之和须 **1~4**；0 → 400「请至少选择 1 张参考图」；>4 → 400「最多支持 4 张参考图」。

**参考图顺序**：先 `files` 后 `refImageIds`（稳定保序；前端负责展示顺序与之一致）。

**响应**：`R<List<ImageAssetEntity>>`；多张参考图时结果 `ref_image_id` 落 NULL（决策 4）；仅「单张且为图库来源」时落该 id。

**错误矩阵**：
- 参考图数 0 / >4 → 400（文案如上）。
- 任一文件超限 → 400「图片超过大小上限 {N}MB」。
- 任一文件格式非法 → 400「仅支持 png/jpg/webp 格式图片」/「文件内容不是有效的 png/jpg/webp 图片」。
- 任一 refImageId 不存在 → 400「参考图不存在」。
- multipart 超请求体上限 → 400「上传失败: ...」。

### 客户端：`AiImageClient.generateImage2Image`

```java
// 由单图签名演进为多图（保序）
public GenResult generateImage2Image(String prompt,
                                     List<byte[]> refImageBytesList,
                                     List<String> refFileNames,
                                     String size)
```

- 对每个模型：`prompt`/`size`/`n=1` + 依次 `body.add("image", ByteArrayResource)`（重复 part，保序）。
- 空集合 / 长度不匹配 → `AiException`。
- 模型轮询与错误聚合语义不变。

## Data Flow & Compatibility

- **保留**：`generate-from-image`（JSON 单 refImageId）、`/{id}/regenerate` 契约不动 → 旧前端/旧数据零回归。
- **演进**：`generate-from-image-upload` 的 `file` 单值改为 `files` 多值 + 新增 `refImageIds`。因该接口为 09-26 新增且**当前部署从未生效**（405），无外部调用方，可安全改签名。
- **schema**：无表结构变更。
- **配置**：新增 `IMAGE_MAX_REQUEST_MB`（默认 `max(15, 4*IMAGE_MAX_UPLOAD_MB)` 语义，见 implement.md），同步 `.env.example`。

## Trade-offs

- 选「统一多图接口」而保留 JSON 旧接口：接受短期两条路径并存（旧路径仅余单图图库用途），换取前端单一提交入口与零回归。
- 选「会话缓存」而非多对多落库：接受刷新后本地来源不可重生成，换取零 schema 变更。

## Rollout / Rollback

- 无 DB 迁移，回滚 = revert 提交 + 重建容器。
- 部署：代码烘焙进镜像，必须 `docker compose up -d --build`（本任务 AC-7）。

## Open Questions

（无）
