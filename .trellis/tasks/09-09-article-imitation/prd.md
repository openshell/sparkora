# 文章仿写功能：项目级新模式（粘贴原文+选定风格仿写+去图+相似度自检+风格推荐）

## Goal

为创作项目新增「文章仿写」来源模式：用户新建任务时选择仿写模式并粘贴参考原文，系统自动分析原文（题材/结构/句式）并推荐匹配的风格库风格；用户选定风格后系统仿写——保留原文的观点组织与信息脉络，以选定风格重新表达，且**不保留原文任何图片**（由用户自行配图）；仿写生成后自动本地自检与原文的相似度/重复率，超阈值警示，防止过度贴近原文的版权风险。

**产品价值**：把「看到一篇好文章想写成自己风格的稿子」这一高频新媒体需求产品化，并内建防洗稿自检。

## Background / 关键决策记录

| 决策点 | 结论 | 依据 |
|---|---|---|
| 产品形态 | **项目级新模式**（genSource=IMITATION），不新增独立页面 | 用户确认（推荐项）；复用版本→预览→发布全链路，参照 S9 深度模式「模式不动状态机」先例 |
| 原文输入 | 粘贴文本（≤20000 字），不做 URL 抓取 | 用户确认 |
| 采纳的增强 | 相似度自检 + 风格智能推荐 | 用户确认；配图占位建议、原文对照视图不采纳 |
| 相似度技术 | 本地 5-gram 字面重合 + 最长重复片段，不调 AI/embedding | 版权风险核心是字面照搬；可解释、零成本；embedding 语义相似会惩罚合理重写 |
| 仿写是否做 RAG | 不做（ragStatus=NO_KNOWLEDGE） | 车型库与任意题材原文强行匹配会注入无关权威数据约束，污染仿写 |
| 仿写产物形态 | 即文章版本（ArticleVersionEntity），增相似度列 | 与主题创作在版本层合流，下游全复用 |

## Requirements

### R1 原文输入（创建）
- `POST /api/projects` 增可选 `genSource`（默认 TOPIC）；IMITATION 时 `imitationText` 必填非空。
- 前端创建页「创作方式」单选：主题创作（默认）/文章仿写；仿写分支：topic 语义改「任务名」必填，原文必填 ≤20000 字（带字数统计），隐藏车型关联。

### R2 原文分析 + 风格推荐
- `POST /api/projects/{id}/imitation/analyze`：AI 一次调用产出分析（题材/结构骨架/句式特征，落 brief：gen_mode=IMITATION + outline/coreViewpoints/titleCandidates 复用）与风格推荐（≤3 个 enabled 风格，附理由，落 brief.style_recommendations）。
- 状态守护仿 BriefService：仅 DRAFT/READY 放行，条件更新原子抢占 GENERATING_BRIEF，失败回 DRAFT 写 last_brief_error。
- 风格库为空：推荐为空数组，前端提示引导去风格库提炼，不阻断。
- `GET /api/projects/{id}/imitation` 返回分析+推荐（无则 data:null）。

### R3 仿写生成（去图）
- 复用 `POST /api/projects/{id}/generate/versions`；genSource=IMITATION 时走仿写 prompt：原文全文+结构大纲+style.toneGuidance，指令明确「保留观点组织、严禁连续 10 字以上照搬原句、不得保留原文任何图片链接/图注/配图占位」。
- 双保险去图：prompt 约束 + 生成后正则清洗（Markdown `![..](..)`、HTML `<img>` 全剔除）。
- 状态机、多版本（每风格一版）、部分失败收集等主流程完全复用 VersionService 现有逻辑。

### R4 相似度自检
- 每版仿写生成后本地计算：5-gram 重合率 score（0~1，防照搬视角：仿写 n-gram 中来自原文的比例）、最长公共连续片段、≥10 字重复片段列表。
- 结果落 version（similarity_score/similarity_report）；前端展示数值+阈值色（≥0.60 红「过度相似建议修改」/0.40~0.60 黄/<0.40 绿）+ 重复片段明细（可折叠）。
- 仅警示不阻断；不自动改写。

### R5 权限与规范
- 写接口 ADMIN/EDITOR，读接口三角色；全部 `R<T>` + `@PreAuthorize` + `@Valid`；中文注释；表结构三处同步（schema.sql/entity/spec §3.2/§3.3/新 §14）。

## Acceptance Criteria

- [ ] AC1 创建仿写项目：选「文章仿写」粘贴原文成功创建；IMITATION 缺原文时创建被拒（400 中文提示）；默认 TOPIC 行为与现状完全一致（回归）。
- [ ] AC2 分析：点「分析原文」后项目进 READY，brief 含分析与风格推荐（≤3 个、附理由）；分析失败回 DRAFT 且 last_brief_error 可见；生成中重触发 409。
- [ ] AC3 仿写：选风格（含一键采用推荐）生成多版；每版 contentMd 为完整 Markdown 正文；**全文无任何图片链接/图注/占位**（正则验证）。
- [ ] AC4 相似度：版本列表可见每版 score 与阈值色；score≥0.60 显示红色警示；可展开查看重复片段明细；数值用「原文×仿写文」本地计算可复现。
- [ ] AC5 合流：仿写版本可设为当前版本→预览（wenyan 渲染正常）→发布链路与主题创作一致。
- [ ] AC6 权限：viewer 调 analyze/generate/create 均 403；三角色可读 imitation 接口。
- [ ] AC7 空风格库：推荐为空，前端展示引导提示，不阻断分析流程。
- [ ] AC8 构建：`mvn -q -DskipTests compile` 与 `npm run build` 通过。

## Out of Scope

- URL 抓取正文；配图占位建议；原文对照视图；相似度超标自动改写/重写循环；仿写模式 RAG 注入；相似度阈值配置化（先常量，运营化时再提 .env）。

## Technical Notes（摘要，详见 design.md）

- 新增列：project.gen_source/imitation_text/imitation_analysis；brief.style_recommendations；version.similarity_score/similarity_report（全部幂等 ADD COLUMN，回滚仅需代码回退）。
- 新增 `ImitationService`（analyze + similarityCheck 纯本地）；`VersionService` 仅加 IMITATION 分支；前端改 ProjectEdit/StepBrief/StepVersions 三个文件，`constants/project.js` 零改动（状态机不动）。