# 风格库

> 回链：[系统说明总览](../README.md)

职责：管理「风格画像」——用户提供样文，AI 提炼为 `toneGuidance`（生成时的 system prompt 片段），供版本生成/深度写作/仿写选风格使用。

---

## 1. 数据模型（`StyleProfileEntity` → 表 `sparkora_style_profile`）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL | 主键 |
| name | VARCHAR(64) NOT NULL | 风格名（用户可改） |
| description | VARCHAR(500) | 风格简述 |
| tone_guidance | TEXT | 提供给生成模型的语气/结构指令（system prompt 片段） |
| source_excerpt | TEXT | 提炼自哪段原文（截断保留，便于回溯） |
| enabled | BOOLEAN NOT NULL DEFAULT TRUE | 是否启用（`list?enabledOnly=true` 过滤） |
| created_at | TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP | 创建时间 |

> 实体注释：`toneGuidance` 是「给正文生成模型用的语气/结构指令」，生成版本时作为 system prompt 片段。

---

## 2. 服务（`com.sparkora.service.StyleService`）

- `create`：`name` 必填（空 → `IllegalArgumentException`「风格名必填」）；`enabled` 缺省 true；写 `created_at` 后 insert。
- `list(enabledOnly)`：`enabledOnly=true` 时过滤 `enabled=true`；按 `id` 倒序。
- `get` / `update` / `delete`：常规 CRUD（`delete` 逻辑删）。
- `draft(sourceText, name)`：**AI 提炼但入库前预览**（09-10-style-library-enhance 拆两段）——`sourceText` 空 → 400「样文不能为空」；AI 提炼 `{name, description, toneGuidance}`，AI 输出 JSON 走 `AiClient.sanitizeAiJson` 容错（剥围栏 + 转义裸控制字符）；返回**不入库**（`id=null`）；`sourceText` 截断送 AI（全文不入库）。
- `extract = draft + createdAt + insert`（契约保持不变，一步式提炼入库）。

---

## 3. 接口契约

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| GET | `/api/styles` | 三角色 | `?enabledOnly=` | `{styles[]}`（`list`，id 倒序） |
| GET | `/api/styles/{id}` | 三角色 | — | `{style}` |
| POST | `/api/styles` | ADMIN/EDITOR | `StyleProfileEntity` JSON（`name` 必填） | `{style}` |
| PUT | `/api/styles/{id}` | ADMIN/EDITOR | `StyleProfileEntity` JSON（`id` 取自路径） | `{style}`（更新后重查返回） |
| DELETE | `/api/styles/{id}` | **ADMIN** | — | `{ok:true}`（逻辑删） |
| POST | `/api/styles/extract` | ADMIN/EDITOR | `{name, sourceText}` | `{style}`（AI 提炼**入库**；异常 `R.fail(500, msg)`） |
| POST | `/api/styles/extract/preview` | ADMIN/EDITOR | `{name?, sourceText}` | `R<StyleProfileEntity>`（AI 提炼**不入库**，`id=null`；两步式提炼预览，人工修改后入库走 `POST /api/styles`；`sourceText` 空 → 400；异常 500） |

- 前端：`views/StyleLibrary.vue`，`api/index.js` 的 `styleApi`（`extract`/`extractPreview` 前端超时放宽至 120s）。
- 生成侧消费：`VersionService`（主题/仿写 prompt 的 `style.toneGuidance` + 统一强化句）、`DeepController`/`DeepWriterService`（`/deep/generate` 按 `styleId` 后端回查风格表取 `toneGuidance`/`name` 注入 system prompt；查无 → 400「风格不存在或已删除」）。
- 仿写风格推荐：`ImitationService.analyze` 产出 `style_recommendations`（`[{styleId,name,reason,matchScore}]`，只保留库内 `styleId` 防御截断），见 [imitation.md](imitation.md)。

---

## 4. 已知限制

- 「两步式提炼预览」的人工修改**不落库**，需走 `POST /api/styles` 入库。
- 风格删除为逻辑删；历史版本/仿写推荐中引用的 `styleId` 可能已失效（深度生成时后端查无返回 400）。
