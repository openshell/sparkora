# 文章仿写（09-09-article-imitation）

> 回链：[系统说明总览](../README.md)

职责：项目级新模式 `genSource=IMITATION`——粘贴参考原文 → AI 分析 + 风格推荐 → 选风格仿写（去图）→ 相似度自检 → 预览/发布（全链路与主题创作合流）。

> 设计原则同深度模式先例——**模式字段驱动，项目状态机（[overview.md §4](overview.md)）不动**；仿写跳过 RAG（`ragStatus=NO_KNOWLEDGE`）。

---

## 1. 数据模型（schema.sql 幂等 ADD COLUMN，回滚仅需代码回退）

- `sparkora_article_project` 增列：
  - `gen_source VARCHAR(20) NOT NULL DEFAULT 'TOPIC'`（`TOPIC`/`IMITATION`）
  - `imitation_text TEXT`（参考原文全文，仅 IMITATION 非空，≤20000 字）
  - `imitation_analysis TEXT`（分析结果 JSON `{genre,structure,sentenceFeatures}`）
- `sparkora_article_brief` 增列：`style_recommendations TEXT`（JSON `[{styleId,name,reason,matchScore}]`，仅 `gen_mode=IMITATION` brief 使用）。
- `sparkora_article_version` 增列：
  - `similarity_score DOUBLE PRECISION`（0~1）
  - `similarity_report TEXT`（JSON `{maxRunLength,maxRunText?,repeatedRuns:[{text,length}],thresholds}`）

---

## 2. 流程与状态机

```
创建(genSource=IMITATION, 粘贴原文≤20000字) → DRAFT
  → POST /imitation/analyze → GENERATING_BRIEF → READY   (brief.gen_mode=IMITATION: 原文分析+风格推荐)
  → StepVersions 选风格(推荐高亮/一键采用) → GENERATING_VERSIONS → VERSIONS_READY (仿写 prompt+去图+相似度自检)
  → 预览/发布（与主题创作完全复用，见 image.md/preview.md/publish.md）
```

- 状态守护与原子抢占仿 `BriefService`：仅 DRAFT/READY 放行、生成中未过期拒绝（409）、陈旧超 10 分钟自愈；失败回 DRAFT 写 `last_brief_error`。

---

## 3. 仿写生成（R3）

- 复用 `POST /generate/versions`（主题创作已封死为 410，**仿写项目例外**，`VersionService.generate` 内 `genSource` 分支）。
- 仿写 prompt：`style.toneGuidance` + 仿写铁律（保留观点组织 / 严禁连续 10 字以上照搬原句 / 不得保留原文任何图片）+ 原文全文 + 结构大纲 + 字数目标。system 内 `toneGuidance` 后附统一强化句「以上语气、句式、结构与用词特征必须在正文中充分体现,不得只在部分段落贴合。」（09-10-style-library-enhance，主题/深度链路同款）。
- 双保险去图：prompt 约束 + 生成后正则清洗（`VersionService.stripImages`：Markdown `![..](..)`、HTML `<img>`、「配图/图注/示意图/图片来源:」占位行全剔除）。
- 仿写跳过 RAG：不检索车型库（任意题材原文与车型库强行匹配会注入无关数据约束），`version.rag_status=NO_KNOWLEDGE`。
- 相似度自检（`ImitationService.similarityCheck`，纯本地 0 次 LLM）：
  - 规范化（去 Markdown/HTML/标点/空白，小写化）
  - 字符 5-gram 重合率 `|仿写 n-gram ∩ 原文 n-gram| / |仿写 n-gram|`（防照搬视角）
  - 最长公共连续片段（朴素 DP）→ 连续 ≥10 字重复片段列表（贪心扩展去重，≤10 条单条截 120 字）
  - 阈值常量：`SIM_WARN=0.40` / `SIM_HIGH=0.60` / `RUN_WARN=13`（`ImitationService`，实验性调参不进 `.env`）
  - 仅警示不阻断、不自动改写。

---

