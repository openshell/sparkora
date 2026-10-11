# 智能配图建议缺陷批次修复(parent)

## Goal

汇总 10-11 设计评审确认的三项缺陷修复(重复配图防御 / 采用落正文 / 门槛解耦),持有跨子任务验收与环境收口。

## 需求来源

2026-10-11 智能配图建议功能设计评审(用户确认只做「低垂果实 1~3」;句级分块检索为中期方向,不在本批次):

- P1b 重复配图无防御 → 子任务 A `10-11-img-suggest-dedup`(复杂:prd + design + implement)
- P2 采纳半状态窗口 → 子任务 B `10-11-img-suggest-adopt-persist`(轻量:PRD-only)
- P2 建议门槛 0.3 噪声偏大 → 子任务 C `10-11-img-suggest-threshold`(轻量:PRD-only)

## Task Map(依赖顺序写入各子 PRD,不由树位置隐含)

| 子任务 | 层面 | 复杂度 | 依赖 |
|---|---|---|---|
| A img-suggest-dedup | 后端(纯后端过滤,前端零改动) | 复杂 | 无 |
| B img-suggest-adopt-persist | 前端 | 轻量 | 无(与 A 并行,验收互不阻塞) |
| C img-suggest-threshold | 后端配置 + 前端默认值 + 文档 | 轻量 | 无 |

三个子任务无硬依赖,可各自独立计划→实现→检查→归档;仅 A 改建议生成的候选内容收窄,B 的真机验收(建议→采纳→落库)在 A 合入后重跑一遍以确认候选收窄后流程仍自洽。

## Cross-child 验收(整合验收,parent 收口时逐项确认)——✅ 2026-10-12 全部通过

- [x] A:锚点标题下已有插图的段落不再产出建议;同图在单次生成结果中至多出现一组;已登记正文插图不再被推荐;`mvn test` 全绿(1127,含AnchorExtractorTest 22/IllustrationSuggestionServiceTest 20);真机项目65回归通过 → `627ea94 fix(image)`
- [x] B:采纳建议后不手动保存,版本 content_md 已含插图 markdown;保存失败仅警示不放大(真机浏览器验收,用户批准) → `b9a2d55 fix(ui)`
- [x] C:未传 minScore 的 suggest 请求按新默认门槛 0.4 过滤;前端门槛输入默认 0.4 口径一致;`docs/spec/image.md` 与 `.env.example` 已同步;真机实证两配置解耦(AI_IMAGE_MIN_SCORE=0.95 干扰下建议不变、图库搜索变 0 命中),0.4 校准留痕 §7.2 → `4236391 fix(image)`
- [x] `npm run build` 通过(B/C/card-layout 各自构建均过)
- [x] docs/spec/image.md §7 与实现一致(本批次无表结构变更,§7.2 过滤链 7 步 + 门槛解耦 + 采纳落库三层已同步)
- 追加项:浏览器验收时用户发现建议结果在 420px 抽屉下展示拥挤 → 独立轻量任务 `10-11-img-suggest-card-layout`(横排卡+标签折叠),已实现/核查/真机验收并随本 parent 同期归档 → `61637e8 fix(ui)`

## 前置 / 环境收口(本批次启动前处理,不属于任何子任务)

- 10-11 上午已修复的回归 bug(`PreviewImageDrawer.vue` 组合函数入参函数→computed 瓦解 projectId)目前**未提交**:在 A/B/C 任一提交之前先独立 `fix(ui)` 提交,不并入子任务。
- 后端容器镜像落后 1 提交(`1c03c48 feat(10-10): 盖世车企销量 V20` 未进 10-10 22:37 构建的镜像):本批次验收全部走 `docker compose up -d --build backend` 重建后的环境。
- 本地 `dev.sh` 后端进程已死(maven 启动失败),联调一律用容器环境(5661/8088)。

## Acceptance Criteria——✅ 2026-10-12 达成

- [x] 三个子任务各自独立验收通过并归档(A/C 已归至 archive/2026-10/,B 随本收口归档)
- [x] 合并后整合验收(Cross-child 清单)通过(用户浏览器真机批准)
- [x] 环境收口完成:bug 修复独立提交(376c24d)、后端容器重建(含 V20 迁移)、联调全程走容器环境

## Notes

- 本批次无数据库迁移(全部为读取过滤/前端行为/配置默认值)。
- 硬约束全程不动:suggest 零副作用、采纳才写入正文、无自动插入开关。