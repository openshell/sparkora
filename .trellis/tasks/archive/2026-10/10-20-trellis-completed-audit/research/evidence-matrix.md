# Trellis 完成态审计证据矩阵

> 审计任务：`.trellis/tasks/10-20-trellis-completed-audit`
> 审计日期：2026-10-21
> 分支：`feature/byd-dynasty-miniapp-push`
> 审计起点 HEAD：`4f1d967`
> 原则：只记录可复核证据；没有运行/测试/真机证据的项不勾选，不把子任务归档等同于父任务通过。

## 0. 起点与边界

- 工作区既有修改：`.trellis/tasks/10-16-byd-dynasty-local-push/task.json`，本审计未触碰。
- 既有未跟踪项：`.agents/`、`.codex/`、`10-20-byd-push-security-acceptance/`、`10-20-trellis-cleanup-audit/`、`10-20-workspace-untracked-governance/`，本审计未触碰。
- 未读取 `.env`、Token、密码、私钥或第三方密钥。
- 未修改产品代码；未执行 commit、push、deploy、reset-hard、强制推送、递归删除或 Docker prune。

## 1. 00-join-c-cha

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| ON-1 onboarding 内容 | 原任务四主题（Trellis 工作流/runtime、项目 spec、个人工作、练习）均在归档 PRD 中保留；归档 PRD 追加的完成证据记录了 `c_cha` 身份、工作流与实际任务使用。 | 通过（人工判断 + 仓库记录） |
| ON-2 实际使用 | `.trellis/.developer` 已配置 `c_cha`；`c_cha` journal 有四个已完成 session 及关联提交；active/archive 任务记录显示 planning/implementation/check/archive/journaling 均被使用。 | 通过 |
| ON-3 无产品代码交付 | onboarding 本身无产品实现要求；归档 PRD 明确说明无产品代码交付。 | 通过 |
| ON-4 归档完成 | 使用 `task.py archive` 归档；`task.json` 为 `status=completed`、`completedAt=2026-10-21`。 | 通过 |
| 归档结果 | `D:\code\AI\sparkora\.trellis\tasks\archive\2026-10\00-join-c-cha` | **已归档** |

## 2. 10-04-brief-retrieval-sources

### 2.1 子任务闭环

| Child | 归档日期 | 状态 | 关键交付 |
| --- | --- | --- | --- |
| `10-04-serper-provider` | 2026-10-06 | completed | Serper provider、Header 认证、垂直 news/web、设置面与契约扩展 |
| `10-04-web-fanout-merge` | 2026-10-08 | completed | PRIMARY_FANOUT、并行 provider、跨源 merge、统一 sourceId、质量门 |
| `10-04-web-followup-budget` | 2026-10-08 | completed | 缺口补检索、Round 2 增量写回、三级预算、去重、缓存、extract 修复 |
| `10-05-tavily-endpoint-priority` | 2026-10-08 | completed | Tavily 中转优先/官方兜底、独立超时、质量门、provider 身份固定 |

### 2.2 父级 AC 逐项证据

