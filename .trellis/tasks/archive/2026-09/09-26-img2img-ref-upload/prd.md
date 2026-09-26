# 后端：图生图参考图直传（不落图库）

> 父任务：`.trellis/tasks/09-26-image-gen-ux-paste`

## Goal

新增一个 multipart 图生图接口：参考图以文件字节**直传 AI**（`/v1/images/edits`），**参考图不进入图库**；生成结果照旧走统一入库管线。为前端「粘贴 / 本地文件」参考图提供后端能力。

## Background（现状勘察结论）

- 现有图生图 `POST /api/images/generate-from-image`（`ImageController.java:172`）走 JSON `ImageGenDTO`，强依赖 `refImageId`；服务层 `ImageService.generateImage2Image:169` 用 `imageMapper.selectById(refImageId)` + `imageStorage.download(storageKey)` 取参考图字节。**该路径要求参考图已入库**，不满足「不落图库」。
- AI 调用层已具备所需能力：`AiImageClient.generateImage2Image(prompt, byte[] refImageBytes, refFileName, size)`（`AiImageClient.java:85`），multipart `/v1/images/edits`，按序轮询候选模型。**无需改 AI 客户端**。
- 上传校验逻辑（扩展名白名单 + 魔数嗅探 + 大小上限）已在 `ImageService.upload:103` 内联，可提取复用。
- 上传大小上限来自 `IMAGE_MAX_UPLOAD_MB`（`ImageProperties.maxUploadMb`，默认 10）。

## Requirements

- R1 新增接口：`POST /api/images/generate-from-image-upload`，`multipart/form-data`，字段：
  - `file`（必填）：参考图（png/jpg/jpeg/webp，≤ `IMAGE_MAX_UPLOAD_MB`）。
  - `prompt`（必填，非空）。
  - `projectId`（可选，可空=全局图库）。
  - `size`（可选，白名单 `1024x1024`/`1536x1024`/`1024x1536`，非法 → 400）。
  - `n`（可选，1~4，默认 1，超限收敛不报错；与现有语义一致）。
  - `tags`（可选，多值或单值内逗号分隔）。
- R2 行为：校验参考图（大小/扩展名/魔数）→ 取字节 → 循环 n 次调用 `aiImageClient.generateImage2Image(prompt, bytes, fileName, size)` → 命中结果走既有 `saveGenerated(...)` 入库（source=`ai-img2img`）；**参考图本身不入库、不上传图床、不嵌向量**。
- R3 `refImageId` 语义：参考图未入库，故生成结果的 `ref_image_id` 落 **NULL**（无法自引用）。由此该结果的「重生成」不可走后端 `/regenerate`（见前端 R5：前端会话缓存代偿）。
- R4 响应：与既有生成接口一致，`R<List<ImageAssetEntity>>`（n 张候选逐张入库；单张失败跳过、全部失败 `R.fail(500)` 含候选模型错误明细）。
- R5 权限：`@PreAuthorize("hasAnyRole('ADMIN','EDITOR')")`；`projectId` 非空时 `ensureProject` 校验存在。
- R6 错误矩阵（全部 HTTP 200 业务包 `R.fail` 或既有异常映射）：
  - 缺 `file` / 空文件 → 400「请选择要上传的参考图」。
  - 大小超限 → 400（复用 `图片超过大小上限 NMB`）。
  - 非法扩展名/魔数不符 → 400（复用 `仅支持 png/jpg/webp 格式图片` / `文件内容不是有效的 png/jpg/webp 图片`）。
  - `prompt` 空 → 400「请输入生成提示词（prompt）」。
  - `size` 非法 → 400（复用 `normalizeSize` 校验语义）。
  - 全部模型失败 → 500（`AiException` 明细）。
- R7 不改动现有 `ImageGenDTO`、`generate-from-image`（图库参考图 JSON 路径）、`regenerate` 契约（零回归）。

## Acceptance Criteria

- [ ] AC-1 `POST /api/images/generate-from-image-upload` 收 multipart（file/prompt/projectId?/size?/n?/tags?[]），成功返回 n 张 `ImageAssetEntity` 列表，`source=ai-img2img`。
- [ ] AC-2 调用后查图库（`GET /api/images`）：**不含**作为参考图上传的那张（参考图未落库）；生成结果在图库中 `refImageId` 为 null。
- [ ] AC-3 参考图大小/格式/魔数校验与现有 upload 同口径；非法输入返回对应 400 中文消息。
- [ ] AC-4 `size` 非法值 400；`n` 超限收敛为 4 不报错（与既有生成接口一致）。
- [ ] AC-5 VIEWER 调用 403；ADMIN/EDITOR 通过。
- [ ] AC-6 全部候选模型失败时 `R.fail(500, 含模型错误明细)`。
- [ ] AC-7 `mvn -q -DskipTests compile` 通过；现有 `ImageControllerTest`（若有）/相关测试不回归。
- [ ] AC-8 现有 `generate-from-image`（JSON/refImageId）与 `/regenerate` 行为不变。

## Dependencies

- 无前置子任务；可独立先做（前端 R2/R3/R5 依赖本接口）。

## Open Questions

（无）
