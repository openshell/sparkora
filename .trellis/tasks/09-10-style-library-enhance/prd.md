# 完善风格库

## Goal

风格库增强:① 支持直接手工新增风格;② 完善「样本提炼」体验;③ 强化/统一风格对文章生成(仿写多版本 + 深度生成)的作用。

## Background / 现状(已探明事实)

### 后端
- `StyleController.java`(`/api/styles`):GET 列表(enabledOnly)、GET 单个、POST 新建、PUT 更新、DELETE(仅 ADMIN)、POST `/extract` 提炼。
  - **POST/PUT 直接以 Entity 收参,未走项目惯例的 @Valid DTO**;异常处理 extract 直接 R.fail(500, ex.getMessage())。
  - 实体字段:id、name(≤64)、description(≤500)、tone_guidance(TEXT,唯一风格数据通道)、source_excerpt(TEXT 提炼来源截断)、enabled、created_at。无 updated_by/updated_at/deleted。
- `StyleService.extract(sourceText, name)`:样文截 4000 字,prompt 内嵌 Java text block,输出 JSON {name(2-6字), description(20-40字), toneGuidance(2-4句)};`aiClient.chatJson`;source_excerpt 截 2000 字;命名优先 AI > 用户 > 兜底。
- 风格注入三链路口径不一致:
  1. 仿写多版本 `VersionService.generateOne`:toneGuidance 进 **system prompt**(L175-181);
  2. 深度生成 `DeepWriterService.write`:前端取好 toneGuidance 以 stylePrompt 字符串传入,进 **user prompt**「风格要求:\n」(L89-91);后端不回查风格表;
  3. 旧多版本(主题创作,已 410 仅仿写放行):toneGuidance 进 system。
- `ImitationService.analyze`:全库 enabled 风格拼候选清单 → AI 推荐 ≤3 条(styleId/name/reason/matchScore)落 `brief.style_recommendations`。

### 前端
- 唯一页面 `StyleLibrary.vue`(/styles):列表卡片、提炼对话框(风格名+样文)、编辑对话框(name/description/toneGuidance/enabled)、删除(ADMIN)、空态/错误态。
- **缺**:手工新增入口(纯手填表单)、source_excerpt 回看、启用/停用筛选、搜索。
- 选风格 UI:`StepVersions.vue` 复选框组(仿写推荐角标)、深度链路逐风格 `generateDeep(id, briefId, toneGuidance, name)`;`?adoptStyle=` 自动预选。
- `styleApi`(api/index.js L52-58)已有六方法(extract timeout 120s)。

### 规格锚点(docs/s0-spec.md)
- §1 L67-72:6 接口权限矩阵;§3.3 L140-141 接口契约;§14 L657-711 仿写风格推荐/注入契约;§13 L588 深度生成 stylePrompt 契约;L710 AC7 空库不阻断。
- POST /api/styles 新建接口 spec 已有但前端无 UI。

## Requirements

- R1 直接新增:风格库页提供「新增风格」表单(name/description/toneGuidance/enabled),走既有 POST /api/styles;表单内额外提供「粘贴样文先提炼预填」按钮 → 调提炼能力但**不入库**,结果回填表单,人工修改后再提交入库。
- R2 样本提炼(两步式):提炼对话框改为「预览确认」流程——先调 AI 提炼得到 name/description/toneGuidance(不入库),用户可修改,点「确认入库」才落库。失败有明确中文提示。
- R3 风格对文章生效(用户已确认「统一+强化」方案):
  - 统一注入位置:风格指令统一进 system prompt(仿写多版本、深度生成)。
  - 强化 prompt:要求「风格特征必须体现」,不止于拼接字符串。
  - 深度链路改为后端回查风格表(按 styleId),不再由前端传 toneGuidance 字符串。
  - 向后兼容:深度生成接口需保持旧调用可用(前端同步改造,契约变更需更新 spec §13)。

## Acceptance Criteria

- [x] AC1 风格库页可不经 AI 直接新增风格(name 必填校验;description/toneGuidance 可填),成功后列表刷新可见。
- [x] AC1b 新增表单内可粘贴样文「提炼预填」:结果回填表单不入库,人工修改后才入库。
- [x] AC2 样本提炼为两步式:先预览 AI 结果(name/description/toneGuidance 可改)→ 确认后入库;失败有明确中文提示。
- [x] AC3 仿写多版本:选风格生成后,版本带风格名标签(style_tag),风格指令进 system prompt 且要求体现特征。(静态核验;AI 生成质量待真机抽检)
- [x] AC4 深度生成:按 styleId 由后端回查风格表,风格指令进 system prompt;版本标签为风格名。(静态核验;待真机抽检)
- [x] AC5 空风格库/未选风格不阻断:仿写推荐空数组引导、深度无风格仍可生成(现状行为保持)。
- [x] AC6 `mvn -q -DskipTests compile` 通过;`npm run build` 通过。

## Open Questions(已全部解决)

- ~~Q1 新增表单字段范围~~ → 已决策:全两步式,新增表单含「样文提炼预填」(不入库,回填表单)。
- ~~Q2 样本提炼两步式~~ → 已决策:提炼先预览(可改)再确认入库。
- ~~Q3 风格对文章生效~~ → 已决策:统一+强化(风格统一进 system prompt + 强化 prompt 要求 + 深度链路后端回查风格表)。
- ~~Q4 列表增强~~ → 已决策:不做(source_excerpt 回看、启停筛选、搜索均不纳入本次)。

## Out of Scope

- 风格库列表增强:source_excerpt 回看、启停筛选、名称搜索、排序。
- 风格强度可调参数。
- 风格表结构重构与 6 接口契约/权限矩阵变更(保持 spec §1 现状)。
- 提炼 prompt 精调以外的 AI 能力升级。