## 4. 接口契约（全部 `R<T>` + 方法级 `@PreAuthorize`）

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| POST | `/api/projects` | ADMIN/EDITOR | `genSource`(缺省 `TOPIC`)/`imitationText`(IMITATION 必填非空) | `{id}`；IMITATION 缺原文 `R.fail(400)`；仿写项目不关联车型（跳过 AI 自动匹配） |
| POST | `/api/projects/{id}/imitation/analyze` | ADMIN/EDITOR | — | `ArticleBriefEntity`（`gen_mode=IMITATION`；一次 AI 调用产出原文分析落 `outline`/`coreViewpoints`/`titleCandidates` + 风格推荐 ≤3 个附理由落 `style_recommendations`，只保留库内 `styleId` 防御截断）；非仿写项目 400；状态冲突 409；失败回 DRAFT 写 `last_brief_error` |
| GET | `/api/projects/{id}/imitation` | 三角色 | — | `{briefId, titleCandidates, coreViewpoints, outline, styleRecommendations, analysis:{genre,structure,sentenceFeatures}}`（无则 `data:null`） |
| POST | `/api/projects/{id}/generate/versions` | ADMIN/EDITOR | `{styleIds:[...]}` | 仿写模式复用：每风格一版（含 `similarity_score`/`similarity_report`）；状态机同 [overview.md §4](overview.md) |

---

## 5. 前端

- `ProjectEdit.vue`：区块 00「创作方式」radio-button（主题创作默认 / 文章仿写）；仿写分支：`topic` 语义改「任务名」、原文 textarea 必填 ≤20000 字带字数统计、隐藏深度研究提示；「创建并分析原文」创建后跳详情页带 `?gen=imitation` 并直发 analyze（失败页面内重试）；切回主题创作清空原文。
- `StepBrief.vue`：仿写模式（`project.genSource` 判定）标题改「原文分析」；独立视图（分析中 skeleton / 引导语含 `lastBriefError` / 分析结果卡题材·结构·句式 + 风格推荐卡 ≤3 个附匹配度%与理由，点「采用」带 `?adoptStyle=` 跳版本步并自动预选）；「重新分析」仅 READY；空推荐时引导去风格库不阻断。
- `StepVersions.vue`：仿写分支——原文摘要折叠卡、推荐风格「推荐」角标（`styleRecommendations`）、生成走 `generateVersions`（非深度逐风格）、版本卡相似度行（`(score*100).toFixed(1)%` + 阈值色 ≥0.60 红「与原文过度相似，建议修改」/0.40~0.60 黄/<0.40 绿 + 重复片段明细可折叠含 `maxRunLength≥13` 提示）。
- `constants/project.js` **零改动**（状态机不动）。

---

## 6. 关键实现路径

- 后端：`com.sparkora.service.ImitationService`（分析/风格推荐/相似度自检）、`service.VersionService`（仿写分支 + `stripImages`）、`web.controller.ArticleProjectController`（analyze/imitation/generate-versions）。
- 前端：`views/ProjectEdit.vue`、`views/project/StepBrief.vue`、`views/project/StepVersions.vue`。
- 表：`sparkora_article_project` / `sparkora_article_brief` / `sparkora_article_version`（仿写列）。

---

## 7. 验收清单

- [ ] AC1 创建仿写项目成功/缺原文 400/TOPIC 回归一致
- [ ] AC2 分析后 READY、brief 含分析与推荐；失败回 DRAFT；生成中重触发 409
- [ ] AC3 仿写多版生成、全文无图片（正则验证）
- [ ] AC4 版本列表相似度数值+阈值色+重复片段明细；数值本地可复现
- [ ] AC5 仿写版本设当前→预览→发布链路一致
- [ ] AC6 viewer 调 analyze/generate 403；三角色可读 imitation
- [ ] AC7 空风格库：推荐空数组，前端引导提示，不阻断
- [x] AC8 `mvn -q -DskipTests compile` 与 `npm run build` 通过（2026-09-09 check 复验 ✓）
