# 预览页遗留清理(四项)

## Goal

收口 10-12 预览页评审批次 journal 留痕的四条遗留:采纳降噪 / 字数口径统一 / localStorage 兜底 / 保存竞态防御。

## 需求来源

`.trellis/workspace/openshell_vm/journal-1.md`「预览页评审收口」节「留痕(中期方向/遗留)」;用户 2026-10-12 批准四项打包。

## Requirements

### R1 采纳降噪:persist 场景抑制「正文已保存」成功 toast(遗留 P3a)

- 现状:单张采纳 =「已插入到正文并登记插图」(composable :99)+「正文已保存」(宿主 saveContent :415)双成功;整组 =「已采用 N 张」(:139-140)+「正文已保存」双成功。
- 修向:**仅**建议采纳落库通道(`:save-content` prop,:97)换成抑制成功提示的变体——工具栏「保存正文」(:22)与「去发布」前置存(:225)成功提示**照旧**。
- 实现自定:如宿主 `saveContent({ silentSuccess } = {})`(默认 false,契约向后不变,返回值仍 boolean);`:save-content="() => saveContent({ silentSuccess: true })"` 或同义包装。
- **失败警示不受影响**:persist 失败仍走宿主中文警示(saveState='error' 语义不变)。

### R2 字数口径统一(遗留:StepPublish.vue:210)

- 发布页 `wordCount` 从 `(contentMd||'').replace(/\s/g,'').length` 改用 `frontend/src/utils/textStat.js` 既有派生(预览页 :182 同源),导出名以文件实际为准。
- 两页字数必须一致;不改 textStat 口径(上任务已定稿)。

### R3 localStorage 兜底(遗留:StepPreview 预置裸读裸写)

- 三处补 try/catch:初始化读 `SPLIT_KEY`(:249)、拖拽落盘(:269)、键盘微调落盘(:276);读写失败静默兜底(读=默认 50,写=忽略)。
- 对齐 `useSplitPane`/本任务 R6 的既有惯例;新键 `sparkora.previewWidth` 已有兜底,勿动。

### R4 保存竞态防御(遗留:saveContent await 窗口)

- 现状:保存 await 期间用户续打字 → 成功后 `originalMd = contentMd.value`(最新编辑)被误标已保存,而**落库的是请求时快照**,草稿也被无条件清除 → 最新编辑丢失保护。
- 修向:`saveContent` 入口 `const md = contentMd.value` 先固化快照,请求体用 `md`;成功分支:
  - `contentMd.value === md`(保存期间无新编辑)→ 现行为不变(originalMd=md、clean、清草稿、成功提示);
  - 有新编辑 → `originalMd = md`(**dirty 保持 true**),saveState 维持'dirty',**不清草稿**;成功提示可正常给(内容确实落了库)。
- 三处调用(工具栏/去发布前置存/采纳 persist)统一受益,不改 usePendingImageFlush 契约(其 flush 流程内 setContent 后自有后续 saveContent)。

### R5 核查与文档

- `.trellis/spec/frontend/index.md`「采纳结算后即时落库」dual-write 条目中「宿主 saveContent 自带…成功提示,回调只吞错防双 toast」语义需随 R1 修正(persist 抑制成功提示)。
- docs/spec/preview.md §4.1/去发布衔接处如有 saveContent 描述,核对是否需要最小同步;StepPublish 字数口径如 docs/spec/publish.md 有描述则同步,无则跳过。

## Acceptance Criteria

- [ ] 真机:单张采纳 → 仅「已插入到正文并登记插图」一条;整组 → 仅「已采用 N 张」;工具栏保存 → 仍有「正文已保存」
- [ ] 真机:同篇正文,预览页与发布页字数一致
- [ ] DevTools 禁 localStorage(或代码审查确认三处均 try/catch)无报错
- [ ] 真机/慢网:保存请求在途继续输入 → 成功后工具栏仍「未保存」、离开页面草稿仍在、最新编辑不丢
- [ ] `npm run build` 通过;`npx playwright test` 44 存量用例全绿
- [ ] spec/docs 最小同步(R5)

## Non-goals

- P2c 滚动同步锚点化(中期方向,不动)
- 自动保存、beforeunload 离开确认(维持既有策略)
- 不改采纳登记/双写回滚、登记时机、dirty→draft watch 结构(R2 节流已有)
- 无后端改动、无新依赖