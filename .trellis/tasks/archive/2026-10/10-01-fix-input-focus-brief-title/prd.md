# 修复文本输入框聚焦样式异常与简报标题选择未生效

## Goal

修复两个已确认的前端/后端缺陷：

1. **输入框聚焦样式异常**：全站所有文本输入框（`el-input` / `el-textarea`）在鼠标点击聚焦后，左侧出现空白、焦点没有落到整块输入框上，视觉上「只有左边一小段有线、整块没聚焦」。
2. **简报选定标题未生效**：在简报页点选了标题，但生成版本、预览、发布到微信草稿时使用的仍是项目主题名（用户感知的「项目名称」），选定标题从未真正生效。

## Background（已确认事实 · 含 file:line 锚点）

### 缺陷 1：输入框聚焦样式异常

- **根因**：`frontend/src/assets/main.css:214-218` 有一条无限定的全局规则：
  ```css
  :focus-visible {
    outline: none;
    box-shadow: var(--focus-ring);   /* 双层环：0 0 0 2px card, 0 0 0 4px brand */
    border-radius: var(--radius-sm);
  }
  ```
  `--focus-ring` 定义于 `frontend/src/assets/main.css:87`（暗色 `:160`）。
- Element Plus 的 `.el-input__inner` 是真正接收焦点的 `<input>`（透明背景、`padding:0`，可见的边框/底色全部由外层 `.el-input__wrapper` 的 inset box-shadow 提供）。鼠标点击输入框时 inner 也命中 `:focus-visible`，于是 `--focus-ring` 的双层环被画在 **inner 文本起点处**，与 wrapper 的 `.is-focus` 描边叠加错位，呈现出「左侧留白/只有左侧有线、整块未聚焦」。
- 全局无任何规则覆盖 `el-input/textarea` 的高度或 padding；`--control-h-*`（17 处）只作用于自绘控件。
- 全库无统一 TextInput 封装，文本输入均直接用原生 `el-input`，与「所有输入框都有异常」的现象面吻合。
- 已有局部覆盖的先例：`frontend/src/components/image/ImageLibraryToolbar.vue:126-127` 为图库工具栏的 `:deep(.el-input__wrapper.is-focus)` 自定义了品牌焦点环——说明组件库 wrapper 聚焦态是可行的落点。

### 缺陷 2：简报选定标题未生效

完整链路：

```
StepBrief 点选标题 → PUT /selected-title → sparkora_article_project.selected_title
   →【应参与版本生成】版本生成(deep/generate) → sparkora_article_version.title ← 断链
   → PreviewService.buildMarkdown(v.getTitle()) → frontmatter "title:"
   → PublishService → gzhContent{title} → wenyan /upload → /publish → 微信草稿
```

- 点选与落库**正常**：
  - UI `frontend/src/views/project/StepBrief.vue:131-147`（候选可点选、`picked` 高亮），`selectedTitle` 计算属性 `:270`，`canPickTitle` 守卫 `:272`（仅 `status==='READY'` 可选），`onPickTitle` `:273-285`（再点取消、写回后端后 `ensureProject(force:true)`）。
  - API `frontend/src/api/index.js:37-39` → `PUT /projects/{id}/selected-title`。
  - 后端 `src/main/java/com/sparkora/web/controller/ProjectVersionController.java:116-130`，单列 `UpdateWrapper` 写 `selected_title`（空串→null）。
  - 实体字段 `src/main/java/com/sparkora/domain/entity/ArticleProjectEntity.java:37`（注释即声明「生成版本时作为标题偏好注入 prompt」）。
  - 简报表**没有**已选标题字段：`ArticleBriefEntity` 只有 `titleCandidates`（候选 JSON 数组）。
- **断链 1（prompt 未注入）**：当前唯一活跃的生成路径是深度写作 `DeepWriterService.write`。其 user prompt 组装在 `src/main/java/com/sparkora/deep/service/DeepWriterService.java:242-255`，只把 `titleCandidates` 平铺注入（`appendBriefSection("标题候选", …)`，`:250`），**完全没有注入 `p.getSelectedTitle()`**（`deep` 包内 `selectedTitle/selected_title` 零引用）。
- **断链 2（落版本回退到项目名）**：`DeepWriterService.java:305` `v.setTitle(extractH1(p==null?null:p.getTopic(), content))`；`extractH1` 在 `:418-430`，正则只匹配正文首个 `^#\s+(.+)$` H1，**缺失/空白回退 `p.getTopic()`**。而深度写作的排版铁律只要求 `##` 二级小标题（`:232-233`、`LayoutRules`），AI 正文通常没有 `#` H1 → `extractH1` 几乎必然 miss → `version.title = project.topic`。这就是「微信草稿用项目名」的直接来源。
- **发布/预览同源**：`src/main/java/com/sparkora/service/PublishService.java:88,91` 与 `PreviewService.java:89-90,133` 一律取 `version.title`，所以预览里看到的也是项目名。
- **历史死代码**：`src/main/java/com/sparkora/service/VersionService.java:304-307` 是 `selectedTitle` 注入 prompt 的唯一现存消费点，但它位于 FAST 非仿写分支 `buildUserPrompt`，而 FAST 已封死（410：`ProjectVersionController.java:51-53`、`ProjectBriefController.java:37`）→ 该注入实际不可达；仿写分支 `buildImitationPrompt`（`:255-273`）同样未注入。
- 现存唯一能改变发布标题的手段是版本页手动改标题 `VersionService.java`（`updateVersionTitle`）→ 前端 `StepVersions.vue`。

