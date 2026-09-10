# 技术设计：文章仿写功能（项目级新模式）

> 形态已定：项目级新模式。设计原则参照 S9 深度模式先例——**模式字段驱动，项目状态机不动**。

## 1. 总体思路

仿写 = 创作项目的另一种「来源」：主题创作（TOPIC）从主题构思；仿写（IMITATION）从参考原文重表达。二者在 brief 层分叉（简报步变为原文分析步），在版本层合流（仍选风格生成多版），下游预览/发布完全复用。

```
创建(genSource=IMITATION, 粘贴原文) → DRAFT
  → 点「分析原文」→ GENERATING_BRIEF → READY   (brief.gen_mode=IMITATION，含原文分析+风格推荐)
  → StepVersions 选风格(推荐高亮) → GENERATING_VERSIONS → VERSIONS_READY   (仿写 prompt + 生成后相似度自检)
  → 预览/发布（完全复用现有链路）
```

## 2. 数据模型（schema.sql 幂等 + entity + spec 三处同步）

### sparkora_article_project 增列
| 列 | 类型 | 说明 |
|---|---|---|
| gen_source | VARCHAR(20) NOT NULL DEFAULT 'TOPIC' | 创作来源：TOPIC / IMITATION |
| imitation_text | TEXT | 参考原文全文（粘贴文本；仅 IMITATION 模式非空） |
| imitation_analysis | TEXT | 原文分析结果 JSON（题材/结构/句式摘要），展示冗余存储 |

### sparkora_article_brief 增列
| 列 | 类型 | 说明 |
|---|---|---|
| style_recommendations | TEXT | 风格推荐 JSON `[{styleId, name, reason, matchScore}]`（仅 IMITATION brief 使用） |

gen_mode 复用现有列，新增取值 `IMITATION`（FAST/DEEP 并列，不冲突）。

### sparkora_article_version 增列
| 列 | 类型 | 说明 |
|---|---|---|
| similarity_score | DOUBLE PRECISION | 与原文 5-gram 重合率 0~1（仅仿写版有值） |
| similarity_report | TEXT | 自检明细 JSON `{maxRunLength, repeatedRuns:[{text, length}]}` |

## 3. 后端设计

### 3.1 ImitationService（新增，`com.sparkora.service`）
- `analyze(projectId)`：状态守护同 BriefService（DRAFT/READY 放行，条件更新原子抢占 GENERATING_BRIEF）。调 AI 一次产出双结果：
  - 原文分析：题材/结构骨架/句式特征 → 写成 brief（outline/coreViewpoints/titleCandidates 从原文结构提炼，gen_mode=IMITATION）
  - 风格推荐：把 enabled 风格列表（id/name/description）喂给 AI，推荐 ≤3 个附理由 → `style_recommendations`
  - 风格库为空：推荐返回空数组，前端提示去风格库提炼（降级不阻断）
  - 原文超长截断策略沿用 StyleService.extract 先例（分析取前 8000 字，全文仍入库）
- `similarityCheck(originalText, contentMd)`：**本地实现，不调 AI**（可解释、零成本、防「整段照搬」才是版权核心风险）：
  1. 规范化：去 Markdown 标记/空白/标点，保留中英文与数字
  2. 字符 5-gram 重合率：`|仿写 n-gram ∩ 原文 n-gram| / |仿写 n-gram|`（防照搬视角：仿写中有多少来自原文）
  3. 最长公共连续片段（朴素 DP，~2000×2000 字毫秒级）+ 连续 ≥10 字重复片段列表（去重截断展示）
  - 阈值常量先落 `ImitationService`（`SIM_WARN=0.40, SIM_HIGH=0.60, RUN_WARN=13`），不进 .env（YAGNI，spec 记录，需要时再提配置）
  - 返回 `{score, report}`，写入本次生成的 version
- 仿写模式 **不做 RAG 检索**（原文题材未知，车型库强行注入会污染仿写；走现有 NO_KNOWLEDGE 路径，ragStatus 记 NO_KNOWLEDGE）

