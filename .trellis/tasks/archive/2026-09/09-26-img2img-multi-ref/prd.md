# 图生图多参考图支持

## Goal

图生图的参考图从「单张」升级为「多张（上限 4）」，且粘贴 / 本地文件 / 图库三种来源可**混合**选择；同时修复「点击生图报 405」——该 405 经查是**部署容器未重建**（非代码缺陷），需按 AGENTS.md 约定重建验证。

## Background（现状勘察结论）

- 图生图整链目前**单图**：
  - 前端 `frontend/src/components/AiImageDrawer.vue` 单参考图模型（`refFile` / `refLibraryImage`），来源二选一。
  - 后端 `ImageController.java:172`（JSON `generate-from-image`，`refImageId` 单值）与 `:191`（multipart `generate-from-image-upload`，`file` 单值）两条路径并存。
  - `AiImageClient.java:85` `generateImage2Image(prompt, byte[] refImageBytes, String refFileName, String size)` 只往 `/v1/images/edits` 塞**一个** `image` part（:98）。
- **405 根因（已实测确认，非代码缺陷）**：
  - 源码 `ImageController.java:191` 的 `@PostMapping("/generate-from-image-upload")` 存在且可编译。
  - 运行中的后端是 Docker 容器 `sparkora-backend:local`（镜像 build 于 2026-09-26 17:33），早于该接口所在提交 `cdc1ecf`（22:21）；实测 `/generate-from-image` → 200、`/generate-from-image-upload` → 405，且任意未映射 `/api/images/*` POST 也返回 405，符合「路由不存在」表现。
  - 修复动作：`docker compose up -d --build`（AGENTS.md：代码烘焙进镜像，restart / 裸 up -d 不重建）。
- **AI 网关多图能力（已实测确认）**：对 `AI_BASE_URL=https://axo.caiqz.cn`、`AI_IMAGE_MODEL=gpt-image-2`，`POST /v1/images/edits` **重复 `image` part** 可成功，实测 2 / 4 / 6 张均返回有效 `data[].url`。即「上限 4」是**产品选择**，不是网关限制。

## Confirmed Decisions（用户拍板）

1. **全来源多张 + 上限 4**：粘贴/拖拽（可多文件）、本地文件、图库（多选）均支持多张；总数上限 4；不同来源可**混合**。
2. **统一为一个 multipart 接口**：新接口以 `files[]`（本地/粘贴）+ `refImageIds[]`（图库）合并参考图集合，后端统一解析为有序参考图字节列表后直传 AI。取代当前「JSON 单图 + multipart 单图」两条路径。
3. 405 属部署问题：本任务只做**重建验证**，不为此改代码（除非重建后仍复现，再回退诊断）。

## Scope

### In Scope

- 后端：图生图参考图改为**多张**——`AiImageClient` 支持多 `image` part；`ImageService` 支持「多个已入库 refImageId + 多个上传文件」合并；控制器接口接收集合参数（上限 4、非空校验、逐张校验复用 `readValidatedImage`）。
- 前端：`AiImageDrawer` 参考图区改为多张管理（缩略图列表 + 逐张移除 + 追加），支持粘贴多文件 / 本地多选 / 图库多选与混合；提交改为统一 multipart。
- 文档：`docs/spec/image.md` 图生图字段级契约与 UI 职责同步（接口由两条并为一条多图契约）。
- 验证：`docker compose up -d --build` 后确认 `generate-from-image-upload` 可达（非 405）。

### Out of Scope

- 参考图持久化 / 跨会话；多参考图的后端 `ref_image_id` 多对多建模（见 Open Questions 决策）。
- 文生图链路改动、生成历史 / prompt 复用。
- 图库整体 UI 重设计（仅生图抽屉参考图区）。

## Requirements

- R1 后端 `/v1/images/edits` 支持多张参考图（重复 `image` part，保序）。
- R2 后端图生图接口接收多参考图集合：上传文件列表 + 图库 id 列表，合并为有序字节列表；总数 1~4。
- R3 前端参考图区支持多张：三来源均可追加、混合、逐张移除；超 4 张拒绝并提示。
- R4 前端统一走多图 multipart 接口；移除旧的单图 JSON 调用路径。
- R5 逐张校验：任一参考图超限 / 格式非法 → 400 明确提示（复用现有文案口径）。
- R6 多参考图生成的图，会话内「重生成」复用整组参考图 + prompt（沿用会话缓存机制）。
- R7 重建部署容器后，`/api/images/generate-from-image-upload` 不再返回 405。

