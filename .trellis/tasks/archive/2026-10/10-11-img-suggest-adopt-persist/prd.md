# 采用建议后即时落正文消除半状态窗口

## Goal

采纳配图建议成功后同步触发既有正文保存,消除「登记行已落、content_md 未落」的半状态窗口(评审 P2)。

## 背景(问题定位)

采纳动作 = ①登记关联行 `POST /images/{imageId}/body?action=add`(网络写,立即生效) + ②编辑器 `insertMdAtAnchor` 插正文(内存态)。而 content_md 落库仅发生在:手动保存、「去发布」dirty 前置存。采纳后未保存就离开页 → 关联行已登记但正文没有 → 发布页插图计数虚高,直到下次保存对账收敛。窗口小但存在,且用户感知为"明明采用了却没落库"。

## Requirements

- **R1 采纳成功即落库**:单张「插入到此段」成功 → 立即触发一次正文保存;整组「全部采用」→ 整组完成后触发**一次**保存(不逐张触发)。
- **R2 复用既有通道**:保存必须复用 `StepPreview.saveContent`(`imageApi.saveContent(projectId, versionId, contentMd)`)既有实现,经 prop 回调传给抽屉(与 `refreshSnapshot` 同款接线模式)。不新增保存通道、不改登记时机、不动 400ms 编辑器防抖与草稿/localStorage 语义。
- **R3 失败不放大**:保存失败不回滚正文与登记行,仅警示提示(与既有失败文案风格一致);「去发布」dirty 前置存保持原语义(R1 落库后去发布时 content_md 已最新,前置存为空操作或幂等)。
- **R4 抽屉独立性**:drawer/composable 不感知保存实现,只拿到「persist 后再 refreshSnapshot」的回调约定;busy 标记期间互斥语义不变。

## Acceptance Criteria

- [ ] 真机:采纳一条建议 → 不离开页面直接查版本 content_md(或刷新页进入)已含该插图 markdown
- [ ] 整组采用 N 张 → 后端仅收到一次 saveContent 请求(N 次采纳 1 次保存)
- [ ] 模拟保存失败(断网/后端停)时:采纳流程其余步骤不受影响,有中文警示,无重复报错弹窗
- [ ] `npm run build` 通过
- [ ] 涉及契约变化处(若有)同步 `docs/spec/preview.md`(采纳时点描述)——若无描述性契约则只在变更日志层说明

## Non-goals

- 不改关联行登记时机与双写回滚逻辑(那是自洽设计本体)
- 不新增定时自动保存
- 不动图库 tab 手动插入的保存路径(该路径已有既有对账机制)

## Notes

- 依赖 parent 环境收口事项(前端容器验收走重建后镜像)。
- A 任务(dedup)候选收窄与本任务无依赖冲突,验收时 A 合入后重跑建议→采纳全流程一遍。