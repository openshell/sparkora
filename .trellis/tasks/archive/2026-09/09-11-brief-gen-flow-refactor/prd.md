# 简报生成流程重构:消除创建后竞态与冗余入口

## Goal

让「主题创作」项目的简报生成阶段只有一个入口、一套状态、一个页面:无论从创建页进入还是从列表重新进入,「无简报」区间恒按同一状态机渲染,不再出现「裸生成按钮页 vs 好看引导页」两套 UI,也不再因重复点击产生重复 brief 行与重复 AI 调用。

## Background / 现状事实(证据)

主题创作已收敛为深度流程(FAST 接口 2026-09-09 封死为 410,见 `ArticleProjectController.java:174`)。现状链路:

1. `ProjectEdit.onSaveAndGenerate`(`frontend/src/views/ProjectEdit.vue:189`):先 `await doSave()` 建项目 → `router.push('/projects/{id}?gen=deep')` → **导航之后**才 `await projectApi.startDeep()`(`POST /deep/clarify`,同步阻塞 10~30s)。
2. `StepBrief.probeDeepStatus`(`frontend/src/views/project/StepBrief.vue:396`)在 project 就位后探测 `/deep/status`;对 `?gen=deep` 仅做 **3 次 × 2s = 6s** 有界重查(`StepBrief.vue:410`),远短于 clarify 的 10~30s。
3. `DeepController.status` 的 `stageOf`(`DeepController.java:215`)只依据 questions/answers/notes/factsheet 推导,**无法表达「clarify 正在跑」**;clarify 未落库时返回 `stage=NONE`。
4. 结果:首次进入时探测超时 → `deepStage=NONE` 且 `deepMode=true` → 渲染裸按钮区(`StepBrief.vue:125`,只有「生成研究计划」)。
5. 退出重进(无 `?gen=deep`):`deepMode=false` → 渲染 `intro-hero` 引导页(`StepBrief.vue:132`),点「开始深度研究」仅把 `deepMode=true`(`StepBrief.vue:138`),**再显示第二个「生成研究计划」按钮** —— 纯冗余中间步。
6. 点裸按钮 `onDeepClarify`(`StepBrief.vue:430`)会**再次** `POST /deep/clarify`;`ClarifyService.clarify`(`ClarifyService.java:42`)每次 `new ArticleBriefEntity` 落**新行**(`DeepController.java:79`),无并发/幂等防护 → 重复 DEEP brief 行 + 重复 LLM 调用 + 再等 10~30s。
7. 对照:研究阶段 `DeepResearchService.run`(`DeepResearchService.java:80`)已是 **202 异步 + 落占位 + 轮询** 范式(`@EnableAsync` 已开启,`SparkoraApplication.java:19`),clarify 却仍同步阻塞,范式不一致。
8. 前端无简报区由 `deepMode` / `deepStage` / `brief` / `generatingBrief` 四个散落变量驱动分支,是上述不一致的温床。

## Requirements

- **R1 单一状态机**:「无简报」区间的渲染由唯一状态机驱动,同一状态恒渲染同一 UI,与进入路径(创建直发 / 重新进入 / 仅存草稿)无关。删除 `deepMode` 这类「路径意图」布尔对 UI 分叉的影响。
- **R2 消除创建后竞态**:clarify 改为异步(202 语义,对齐 `DeepResearchService.run` 范式)。后端在同步阶段落「计划生成中」可查询态后立即返回,后台生成研究计划与澄清问题;`/deep/status` 暴露该中间态,前端轮询至 questions 就绪。
- **R3 消除冗余入口与重复调用**:无简报区间只保留**一个**主操作(启动深度研究);删除「开始深度研究 → 生成研究计划」两步按钮链与「裸生成研究计划按钮」。同一项目在生成中时,重复触发被后端拒绝且前端按钮禁用,不产生重复 brief 行。
- **R4 草稿引导体验**:「仅存草稿」建的项目进入详情页,渲染统一引导页,单一主操作启动深度研究;点击后立即进入「计划生成中」态,无需再点第二次。
- **R5 并发/幂等防护**:clarify 触发对齐 `BriefService.claimGenerating` / `ImitationService.analyze` 的原子抢占与陈旧自愈先例,防止双开页面/前端恢复失效时的重复触发。
- **R6 失败可见可重试**:clarify 失败写 `lastBriefError` 并回到可重试的引导态;页面展示失败原因与重试入口(沿用现有 `lastBriefError` 展示)。