| 验收项 | 可复核证据 | 结论 |
| --- | --- | --- |
| AC1 零回归基线 | 2026-10-21 Java 21 全量 `mvn test`：1247/0/0/0；`frontend/npm run build`：通过。既有 FIRST_HIT 行为、旧构造器和旧字段兼容用例在同批测试中通过。 | 通过 |
| AC2 Serper 可用 | `SerperSearchToolTest`；`DeepProperties.effectiveSerperApiBase()/effectiveSerperApiKey()` 支持官方/中转仅改配置切换；设置 DTO/UI 已放开 SERPER；`docs/spec/brief-generation.md`、`retrieval.md`、`.env.example` 有 `_BASE_URL` 契约。 | 通过 |
| AC2a 垂直路由 | `SerperSearchTool` 支持 web/news；`WebSearchRouterTest` 覆盖 vertical 路由与实际 resultCount；news 结果保留 date/source，默认仍 web。 | 通过 |
| AC3 多源聚合正确性 | `WebSearchRouterFanoutTest` 覆盖 primary 并行、异常隔离、primary 为空回落、跨源去重、witness、maxResults 不放大；`WebResultNormalizerMergeTest` 覆盖全局 sourceId 与反例；`SubAgentRunnerTest` 覆盖任意 sourceId 通过 `validateFacts`。 | 通过（单测） |
| AC4 交叉验证收益可观测 | `WebSearchRouterFanoutTest.witnessTotal_可观测交叉验证` 与 `SubAgentRunnerTest.SearchMeta_providers增量透出_旧provider字段语义不变` 证明 `providers`/`witnessTotal` 契约完整。**没有找到同项目 FIRST_HIT vs PRIMARY_FANOUT 的线上 A/B 趋势快照**；PRD 明示此项是观测性验证、不是独立交付门槛，因此不作为父任务阻断项，留作后续线上观测。 | 契约通过；A/B 趋势未覆盖 |
| AC5 覆盖补检索 | `SelectFollowupTargetsTest` 覆盖三类缺口、聚类、截断与无缺口空列表；`DeepResearchServiceFollowupTest` 覆盖 Round 2 增量写回、不新建子 agent、不重跑 plan、generate 仅一次。 | 通过（单测） |
| AC6 预算与去重 | `WebCallBudgetTest` 覆盖三级预算、per-round 保护性下限、SearXNG 不计费、预算耗尽；`WebSearchCacheTest` 覆盖批次缓存/TTL/release；`WebResultNormalizerMergeTest` 覆盖跨轮去重与 dedupedCount；`WebSearchRouterTest` 覆盖实际 resultCount。 | 通过（单测） |
| AC7 降级不阻断 | `WebSearchRouterTest` 覆盖 provider error/empty/unconfigured 回退；`WebSearchRouterExtractTest` 覆盖 webAllowed=false 零 extract；`DeepResearchServiceFollowupTest` 覆盖超时降级、预算耗尽仍生成。 | 通过（单测） |
| AC8 契约与前端 | `toolHealth`、`strategyLabel()` 三值、`usedProviders`/`providers`/`attempts[].witnessTotal` 增量字段均有实现/测试；`SubAgentRunnerTest` 保留旧 provider 语义；前端构建通过。 | 通过 |
| AC9 默认全关 | `DeepProperties` 默认 `first_hit`、followup 0、budget 20/12；`.env.example` 同步 `SERPER_*`/`DEEP_SERPER_*`/`DEEP_TAVILY_API_BASE_URL`/`DEEP_WEB_*`；URL 键均为 `_BASE_URL`；运行时 setting 未新增预算/followup 开关。 | 通过（静态契约） |

### 2.3 结论

- 父 PRD AC1-AC9 已逐项更新；AC4 的线上 A/B 趋势是唯一未留存快照，但 PRD 明确它不是独立门禁。
- 父任务已于 2026-10-21 使用 `task.py archive` 归档。
- 归档路径：`.trellis/tasks/archive/2026-10/10-04-brief-retrieval-sources/`
- 归档后 `task.json`：`status=completed`、`completedAt=2026-10-21`。

## 3. 10-05-self-hosted-sources

### 3.1 Children 闭环

9 个 children 均已归档且 `status=completed`：

- `10-05-crawl4ai-transport`（2026-10-07）
- `10-05-source-crawl-base`（2026-10-08）
- `10-05-source-domain-retrieval`（2026-10-08）
- `10-05-source-center-ui`（2026-10-08）
- `10-05-source-web-fusion`（2026-10-09）
- `10-09-source-metadata-completion`（2026-10-09）
- `10-09-cpca-gasgoo-collection`（2026-10-10）
- `10-10-gasgoo-sales-channel`（2026-10-10）
- `10-10-gasgoo-sales-url-and-enable`（2026-10-10）

### 3.2 AC-P1~AC-P10 逐项证据

