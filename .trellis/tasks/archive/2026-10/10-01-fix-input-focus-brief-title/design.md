# 设计 · 修复输入框聚焦样式与简报标题选择

## 1. 缺陷 1：输入框聚焦样式

### 现状与根因

`frontend/src/assets/main.css:214-218` 的全局规则：

```css
:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
  border-radius: var(--radius-sm);
}
```

`main.css` 在 `frontend/src/main.js:9` 于 `element-plus/dist/index.css`（`:4`）之后引入，故该规则可覆盖组件库。Element Plus 输入框结构：

```
.el-input（外层）
 └ .el-input__wrapper（可见边框/底色：inset box-shadow；聚焦加 .is-focus）
    └ .el-input__inner（真实 <input>：透明、padding:0、height:100%）
```

鼠标点击输入框时，真实 `<input>`（`.el-input__inner`）获得焦点并命中 `:focus-visible`，于是 `--focus-ring`（`0 0 0 2px card, 0 0 0 4px brand`）这套**外扩双层环**被画在 inner 的文本框上——inner 的盒从文本起点（wrapper 左内边距之后）开始且 padding 为 0，环就贴着文本左侧出现，与 wrapper 的 `.is-focus`（仅 1px 品牌描边）叠加，形成「左侧有一小段线、整块没聚焦」的错位视觉。`el-textarea__inner` 同理。

`--focus-ring` 使用 `box-shadow` 而非 `outline`，因此不会因 `outline` 被组件库 reset 而失效；它同时被自绘控件（`.title-tag:focus-visible` `StepBrief.vue:685`、`.session-item:focus-visible` `QaChat.vue:376`、splitter）正常使用，**不能简单删除全局规则**。

### 设计方案（方案 A：精确排除原生表单控件）

保留全局 `:focus-visible`（供自绘控件使用），新增一条更具体的选择器，把原生表单/组件库输入控件从「外扩环」路径中排除，改为「零环 + 交给 wrapper 的组件库聚焦态」：

```css
/* 原生表单控件与 Element Plus 输入控件：焦点可视化交给组件库 wrapper 的聚焦态，
   全局 --focus-ring 的外扩双层环会误画在 inner（透明、padding:0）的文本起点处，
   造成「左侧孤立描边/留白」。此处清除 inner 上的 global focus 视觉。 */
input:focus-visible,
textarea:focus-visible,
select:focus-visible,
.el-input__inner:focus-visible,
.el-textarea__inner:focus-visible {
  outline: none;
  box-shadow: none;
  border-radius: 0;
}
```

要点：

- **特异性**：`input:focus-visible`（0,1,1）> `:focus-visible`（0,1,0）；`.el-input__inner:focus-visible`（0,2,0）同样更高。无需 `!important`。
- **只清视觉，不改可达性**：输入框的键盘焦点仍由 wrapper 的 `.is-focus`（Element Plus 自带 CSS 变量驱动的描边）呈现；其余元素仍走 `--focus-ring`。
- **不碰** `.el-input__wrapper` / `.el-textarea__inner` 的其它样式，零回归风险；图库工具栏 `ImageLibraryToolbar.vue:126-127` 已有的 wrapper 自定义环不受影响。
- **可选增强（若验收发现 wrapper 在鼠标聚焦时对比度不足）**：追加一条 wrapper 聚焦态品牌环，与图库工具栏同构：
  ```css
  .el-input__wrapper.is-focus,
  .el-select__wrapper.is-focused {
    box-shadow: 0 0 0 1px var(--brand) inset, 0 0 0 3px var(--brand-weak) !important;
  }
  ```
  但默认**先只做排除**，避免改变已有视觉基调；是否加增强由验收截图决定。

### 备选与取舍

- **方案 B：把全局规则改为 `.focus-ring` 工具类，逐处挂载**。语义最干净，但需改约 4 处自绘控件且漏一处即丢可达性，改动面大、回归风险高。**不采用**。
- **方案 C：全局 `:focus-visible { outline: 2px solid var(--brand); box-shadow: none }`**。会让自绘控件失去既有双层环基调，视觉不一致。**不采用**。
- **方案 D：`:focus-visible:not(input):not(textarea)`**。特异性足够，但 `:not()` 链可读性差且未覆盖组件库类；显式选择器更直观。

### 验收方式

- 前端 `npm run build` 通过。
- 人工/截图核对：单行、textarea、密码框、select、日期等聚焦态；键盘 Tab 到按钮/链接/自绘控件仍有环。
- Playwright 视觉基线（`frontend/tests/visual`）若覆盖输入框聚焦态则按需重录（正式全量基线在 pc-ui 批 3 后统一重录，本任务不强制）。

## 2. 缺陷 2：简报选定标题未生效

### 现状与根因

数据流（详见 `prd.md` Background）：`selected_title` 落库正常，但**唯一活跃**的生成链路 `DeepWriterService` 不消费它：

