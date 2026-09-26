# 后端：图生图多参考图契约（多 image part + 统一集合接口）

## Goal

后端图生图链路支持**多张参考图**：AI 客户端对 `/v1/images/edits` 发重复 `image` part；控制器接口接收「上传文件列表 + 图库 id 列表」并合并为有序参考图集合（1~4），逐张校验；请求体上限适配 4 张大图。

## Background（勘察结论）

- `AiImageClient.generateImage2Image`（`AiImageClient.java:85`）当前单图：`byte[] refImageBytes` → 单个 `image` part（:98）。
- `ImageController.generateFromImageUpload`（`:191`）当前 `MultipartFile file` 单值；`generateFromImage`（`:172`，JSON）当前 `refImageId` 单值。
- 网关实测（`gpt-image-2` @ `axo.caiqz.cn`）：重复 `image` part 2/4/6 张均返回有效 URL。
- 复用件：`ImageService.readValidatedImage`（`:133`，逐张校验）、`imageStorage.download`（图库参考图取字节）、`ensureExt`/`safeName`/`sniffExt`/`fileBaseName`/`normalizeSize`、控制器 `splitTags`（`:127`）。
- `application.yml:15-16`：`max-file-size=${IMAGE_MAX_UPLOAD_MB:10}MB`、`max-request-size:15MB`（4 张需上调）。

## Requirements

- R1 `AiImageClient.generateImage2Image(prompt, List<byte[]> refs, List<String> names, size)`：保序重复 `image` part；空集合/长度不匹配抛 `AiException`；模型轮询语义不变。
- R2 `ImageService.generateImage2ImageFromUpload` 增加「图库 refImageIds」入参，与上传文件合并为**有序**参考图列表（先 files 后 refImageIds），统一直传 AI；总数校验 1~4。
- R3 控制器 `generateFromImageUpload` 改为 `files`（`MultipartFile[]`）+ `refImageIds`（`Long[]`）集合参数；保留 prompt/projectId/size/n/tags。
- R4 逐张校验：文件走 `readValidatedImage`；图库 id 走 `selectById` + `download`（不存在 → 400）。
- R5 上限校验与文案：0 → 「请至少选择 1 张参考图」；>4 → 「最多支持 4 张参考图」。
- R6 multipart 请求体上限上调（新增 `IMAGE_MAX_REQUEST_MB`，默认 45），同步 `.env.example`。
- R7 `ref_image_id` 规则：结果参考图数==1 且来源为图库 → 落该 id；其余 → NULL。
- R8 `generate-from-image`（JSON 单图）与 `/{id}/regenerate` 契约**不动**（零回归）。

## Acceptance Criteria

- [ ] AC-1 `AiImageClient` 多图：对同一模型发送 N 个 `image` part（保序），2~4 张可成功；空/不匹配集合抛异常。
- [ ] AC-2 `POST /api/images/generate-from-image-upload` 接受 `files[]`（≥1）成功生成；接受 `refImageIds[]`（图库多选）成功生成；两者混合成功生成。
- [ ] AC-3 参考图数 0 → 400「请至少选择 1 张参考图」；>4 → 400「最多支持 4 张参考图」。
- [ ] AC-4 任一文件超限/格式非法 → 400 对应文案（复用现有口径）。
- [ ] AC-5 任一 `refImageIds` 不存在 → 400「参考图不存在」。
- [ ] AC-6 结果 `ref_image_id`：多图或仅文件来源 → NULL；单图图库来源 → 该 id。
- [ ] AC-7 `generate-from-image` / `regenerate` 行为不变（回归）。
- [ ] AC-8 `mvn -q -DskipTests compile` + `mvn test` 通过；`.env.example` 含 `IMAGE_MAX_REQUEST_MB`。

## Technical Notes

- 有序性：前端展示顺序 = 提交顺序 = `files` 先、`refImageIds` 后；后端不得重排。
- 文件名：上传文件沿用现有 `ensureExt(safeName(originalFilename, "reference.png"), sniffExt(bytes))` 逻辑；图库参考图用 `fileBaseName(ref.getFileName())`（与单图图库路径一致）。
- Multipart 参数绑定：同名多值 `files` 映射 `MultipartFile[]`；`refImageIds` 映射 `Long[]`（Spring 同名多值自动成数组）。
- 无 schema 变更。

## Dependencies

- 无前置子任务。**本子任务完成后**，前端子任务 `09-26-img2img-multi-ref-frontend` 依赖其字段契约（`files[]`/`refImageIds[]`/顺序/上限）。
