# 实施计划 · 修复输入框聚焦样式与简报标题选择

> 前置：`prd.md`（需求/验收）、`design.md`（方案）。本文件为执行清单。
> 两个缺陷相互独立，可作为两个可独立验收的提交单元；建议先做缺陷 1（纯 CSS，低风险），再做缺陷 2（链路）。

## 缺陷 1：输入框聚焦样式（前端，纯 CSS）

### I1-1 修改全局焦点样式

- 文件：`frontend/src/assets/main.css`（`main.js:9` 最后引入，可覆盖 Element Plus）。
- 在 `:focus-visible`（`:214-218`）规则之后追加原生表单/组件库输入控件的排除规则：
  ```css
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
- 约束：不删除全局 `:focus-visible`（自绘控件仍依赖）；无需 `!important`；不动 wrapper 其它样式。
- 验证：
  - `cd frontend && npm run build`
  - 人工核对：`./dev.sh start` 后打开任一含输入框页面（登录、项目编辑、简报澄清、发布等），鼠标点击单行/多行输入框——焦点应覆盖整块 wrapper，无左侧孤立描边；键盘 Tab 到按钮/链接/自绘控件仍显示 `--focus-ring`。
- 可选（仅当截图显示 wrapper 聚焦对比度不足时）：追加 wrapper 品牌环（与 `ImageLibraryToolbar.vue:126-127` 同构），否则保持默认。

### I1-2 回归确认

- 检查 `.el-input__wrapper.is-focus`、`.el-select__wrapper.is-focused`、图库工具栏自定义环、日期/密码框聚焦均正常。
- 如 Playwright 视觉基线覆盖输入框聚焦态且因本改动变化，记录待统一重录（正式全量基线在 pc-ui 批 3 后；不在本任务强制重录）。

## 缺陷 2：简报选定标题生效（后端为主）

### I2-1 深度链路落库优先级 + prompt 注入

文件：`src/main/java/com/sparkora/deep/service/DeepWriterService.java`

- 新增私有 helper（语义：选定 > H1 > 主题）：
  ```java
  private String resolveTitle(String selectedTitle, String contentMd, String topic) {
      if (selectedTitle != null && !selectedTitle.isBlank()) {
          String s = selectedTitle.trim();
          return s.length() > 200 ? s.substring(0, 200) : s;
      }
      return extractH1(topic, contentMd);
  }
  ```
- `write()` 落版本处（`:305`）：
  - 由 `v.setTitle(extractH1(p == null ? null : p.getTopic(), content));`
    改为 `v.setTitle(resolveTitle(p == null ? null : p.getSelectedTitle(), content, p == null ? null : p.getTopic()));`
- `write()` prompt 组装处（`:250` 标题候选块之后）注入选定标题：
  ```java
  if (p != null && p.getSelectedTitle() != null && !p.getSelectedTitle().isBlank()) {
      user.append("\n【用户已选定标题,正文一级标题(#)请采用该标题,勿偏离原意】\n")
          .append(p.getSelectedTitle()).append('\n');
  }
  ```
- 约束：`p` 仍用既有快照（`:211-216`），不新增查询；空 selectedTitle 时 prompt 与落库均与现状逐字/逐义等价。

### I2-2 仿写链路口径统一（VersionService）

文件：`src/main/java/com/sparkora/service/VersionService.java`

- `generateOne` 落版本处（`:227`）由 `v.setTitle(title.isBlank() ? p.getTopic() : title);`
  改为优先级：`selectedTitle 非空 → selectedTitle`；否则 `AI title 非空 → title`；否则 `topic`（可抽同一 helper 或本地实现，注意 200 截断）。
- 仅影响 IMITATION 分支（主题创作生成已封死走深度）；不触碰已不可达的 FAST 逻辑。
- 若认为改动面需最小化，可评估此项为可选；但推荐做以保证口径一致。

### I2-3 测试

文件：`src/test/java/com/sparkora/deep/service/DeepWriterServicePromptTest.java`（必要时 `VersionServiceAsyncTest`）

- 用 `ArgumentCaptor<ArticleVersionEntity>` 捕获 `versionMapper.insert(...)` 断言标题优先级：
  1. selectedTitle 非空 + 无 H1 → title == selectedTitle
  2. selectedTitle 非空 + 含 H1 → title == selectedTitle（选定优先）
  3. selectedTitle 空 + 含 H1 → title == H1
  4. selectedTitle 空 + 无 H1 → title == topic
- prompt 断言：selectedTitle 非空 → user prompt 含注入块与标题文本；空 → 不含且现有用例不回归。
- 若做 I2-2：补 IMITATION 标题优先级用例。
- 注意现有测试的 mock：`projectMapper.selectById` 返回的 `ArticleProjectEntity` 需设 `selectedTitle`；`versionMapper.insert` 为 mock，需捕获。

### I2-4 文档同步

- `docs/spec/version-generation.md:19`：明确 `title` 来源优先级（选定 > H1/ AI > 主题）。
- `docs/spec/brief-generation.md:122`（`DeepWriterService.write` 描述）：补「选定标题注入 prompt + 落库优先级」。
- `docs/spec/brief-generation.md:125`：`extractH1` 复用描述与新优先级保持一致。
- 如实现采用 `version-generation.md` §接口表补充说明，保持字段级表格口径。

## 验证命令汇总

```bash
# 后端
mvn -q -DskipTests compile
mvn test                       # 至少覆盖 DeepWriterServicePromptTest / VersionServiceAsyncTest
# 前端
cd frontend && npm run build
# 联调手测
./dev.sh start                 # 或 ./dev.sh restart backend / restart frontend
```

## 验收映射

- AC1/AC2 ← I1-1/I1-2 手测 + build。
- AC3/AC4/AC7 ← I2-1/（I2-2）落库优先级 + 单测。
- AC5 ← 端到端：简报选标题 → 生成 → 预览 frontmatter title → 发布草稿标题。
- AC6 ← I2-1 prompt 注入 + 现有 prompt 测试不回归。
- AC8 ← 验证命令。
- AC9 ← I2-4。

## 风险 / 回滚点

- CSS 改动单条规则，点状回退。
- `DeepWriterService` 改动集中在 `write()` 两处 + 一个 helper；`VersionService` 一处。任一异常可独立回退，不影响另一缺陷。

## 交付前检查

- [ ] 两份改动的验收命令全部通过。
- [ ] `prd.md` 验收项逐条勾选。
- [ ] 规格文档已同步。
- [ ] 未触碰发布契约 / 历史数据回填 / 移动端。
