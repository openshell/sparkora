# 修复单行输入框 @keydown 遗漏 .enter 修饰符导致的按键误触发提交

## Goal

修复回归缺陷：新建项目页与简报澄清表单的单行 `el-input` 绑定了**不带 `.enter` 修饰符的 `@keydown`**，导致按**任意键**都会执行 `preventDefault()` 并触发提交逻辑。用户可感知后果：创作主题框**无法输入数字**（被吞掉且同时触发生成）、**无法粘贴**（`Ctrl+V` 的 keydown 被拦截）、任意按键都可能误触发创建/锁定。

## Background（已确认事实 · file:line）

### 缺陷 A：ProjectEdit（用户直接报告）

- `frontend/src/views/ProjectEdit.vue:46`（创作主题）、`:69`（关键词）、`:72`（目标读者）三处单行 `el-input` 绑定：
  ```
  @keydown="onEnterSubmit"
  ```
- handler `ProjectEdit.vue:209-214`：
  ```js
  const onEnterSubmit = (e) => {
    if (e.isComposing || e.keyCode === 229) return
    e.preventDefault()
    onSaveAndGenerate().catch(() => {})
  }
  ```
  只挡 IME 组字，**没有判断是否 Enter**，随后无条件 `preventDefault()` + 触发生成。
- 后果：
  - 数字键（如 `1`）：`preventDefault()` 阻止字符输入 → 打不出数字；同时 `keydown` 每次触发 `onSaveAndGenerate()` → 「输入数字直接触发简报生成」。
  - `Ctrl+V`：keydown 到达 handler → `preventDefault()` 阻止粘贴默认行为 → 粘贴无效。
  - 任意字母/符号同理（多数能被后续 input 事件救回，但数字/组合键行为异常）。

### 缺陷 B：ClarifyForm（同类连带隐患）

- `frontend/src/views/project/deep/ClarifyForm.vue:27`（「其他」自由输入）、`:29`（`type==='input'` 自由输入）绑定：
  ```
  @keydown="onEnter"
  ```
- handler `ClarifyForm.vue:107-111`：
  ```js
  const onEnter = (e) => {
    if (props.locked) return
    if (e.isComposing || e.keyCode === 229) return
    onSubmit()
  }
  ```
  **完全没有按键判断**，任意键（非组字态）都会 `onSubmit()` → `emit('submit')`；`StepBrief.vue:214/499` 收到后锁定需求并开始研究。即：澄清表单里只要出现自由文本输入框，敲任意字符都会立即提交并锁定。
- 无 `preventDefault`，故字符通常仍能输入，但输入即提交，用户无法正常填写。

### 对照（正确写法）

- `frontend/src/views/QaChat.vue:121`：`@keydown.enter="onEnter"`（正确，且 handler 内 `preventDefault` 防换行/表单提交）。
- `frontend/src/views/project/StepVersions.vue:127`、`ProjectList.vue:28`、`NewsKnowledgePanel.vue:5`：`@keyup.enter`（正确）。
- `ClarifyForm.vue` / `ProjectEdit.vue` 的裸 `@keydown` 是唯一两处偏差。

### 归因

引入于提交 `537498b`（2026-09-29 `feat(ui): pc-ui 批2 … Enter 提交`）。同提交引入的前端 spec（`.trellis/spec/frontend/index.md:126`）明确写的是「`@keyup.enter` 提交表单」约定，**实现写成裸 `@keydown` 与该约定不符**，属实现与规格的漂移。

## Requirements

### R1 — ProjectEdit 三处单行输入改为仅 Enter 触发

- `ProjectEdit.vue:46/69/72` 三处 `@keydown="onEnterSubmit"` 改为 `@keydown.enter="onEnterSubmit"`。
- 保留 `keydown`（而非换成 `keyup`）：Enter 的 keydown 必须 `preventDefault` 以阻止浏览器在单行输入框上的隐式表单提交/换行；`onEnterSubmit` 内的 `preventDefault`、IME 判断、`onSaveAndGenerate` 逻辑保持不变。
- 修复后：数字、粘贴、任意字符均可正常输入；仅 Enter（非组字态）触发创建。

### R2 — ClarifyForm 两处自由输入改为仅 Enter 触发

- `ClarifyForm.vue:27/29` 两处 `@keydown="onEnter"` 改为 `@keydown.enter="onEnter"`。
- 修复后：自由文本输入框可正常键入，仅 Enter（非组字态）提交澄清；`locked` 只读回显仍不触发。

### R3 — 规格同步 / 防回归

- 在 `.trellis/spec/frontend/index.md` 的 Enter 提交约定处补一条**明确定式**：需要 Enter 提交的输入必须写 `@keydown.enter` 或 `@keyup.enter`，**禁止裸 `@keydown` 后由 handler 自判 Enter 之外又无条件 preventDefault/submit**；并记录本次缺陷形态（裸 `@keydown` → 任意键 preventDefault/提交）。
- 可选：在 `frontend/tests/` 补一条冒烟/回归断言（如创作主题框输入数字不触发生成、可粘贴），视 E2E 基建成本决定。

## Acceptance Criteria

- [x] AC1：新建项目页「创作主题」框可正常输入数字（`123` 可见），输入过程中不触发生成。（修复：仅 Enter 触发）
- [x] AC2：新建项目页三处单行框（主题/关键词/目标读者）可正常 `Ctrl+V` 粘贴，粘贴不触发生成/不失败。（修复：非 Enter 不再 preventDefault）
- [x] AC3：在单行框中按 Enter 仍能触发「创建并生成简报」（非幂等入口的既有同步重入守卫仍生效）。（`onEnterSubmit` 逻辑未改）
- [x] AC4：中文输入法组字态回车仍只「选词」，不触发生成（IME 安全不回归）。（handler 内 IME 判断保留）
- [x] AC5：简报澄清表单的自由文本输入框（`type==='input'` 与「其他」）可正常键入任意字符且**不**在每次按键时提交；仅 Enter 锁定/开始研究。
- [x] AC6：`cd frontend && npm run build` 通过。（✓ built in 1m 1s）
- [x] AC7：`.trellis/spec/frontend/index.md` 已补充定式与防回归说明。
- [x] AC8：全局 `grep` 确认无其它「需要 Enter 提交却绑定裸 `@keydown`」的输入控件（仅剩 StepPreview/QaChat splitter `@keydown`，方向键语义）。

## Out of Scope

- 不改 `onSaveAndGenerate` / `onClarifySubmit` 的业务逻辑（仅改触发条件）。
- 不改 `@keyup.enter` 已有正确写法。
- 不改分栏 splitter 的 `@keydown`（方向键调宽，语义不同）。
- 不重构为统一的「Enter 提交」composable（除有明确收益，不在本任务）。

## Notes

- 轻量任务：纯模板属性修复，无需 `design.md` / `implement.md`。
- 改动点：`ProjectEdit.vue` 3 行、`ClarifyForm.vue` 2 行；风险极低，回滚即还原属性名。