| 验收项 | 可复核证据 | 结论 |
| --- | --- | --- |
| AC-P1 端到端 | `10-05-source-crawl-base/prd.md` 明确记录 live 站点未真连；`10-09-cpca-gasgoo-collection` 的交付范围不含工信部申报入库；`docs/spec/knowledge/sources.md` 仍保留真实端到端采集未完成项。盖世/乘联会真机记录不能替代“工信部 BYD 申报全收 + 统一检索进入 fact_sheet”。 | **未满足** |
| AC-P2 发布节奏 | `SourceScheduleServiceTest`（20 用例）覆盖窗口、cron、本批完成跳过；`SourceJobServiceTest` 覆盖 RUNNING 防重叠；乘联会/盖世真机记录含排期和增量采集。 | 通过 |
| AC-P3 资源红线 | `Crawl4aiConcurrencyTest` 验证并发 ≤2、超出快速限流不排队；`FetchRateLimiterTest` 验证同 host ≤2/天、跨 host 独立、跨日重置、HTTP 无硬日限。**缺少工信部多子页 BYD 申报真实一轮采完证据**。 | **部分，未满足真机场景** |
| AC-P4 域隔离 | `CarRagServiceTest.E2_信源块激增_CAR候选不被挤占`、`E2_二级隔离_user子集塞满不改BYD子集`；`NewsServiceSourceIsolationTest` 验证 `/api/news` 不泄漏通用信源。 | 通过（单测） |
| AC-P5 融合可观测 | `FactSheetServiceTest` 的 F1/F2/F3/F4：本地优先、跨 type 同 URL 去重、sourceMeta 计数、ranking 不计独立交叉；`SubAgentRunnerTest` 覆盖 SOURCE 元数据透传。 | 通过（单测） |
| AC-P6 零回归基线 | 2026-10-21 全量 `mvn test` 1247/0/0/0；`frontend/npm run build` 通过（4242 modules，25.05s）。 | 通过 |
| AC-P7 契约与文档 | `docs/spec/knowledge/news.md`、`center.md`、`sources.md`、`retrieval.md`、`brief-generation.md`、`image.md` 与 `.env.example` 已同步信源/抓取/图库字段；URL 配置均以 `_BASE_URL` 结尾。 | 通过（静态契约） |
| AC-P8 结构化内容 | `TextChunkerTest.preserveNewlines_*`、`SourceDocServiceTest.结构化分类_保留换行_数值行不丢`、`SourceTableParserTest` 覆盖表格行/列与数值不丢。 | 通过（单测） |
| AC-P9 SOURCE 事实 | `SubAgentRunnerTest.SOURCE事实_无url无sourceId_直接接受`、`SOURCE带自造URL_按WEB校验拒绝`、`SOURCE带合法sourceId_归一为WEB`；`FactSheetServiceTest` 覆盖 official/industry/media 置信分档与 crossCounted=false。 | 通过（单测） |
| AC-P10 采集配图 | `SourceImageServiceTest` 覆盖 `source=source`、sourceRef、detail_base_url 相对链、单图失败不阻断、无图零调用；`ImageEmbeddingTextBuilderTest`/`ImageEmbeddingServiceTest` 覆盖标题嵌入与语义检索。 | 通过（单测） |

### 3.3 结论

- 父 PRD 已逐项更新：AC-P2/P4/P5/P6/P7/P8/P9/P10 勾选；AC-P1 与 AC-P3 保持未勾选并写明缺口。
- **保留 open，不归档**：剩余完整业务 E2E、部署后真实页面复核和 prompt 质量人工验收。
- 最小补充路径：在可达 Crawl4AI 环境启用 `SOURCE_COLLECT_ENABLED=true`，真实采集工信部 BYD 申报全收 + 乘联会/盖世，跑一轮统一检索/深度研究并保存 `fact_sheet` 证据；同时记录工信部多子页真实限速/全收结果。

## 4. 10-12-preview-leftover-cleanup

### 4.1 实现提交

`ba6968b fix(ui): 采纳降噪双Toast,发布页字数统一,localStorage兜底,保存竞态防御(快照口径)`

### 4.2 四项需求与证据

| 项 | 静态/构建证据 | 结论/缺口 |
| --- | --- | --- |
| R1 采纳降噪 | `StepPreview.vue`：`saveContent({ silentSuccess })` 默认保留工具栏/去发布提示；`saveContentSilent` 仅用于 persist；失败警示不变。`docs/spec/preview.md` 与 `.trellis/spec/frontend/index.md` 已同步。 | 代码满足；**真机单张、整组、工具栏 toast 未复核** |
| R2 字数统一 | `StepPublish.vue` 使用 `textStatOf`，与 `StepPreview.vue` 同源；`textStat` 口径未改。 | 代码满足；**真机预览/发布同文同篇字数一致未复核** |
| R3 localStorage 兜底 | `SPLIT_KEY` 初始化读、拖拽写、键盘写均有 `try/catch`；草稿读写也有兜底。验收允许代码审查确认，故通过。 | 通过（代码审查） |
| R4 保存竞态 | `saveContent` 入口固化 `md`；成功后仅 `contentMd.value === md` 时置 clean、清草稿；保存期间有新编辑则保持 dirty、保留草稿。 | 代码满足；**慢网在途续写真机未复核** |
| R5 spec/docs | `.trellis/spec/frontend/index.md` 与 `docs/spec/preview.md` 已同步静默保存语义、dirty/草稿语义和预览/发布衔接。 | 通过 |
| 构建 | `frontend/npm run build` 通过：4242 modules，25.05s，仅既有警告。 | 通过 |
| E2E | `frontend/npx playwright test`：**24 passed / 25 failed**。24 个失败为 Windows 视觉快照 `*-win32.png` 缺失；1 个为 `tests/smoke/project-steps.spec.js:35` 仍在公众号发布页期待 BYD 面板（10-19 已迁移）。定向重跑该 spec：3 passed / 1 failed。 | **未通过** |