## Acceptance Criteria

- [ ] AC-1 图库页与预览页两入口均可：选择/粘贴 2~4 张参考图（含混合来源）→ 生成成功，参考图不进入图库。
- [ ] AC-2 图库多选参考图（2~4 张）→ 生成成功（不回归既有单图图库路径的能力）。
- [ ] AC-3 选择第 5 张时被拒绝并给出「最多 4 张参考图」提示；0 张时提交被拒并提示需选择参考图。
- [ ] AC-4 参考图列表可逐张移除后重新生成，提交的参考图集合与界面一致。
- [ ] AC-5 会话内「重生成」复用整组参考图 + prompt 产出新图；刷新后本地来源置灰 + tooltip（沿用既有语义）。
- [ ] AC-6 `mvn -q -DskipTests compile` 与 `frontend npm run build` 通过；后端相关单测通过。
- [ ] AC-7 `docker compose up -d --build` 后实测 `/api/images/generate-from-image-upload` 返回 200/400（非 405），接口可用。
- [ ] AC-8 `docs/spec/image.md` 已按多图契约更新（字段级表格同步）。

## Confirmed Decisions (cont.)

4. **多图重生成语义**：多参考图生成的结果 `ref_image_id` 落 **NULL**，重生成靠**前端会话缓存**整组复用；仅「单张参考图且为图库来源」保留 `ref_image_id` 走后端 `/regenerate`（零回归）。**不新增多对多落库**。

## Requirement Map（父 → 子）

| 需求 | 归属子任务 |
|---|---|
| R1 `/v1/images/edits` 多 `image` part（保序） | `09-26-img2img-multi-ref-backend` |
| R2 统一 multipart 接口接收集合（files[] + refImageIds[]，1~4） | `09-26-img2img-multi-ref-backend` |
| R5 逐张校验 + 上限校验 | `09-26-img2img-multi-ref-backend` |
| R3 前端多张参考图交互（追加/混合/移除/上限） | `09-26-img2img-multi-ref-frontend` |
| R4 前端统一提交多图 multipart | `09-26-img2img-multi-ref-frontend` |
| R6 会话缓存多图重生成 | `09-26-img2img-multi-ref-frontend` |
| R7 部署容器重建验证 405 | 父任务（集成收口） |
| R8 `docs/spec/image.md` 同步 | 父任务（集成收口） |

> 依赖顺序：前端 R4/R6 依赖后端 R1/R2 的新接口契约（字段名/上限）。该依赖已写入子任务 `prd.md`/`implement.md`，不依赖树位置。

## Cross-Child Acceptance Criteria

- [ ] AC-1 图库页与预览页两入口均可：选择/粘贴 2~4 张参考图（含**混合**来源）→ 生成成功，参考图不进入图库。
- [ ] AC-2 图库多选参考图（2~4 张）→ 生成成功（既有单图图库能力不回归）。
- [ ] AC-3 第 5 张被拒并提示「最多 4 张参考图」；0 张提交被拒并提示需选择参考图。
- [ ] AC-4 逐张移除后重新生成，提交集合与界面一致。
- [ ] AC-5 会话内「重生成」复用整组参考图 + prompt；刷新后本地来源置灰 + tooltip（沿用既有语义）。
- [ ] AC-6 `mvn -q -DskipTests compile` 与 `frontend npm run build` 通过；后端相关单测通过。
- [ ] AC-7 `docker compose up -d --build` 后实测 `/api/images/generate-from-image-upload` 返回 200/400（非 405）。
- [ ] AC-8 `docs/spec/image.md` 已按多图契约更新（字段级表格同步）。

## Technical Notes

- 网关已实测支持多 `image` part（2/4/6 张均成功）；4 张为**产品上限**，前后端双重校验。
- `ref_image_id` 为单列，无法表达多对多；多图结果落 NULL（决策 4）。
- 前端 `imageRefCache` 需从单 file 扩展为 ref 列表以支撑多图重生成。
- multipart 请求体上限需上调（4×`IMAGE_MAX_UPLOAD_MB`）；新增 `IMAGE_MAX_REQUEST_MB`（见 design.md）。

## Open Questions

（无 — 阻塞项已全部澄清）