- prompt 组装 `DeepWriterService.java:242-255` 未注入 `p.getSelectedTitle()`。
- 落版本 `DeepWriterService.java:305` → `extractH1`（`:418-430`）只认正文首个 H1，缺失回退 `p.getTopic()`；深度铁律只要求 `##`，故几乎必然回退项目主题。
- `VersionService.java:304-307` 的选定标题注入仅存在于已封死（410）的 FAST 非仿写分支，实际不可达；仿写分支亦未注入。

### 决策（用户确认）

**选定标题优先**：`selected_title` 非空时，版本标题直接采用它；为空才回退「正文 H1 → 项目主题」。同时把选定标题注入深度写作 prompt，使正文 H1（若产出）与版本标题一致。

### 设计方案

#### 2.1 标题优先级（确定性落库）

在 `DeepWriterService.write` 落版本处引入明确的优先级函数，替换现有单点回退：

```
resolveTitle(selectedTitle, contentMd, topic):
  if selectedTitle 非空白 -> selectedTitle（裁剪 200）
  h1 = 首个 H1(正则 ^#\s+(.+)$)
  if h1 非空白 -> h1（裁剪 200）
  -> topic
```

- `selectedTitle` 来自已取的 `ArticleProjectEntity p`（`:211-216` 已有快照，无需新增查询）。
- 保留 `extractH1` 的 200 字截断防御；选定标题后端写库时已限 200（`ProjectVersionController.java:123`），仍统一裁剪以防历史/并发脏值。
- 空/空白 `selectedTitle` 时行为与现状完全一致（AC4 零回归）。

#### 2.2 prompt 注入选定标题

在 `DeepWriterService.java:250` 的「标题候选」块之后，参考 `VersionService.java:304-307` 的既有措辞，追加选定标题块：

```
if (p != null && p.getSelectedTitle() != null && !p.getSelectedTitle().isBlank()) {
    user.append("\n【用户已选定标题,正文一级标题(#)请采用该标题,勿偏离原意】\n")
        .append(p.getSelectedTitle()).append('\n');
}
```

- 位置：放在标题候选之后、核心观点之前，保证「候选 + 明确选定」上下文相邻。
- 空标题历史 brief → 不追加 → prompt 与旧行为逐字等价（AC6）。
- 正文若产出 H1，通常等于选定标题；即便 AI 未产出 H1，2.1 的落库优先级仍保证版本标题正确（双重保障）。

#### 2.3 是否统一 VersionService（IMITATION 分支）

`VersionService` 仅 IMITATION 走 `/generate/versions`（`ProjectVersionController.java:51`）。为口径一致，同规则应用到 `VersionService.generateOne`：

- `generateOne` 当前 `v.setTitle(title.isBlank() ? p.getTopic() : title)`（`:227`），其中 `title` 来自 AI JSON。按新优先级改为：选定标题非空 → selectedTitle；否则 AI title 非空 → AI title；否则 topic。
- 该分支仅影响 IMITATION，不触碰已封死的主题创作分支；改动小、口径统一。
- **决策依据**：避免「主题创作选定标题生效、仿写不生效」的不一致。

#### 2.4 范围界定

- **不改** `PublishService` / `PreviewService`：它们取 `version.title` 的设计正确，源头修好后链路自然贯通（发布/预览同源）。
- **不改** 发布接口契约（无 title 入参）、不改 `publish-options` 字段。
- **不做** 历史版本回填。

### 契约/文档同步

- `docs/spec/version-generation.md:19` 对 `title` 的说明补充/明确来源优先级。
- `docs/spec/brief-generation.md:122`（`DeepWriterService.write` 描述）补充「选定标题注入 prompt + 落库优先级」。
- `docs/spec/brief-generation.md:125` 提到「复用给 `extractH1`」处需与新优先级保持一致表述。

### 测试设计

在 `src/test/java/com/sparkora/deep/service/DeepWriterServicePromptTest.java` 增补：

1. `selectedTitle` 非空 + 无 H1 → 版本 `title == selectedTitle`（用 `ArgumentCaptor<ArticleVersionEntity>` 捕获 `versionMapper.insert`）。
2. `selectedTitle` 非空 + 正文含 H1 → 仍 `title == selectedTitle`（选定优先于 H1）。
3. `selectedTitle` 为空 + 正文含 H1 → `title == H1`（现状保留）。
4. `selectedTitle` 为空 + 无 H1 → `title == topic`（现状保留）。
5. prompt 含选定标题注入块；空 selectedTitle 时不含该块且 prompt 与旧行为等价（不回归现有用例）。
6. 若改 `VersionService`：增 `IMITATION` 分支标题优先级用例（参照 `VersionServiceAsyncTest` 结构）。

### 风险与回滚

- 风险：`selectedTitle` 非空但用户后续在版本页手动改标题——既有 `updateVersionTitle`（`ProjectVersionController.java:102`）仍可覆盖，不受影响。
- 风险：批量多风格生成时各版本标题相同（选定标题）。这是决策预期行为；用户可在版本页单独改。
- 回滚：改动集中在 `DeepWriterService` 单一方法 + 一处 helper 与一条 CSS 规则，可点状回退。