### 4.3 结论

- 四项实现有静态代码证据，构建通过；但 E2E 门禁未全绿，真实 toast/字数/localStorage 禁用/慢网竞态四项没有现场证据。
- PRD 中 R3、R5 已勾选；R1、R2、R4、构建+E2E 项保持未勾选。
- **保留 open，不归档**：剩余完整业务 E2E、部署后真实页面复核和 prompt 质量人工验收。
- 最小修复建议：更新陈旧 smoke 断言到 BYD 推送路由；为 Windows 视觉测试提供匹配基线或显式平台策略；在真机补四项手工验收。

## 5. 10-19-channel-editor-publish

### 5.1 实现提交

`dec0bf1 feat(10-19): 渠道化发布——自由编辑区与王朝小程序三步工作台`（31 files，1,880 insertions）。当前分支后续提交建立在该实现上。

### 5.2 AC-1~AC-7 与页面/接口证据

| 验收项 | 证据 | 结论 |
| --- | --- | --- |
| AC-1 渠道分流 | 代码与只读页面：`/projects/new` 提供公众号/王朝小程序；`ProjectLayout.vue` 按 `channel` 切换三步/四步并做路由守卫；`/projects/67/byd-push` 显示 BYD 三步，`/projects/64/publish` 为 gzh 四步且无 BYD 面板。 | 通过（代码/只读页面） |
| AC-2 编辑保存 | `BydEditorStep.vue` 提供标题、MarkdownEditor 正文、配图入口；保存走版本 title/content 接口。2026-10-19 验收记录有接口冒烟和刷新链路证据。**本轮没有完整浏览器脚本覆盖编辑→保存→刷新不丢**。 | 部分（接口证据，浏览器 E2E 缺口） |
| AC-3 推送步 | 项目 67 只读状态：`channel=byd_miniapp`、`status=DRAFT`、非空 `currentVersionId`/`lastJobId`/`lastDraftId`；最近任务 `SUCCESS`；Agent 在线 v1.1.0。历史页面曾渲染“草稿 ID：-”；提交 `7b77d7f` 已修复为优先任务结果、仅在 `latestJob.jobId === lastJobId` 且无 result 时回退顶层 `lastDraftId`，Playwright 9 条定向用例通过。**真实部署页仍待最终复核**。 | 代码/契约通过；部署页待验收 |
| AC-4 活动发文 | `BydGenerateStep.vue` 两项必填校验；`BydDynastyGenerateService` 异步生成并落新版本；后端单测覆盖缺字段/生成/失败中文提示。**prompt 质量未验证**。 | 功能通过（单测/既有记录）；质量未验收 |
| AC-5 内容解耦 | `BydDynastyPushService.buildPayload` 使用项目 `current_version_id` 对应版本；gzh 与 BYD 用户侧接口有渠道门禁单测。 | 通过（代码/单测） |
| AC-6 发布步解耦 | `StepPublish.vue` 已移除 BYD 面板；gzh 发布页只读页面无 BYD 面板；BYD 工作台不调用公众号 publish 状态。 | 通过 |
| AC-7 回归与文档 | 2026-10-21 `mvn test` 1248/0/0/0；`frontend/npm run build` 通过；`docs/spec/channel.md`、`publish.md`、`project-lifecycle.md` 已同步。 | 通过 |

### 5.3 三项必须分开的结论

1. **浏览器 E2E：未完成。** 本轮只做只读页面/API 状态核对，没有执行完整创建→编辑→保存→生成→推送。公众号/BYD 路由与草稿 ID 契约的旧 smoke 断言已修复，定向 9 passed；这不替代完整业务 E2E。
2. **Agent 真机推送：有历史成功记录，UI 契约已修复。** 项目 67 最近任务 SUCCESS、Agent 在线、顶层 `lastDraftId` 非空，说明历史推送链路有证据；`7b77d7f` 修复了成功页草稿 ID 回退并覆盖同任务 ID 防串用，仍需在部署后的真实页面复核。
3. **Prompt 质量：未验证。** 未取得“官方活动要求 + 个人情况 → 生成标题/正文”的人工质量评审记录；不能由任务 SUCCESS 或接口成功推断。

### 5.4 修复与后续验收

