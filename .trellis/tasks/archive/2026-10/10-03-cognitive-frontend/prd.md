# C5 前端认知层交互

> 父任务:`../10-03-gen-cognitive-redesign`。
> **依赖:C1、C3**(对话接口 + 蓝图接口就绪)。

## Scope

对话式澄清 UI(替代 `ClarifyForm` 一次性表单)+ 蓝图评审 UI(结构化展示/编辑/确认);扩展 `StepBrief.vue` 深度状态机。

## Acceptance Criteria

- [ ] 澄清 UI:逐轮问答、选项/默认值/跳过,展示收敛进度;收敛后可查看 TaskBrief。
- [ ] 蓝图评审 UI:展示 thesis/论证结构/evidenceMap(含 coverage)/质量信号;支持编辑论据与 entryKeys;确认后解锁写作。
- [ ] `StepBrief.vue` `deepStage` 扩展(ASKING/CONVERGED/BLUEPRINT_REVIEW),完成态分支不吞面板。
- [ ] `src/api/index.js` 增具名导出;Enter 提交 IME 安全 + 防重入。
- [ ] `npm run build` 通过;涉及门禁时 `cd frontend && npm run test` 通过。

## Out of Scope

- 后端认知逻辑(C1~C4)。