## Requirements

### R1 — 输入框聚焦样式修复（前端）

- 消除全局 `:focus-visible` 规则对 Element Plus 输入控件 inner 元素的误伤：鼠标点击 `el-input` / `el-textarea` 聚焦时，焦点可视化必须落在**整块**输入框（wrapper）上，不得出现左侧孤立描边/留白。
- 修复后所有文本输入控件（单行、多行、各尺寸）聚焦态视觉一致、美观，符合品牌焦点环基调。
- 不破坏键盘可达性：其余可聚焦元素（按钮、链接、自绘控件、`title-tag` 等）的键盘 `:focus-visible` 环应保持可见。
- 不回归 Element Plus 的其它聚焦态（select、日期、图库工具栏已有自定义覆盖）。

### R2 — 简报选定标题生效（后端 + 可能的契约/前端提示）

- 当 `selected_title` 非空时，生成的版本标题必须采用该选定标题，并保证其进入预览 frontmatter 与发布到微信草稿的 `title`。
- 当 `selected_title` 为空时，保持既有回退语义（正文 H1 → 项目主题），不得回归。
- 选定标题应同时作为正文标题意图注入写作 prompt，使正文 H1（若产出）与版本标题一致，避免「标题与正文不符」。
- 兼容历史版本与未选标题项目：不改变既有已生成版本，不回填历史数据。
- 明确多风格批量生成的语义：同一项目一次批量生成的多个风格版本，标题处理需一致、可预期（采用同一选定标题；用户仍可在版本页单独修改）。

### R3 — 文档与测试

- 同步更新相关规格文档（`docs/spec/brief-generation.md` / `docs/spec/version-generation.md` 等）中关于标题来源/`extractH1`/`selected_title` 的契约描述。
- 补充/更新自动化测试覆盖：标题优先级（选定 > H1 > 主题）、未选标题回退、prompt 注入。

## Acceptance Criteria

- [ ] AC1：鼠标点击任意 `el-input` / `el-textarea` 聚焦，焦点环/描边覆盖整块输入框，无左侧孤立线条或空白；截图或人工核对通过。
- [ ] AC2：键盘 Tab 聚焦按钮/链接/自绘控件仍显示品牌焦点环（无回归）。
- [ ] AC3：项目在简报页选定标题后生成版本，`sparkora_article_version.title` 等于选定标题（当 `selected_title` 非空）。
- [ ] AC4：未选标题（`selected_title` 为空）时，版本标题回退语义与现状一致（正文 H1 → 项目主题），既有测试不回归。
- [ ] AC5：端到端：选定标题 → 生成 → 预览 frontmatter `title:` → 发布到微信草稿的标题均为选定标题（不再出现项目名）。
- [ ] AC6：深度写作 user prompt 中含选定标题注入；空标题历史 brief 的 prompt 与旧行为等价（现有 `DeepWriterServicePromptTest` 不回归）。
- [ ] AC7：批量多风格生成时标题策略一致且可预期（按 Key Decisions 最终口径）。
- [ ] AC8：后端 `mvn -q -DskipTests compile` 通过、`mvn test` 相关用例通过；前端 `npm run build` 通过。
- [ ] AC9：相关规格文档已同步更新。

## Out of Scope

- 不改动发布到微信的传输契约（`/upload`、`/publish` 字段、超时/非幂等策略）。
- 不新增「发布页手动改标题」功能；不改造版本页手动改标题既有能力。
- 不做历史已生成版本的标题回填/迁移。
- 不引入新的 UI 框架或统一输入框封装组件（除最小必要样式）。
- 移动端相关不做（本项目 PC-only）。

## Key Decisions

- **D1（标题权威来源 · 用户已确认「选定标题优先」）**：`selected_title` 非空时，版本标题直接采用它；为空才回退「正文首个 H1 → 项目主题」。同时把选定标题注入深度写作 prompt，使正文 H1（若产出）与版本标题一致。理由：与简报页文案「生成版本时将优先采用此标题」一致，确定性最强，且 `version → preview → publish` 全链路自然统一。代价：同项目批量多风格生成的各版本共用同一选定标题（用户仍可在版本页单独改）。
- **D2（仿写链路口径统一）**：`VersionService`（仅 IMITATION 分支可达）采用与深度链路相同的标题优先级，避免「主题创作选定标题生效、仿写不生效」的不一致。

## Deferred / Risks

- Playwright 视觉全量基线在 pc-ui 批 3 后统一重录；本任务不强制重录，仅在改动导致既有输入框聚焦截图变化时记录。
- 历史已生成版本不回填标题（Out of Scope）。
- 若验收截图显示 wrapper 鼠标聚焦对比度不足，再评估是否追加 wrapper 品牌环增强（当前默认不改变既有视觉基调）。

## Open Questions

- 无（阻塞问题已清零）。