- [已完成] 成功态优先读 `job.result?.draftId`；仅当 `latestJob.result == null` 且 `latestJob.jobId === lastJobId` 时回退 `state.lastDraftId`，避免跨任务串用。提交 `7b77d7f`，定向契约测试 9 passed。
- [已完成] `project-steps.spec.js` 已覆盖公众号 `/publish` 不挂载 BYD 面板、BYD `/byd-push` 路由、同任务回退及不匹配任务不得串用。
- 增加一条完整浏览器 E2E，覆盖创建 BYD 项目 → 编辑保存 → 刷新 → 生成/失败提示 → 推送门禁/成功态。
- 由人工生成至少一篇活动文并记录 prompt 质量验收。

### 5.5 结论

- **保留 open，不归档**：剩余完整业务 E2E、部署后真实页面复核和 prompt 质量人工验收。

## 6. 最终处理记录

| Task | 最终结论 | 归档路径/原因 |
| --- | --- | --- |
| `00-join-c-cha` | **已归档** | `.trellis/tasks/archive/2026-10/00-join-c-cha/`；人工 onboarding 完成 |
| `10-04-brief-retrieval-sources` | **已归档** | `.trellis/tasks/archive/2026-10/10-04-brief-retrieval-sources/`；4 children 完成、全量测试/构建通过；AC4 线上 A/B 为后续观测项 |
| `10-05-self-hosted-sources` | **保留 open** | AC-P1 工信部真实采集/入库/检索闭环缺失；AC-P3 工信部多子页真实限速场景缺失 |
| `10-12-preview-leftover-cleanup` | **保留 open** | E2E 24/49 通过且 4 项真机验收未覆盖 |
| `10-19-channel-editor-publish` | **保留 open** | 草稿 ID 缺陷已由 `7b77d7f` 修复；完整业务浏览器 E2E、部署后页面复核和 prompt 质量仍未验收 |

## 7. 命令与测试日志

| 命令 | 结果 |
| --- | --- |
| `$env:JAVA_HOME='C:\Program Files (x86)\Common Files\JDK\jdk-21'; mvn test` | **PASS**：1248 tests / 0 failures / 0 errors / 0 skipped；BUILD SUCCESS |
| `Set-Location frontend; npm run build` | **PASS**：4242 modules，25.05s；仅既有警告 |
| `Set-Location frontend; npx playwright test` | **FAIL**：24 passed / 25 failed（24 Windows 视觉快照缺失 + 1 陈旧 BYD smoke 断言） |
| `Set-Location frontend; npx playwright test tests/smoke/project-steps.spec.js` | **9 passed**：公众号/BYD 路由、离线态、草稿 ID 回退优先级与跨任务防串用 |
| `python ./.trellis/scripts/task.py archive .trellis/tasks/00-join-c-cha --skip-branch-validation --no-commit` | 成功归档 |
| `python ./.trellis/scripts/task.py archive .trellis/tasks/10-04-brief-retrieval-sources --skip-branch-validation --no-commit` | 成功归档 |

## 8. 未覆盖项与风险

- 10-04 AC4：没有同项目 FIRST_HIT vs PRIMARY_FANOUT 线上 A/B 趋势快照；按 PRD 非独立门禁处理，后续应补观测。
- 10-05 AC-P1：没有工信部真实站点采集、BYD 申报全收、入库/检索进入 `fact_sheet` 的端到端证据；盖世/乘联会证据不能替代。
- 10-05 AC-P3：有并发/频控单测，但没有工信部多子页真实一轮采完证据。
- 10-12：Playwright 全量未绿；真机 toast、字数、localStorage 禁用、慢网保存竞态均未现场复核。
- 10-19：草稿 ID 缺陷已修复并完成契约测试；完整业务浏览器 E2E、部署后真实页面复核和 prompt 质量仍未验收。
- 产品代码缺陷：10-19 草稿 ID 缺陷已由后续修复提交 `7b77d7f` 处理；10-12 的 Windows 视觉基线策略仍需单独治理。

## 9. 本任务变更与生成物

- 修改：`.trellis/tasks/10-20-trellis-completed-audit/research/evidence-matrix.md`
- 修改：`.trellis/tasks/10-05-self-hosted-sources/prd.md`
- 修改：`.trellis/tasks/10-12-preview-leftover-cleanup/prd.md`
- 修改：`.trellis/tasks/10-19-channel-editor-publish/prd.md`
- 归档新增：`.trellis/tasks/archive/2026-10/00-join-c-cha/`
- 归档移动：`.trellis/tasks/archive/2026-10/10-04-brief-retrieval-sources/`（对应 active 路径已删除）
- 审计产生的 24 个 `frontend/tests/visual/**/*-win32.png` 已按显式路径清理，当前无这些生成物残留。
- 未产生产品代码修改、commit、push 或部署。