## Acceptance Criteria

- [ ] AC1(对应现象 bug):创建并生成一个主题项目,进入详情页后**不出现**只有单个「生成研究计划」按钮的裸页面;若 clarify 仍在进行,显示「研究计划生成中」进度态,完成后自动切换到澄清表单/研究计划卡片。
- [ ] AC2(对应冗余):「无简报」区间(无论创建直发还是退出重进)只渲染同一套 UI,且只有一个启动主操作;不再存在「先点开始深度研究、再点生成研究计划」的两步链。
- [ ] AC3(对应重复调用):在计划生成中重复触发启动,后端返回冲突提示、前端主操作处于 loading/disabled;数据库中同一项目不因重复点击新增重复 DEEP brief 行。
- [ ] AC4:退出进行中的页面再重新进入,能恢复到正确状态(计划生成中 → 澄清表单 → 研究中 → 手册 → 简报),不因 6s 探测窗口错判为「未开始」。
- [ ] AC5:clarify 失败时,页面显示 `lastBriefError` 并可一键重试;重试成功后进入澄清表单。
- [ ] AC6:「仅存草稿」项目进入详情页显示引导页,点唯一主操作后立即进入「计划生成中」,无第二次点击。
- [ ] AC7:仿写项目(IMITATION)路径与既有行为不回归(本轮不改仿写交互,仅确保未受影响)。
- [ ] AC8:后端 `mvn -q -DskipTests compile` 通过;前端 `npm run build` 通过;联调实测 AC1/AC3/AC4 通过。

## Key Decisions

- **D1 创建后保留自动发起,改为异步**(2026-09-11 用户确认):主题创作「创建并生成简报」仍自动发起深度研究,但 clarify 改异步——创建后立即进详情页显示「研究计划生成中」,完成后自动展开澄清表单;「仅存草稿」落到统一引导页,点一次主操作进入同一进度态。两条创建路径汇入同一状态机 UI。
- **D2 文章仿写(IMITATION)不纳入本轮**(2026-09-11 用户确认):仿写保持现有交互,仅验证不回归。其 `?gen=imitation` 的「导航后 await」竞态(`ProjectEdit.vue:195`)性质相同,留待后续单独任务。

## Out of Scope

- 文章仿写(IMITATION)分析流程的重构(D2;其 `?gen=imitation` 也存在类似「导航后 await」竞态,本轮不改)。
- 研究阶段(③④)、深度写作(⑤⑥)、版本/预览/发布阶段的改动。
- FAST 模式存量产物的迁移(已封死,只读)。
- 项目状态机(§4)本身的改动:深度流程仍为 brief 层,`CLARIFYING/RESEARCHING` 等不进入项目状态机。

## Technical Notes

- 深度流程为 brief 层产物状态(`gen_mode=DEEP`),项目状态机不变(见 `frontend/src/constants/project.js:6` 与 spec §14)。
- 「计划生成中」的可查询 brief 侧态定为 `sparkora_article_brief.plan_status`(PLANNING/READY),配部分唯一索引做并发幂等;详见 `design.md` §3。
- 权威规格 `docs/s0-spec.md` §14(深度模式)与 §5(创建/简报)需在实现完成后同步更新(见 `implement.md` D1)。

## Open Questions

(无阻塞开放问题。OQ1/OQ2 已由 D1/D2 决策收敛。)