### 3.2 VersionService 扩展
- `generate()` 内分支：`genSource=IMITATION` 时 `buildImitationPrompt(p, brief, style)` 替代 `buildUserPrompt`：
  - system = style.toneGuidance + 仿写指令：「保留原文观点组织与信息脉络，以指定风格重新表达；严禁连续 10 字以上照搬原句；不得保留原文任何图片链接、图注、配图说明，正文不得出现任何图片占位」+ 现有 JSON 输出格式约定
  - user = 原文全文 + 分析出的结构大纲 + 字数目标（沿用 word_count_target，默认 1500）
  - wordCount 逻辑照旧；生成成功后调 `similarityCheck` 落库 similarity_score/report
- `generateOne` 主流程（原子抢占/状态守护/部分失败收集）完全复用不动

### 3.3 接口契约（`ArticleProjectController`，全部 R<T> + @PreAuthorize）
| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| POST | `/api/projects` | ADMIN/EDITOR | ProjectRequest 增 `genSource`(默认 TOPIC)、`imitationText`(IMITATION 时必填校验) | `{id}` |
| POST | `/api/projects/{id}/imitation/analyze` | ADMIN/EDITOR | — | `{brief}`（含 styleRecommendations）；状态冲突 409；生成失败 500 回 DRAFT 写 last_brief_error |
| GET | `/api/projects/{id}/imitation` | 三角色 | — | `{analysis, recommendations}`（无则 data:null） |
| POST | `/api/projects/{id}/generate/versions` | ADMIN/EDITOR | 复用 `{styleIds}` | 仿写模式下返回版本含 similarity 字段 |

- 分析接口前端单独放宽超时（沿用版本生成同款 axios 超时先例）。
- 权限冒烟：viewer 打 analyze/generate → 403。

## 4. 前端设计

- `ProjectEdit.vue`：顶部增「创作方式」单选：主题创作（默认）/ 文章仿写。选仿写：topic 标签改「任务名」（必填，列表展示用）、隐藏车型关联选择、增「参考原文」大文本域（必填、限 20000 字、字数统计）。`genSource` 仅仿写时随 create 提交。
- `StepBrief.vue`：仿写模式（读 project.genSource）标题改「原文分析」；「生成简报」按钮改「分析原文」；READY 后展示分析卡片（题材/结构/句式）+ 风格推荐卡片（推荐 ≤3 个：名称/理由/匹配度，一键「采用」把推荐风格带入版本步）。
- `StepVersions.vue`：仿写模式顶部展示原文摘要卡（可折叠）；风格选择列表中推荐风格打「推荐」角标；版本卡片增相似度行：score 数值 + 阈值色（≥0.60 红「与原文过度相似，建议修改」/0.40~0.60 黄提示/<0.40 绿通过），点开看重复片段明细（el-collapse）。
- `constants/project.js`：**状态机零改动**；仅加相似度阈值色映射工具（或组件内联，实现时定）。

## 5. 权衡与风险

| 决策 | 取舍 |
|---|---|
| 相似度用本地 n-gram 而非 embedding/AI 判分 | 版权风险核心是「字面照搬」，n-gram 可解释、确定性强、零成本；embedding 语义相似度会惩罚「合理重写」，AI 判分不稳定。后续可叠加 |
| 阈值硬编码常量而非 .env | 三个阈值属实验性调参，YAGNI；需要运营化时再提配置（遵循项目「配置走 .env」规范的时机判断） |
| 原文存 project 而非独立表 | 1:1 关系，独立表徒增 join；TEXT 列 PostgreSQL 无压力 |
| 仿写跳过 RAG | 车型库与任意题材原文强行匹配会注入无关「权威数据」约束，污染仿写；保留 IMITATION 模式下 ragStatus=NO_KNOWLEDGE 可见性 |
| brief 复用（gen_mode=IMITATION） | 不为仿写单设分析表，StepBrief/状态机/历史 brief 逻辑全复用；代价是 brief 语义略宽（分析结果复用 outline 等字段） |
| 图片处理 = prompt 约束 + 输出过滤 | prompt 禁止 + 生成后正则二次清洗（`![...](...)`、HTML `<img>` 全剔除），双保险，不依赖 AI 自觉 |

## 6. 兼容与回滚

- 全部新列 `ADD COLUMN IF NOT EXISTS` 幂等 + DEFAULT，旧数据不受影响（genSource 默认 TOPIC 行为等价现状）。
- 新增列均为增量，回滚只需代码回退，无需数据迁移。
- spec 同步：`docs/s0-spec.md` §3.2 字段表、§3.3 接口表、§7b 配置（无新增）、新增 §14 仿写模块规格。