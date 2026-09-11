# Implement: 扩展 wenyan 主题库并支持社区主题发布

> 执行顺序：A 后端 → B 前端 → C 文档 → D 验证。每阶段结束跑对应验证命令。

## Phase A：后端主题目录与渲染分支

- [ ] A1 复制社区 CSS：`frontend/src/assets/wenyan-themes/*.css` → `src/main/resources/wenyan-themes/*.css`（7 个）。
      复制后 `diff -r` 两份目录应无差异。
- [ ] A2 新增 `WenyanThemeCatalog`（`com.sparkora.service`）：`ThemeMeta(id,name,group,color,bright)` record；固定 8 内置 + 7 社区；`@PostConstruct` 物化 classpath CSS 到 `{storageRoot}/../tmp/wenyan-themes/{slug}.css` 并缓存绝对路径；提供 `all()/ids()/isCommunity()/require()/cssPath()`。
- [ ] A3 `PreviewService`：
      - 注入 `WenyanThemeCatalog`。
      - `validTheme` 改用 `catalog.require(id).id()`（保留 null/blank → default 行为）。
      - `themeOptions()` 返回 `catalog.all()`（对象数组）。
      - `renderByCli`：社区主题传 `--custom-theme <absPath>` 且不传 `--theme`；内置传 `--theme`。
      - 社区 CSS 路径为空（物化失败）时抛中文异常 → 走降级链。
- [ ] A4 接口下发：
      - `ImageController.previewOptions`：`m.put("themes", previewService.themeOptions())`（对象数组）。
      - `ArticleProjectController.publishOptions`：同样改。
- [ ] A5 `WenyanProperties.themeNames` 标记 `@Deprecated` 并在注释说明不再参与校验；`.env.example` 的 `WENYAN_THEME_NAMES` 行加废弃注释。

**验证 A**：`mvn -q -DskipTests compile`（只读仓库加 `-Dmaven.repo.local=/tmp/m2repo`）。

## Phase B：前端分组下拉与目录驱动

- [ ] B1 `StepPreview.vue`：
      - `themeOptions` 存目录对象数组；新增 `builtinThemes`/`communityThemes` computed。
      - 下拉改 `el-option-group`（内置主题 / 社区主题），选项显示 `name` + 色点（`color`/`bright` 来自目录）。
      - `themeLabel(id)`/`themeColor(id)`/`themeIsBright(id)` 查目录对象。
      - `allThemeOptions` 移除或改造为目录对象。
- [ ] B2 `StepPublish.vue`：只读回显的 label/color/bright 改查目录对象（从 `options.themes`）。
- [ ] B3 确认 `wenyanRender.js` 对 `custom:*` 仍能解析 CSS（应无需改动）；`wenyanThemes.js` 保留 CSS_STORE。

**验证 B**：`cd frontend && npm run build`。

## Phase C：文档沉淀

- [ ] C1 新增 `docs/wenyan.md`：主题来源（8 内置 + 7 社区）、`--custom-theme` 用法与限制（本地路径/不传 `--theme`/不支持 URL）、发布链路主题落点、如何新增主题。
- [ ] C2 `docs/s0-spec.md` §11/§12：`themes` 契约由 string[] 改对象数组；主题范围说明改为 15 个；`preview-style` 校验说明更新。
- [ ] C3 `AGENTS.md` 增一行主题能力说明（文件 gitignored，本地）。

## Phase D：端到端验证

- [ ] D1 `mvn -q -DskipTests compile` EXIT=0。
- [ ] D2 `cd frontend && npm run build` EXIT=0。
- [ ] D3 重启后端（`./dev.sh restart backend`），`GET /api/images/preview-options` 返回 15 个主题对象、含分组。
- [ ] D4 保存社区主题（`PUT /preview-style {"theme":"custom:chazi"}`）→ `publish-options` 回显 `previewTheme=custom:chazi`。
- [ ] D5 直接调用 `PreviewService.renderByCli`（或 `POST /preview?theme=custom:chazi`）确认 HTML 带 chazi 样式（如 `#773098`/`姹紫` 特征色）。
- [ ] D6 未知主题 `theme=evil` → `R.fail(400)` 中文。
- [ ] D7 人工：草稿箱发布一个社区主题，确认观感（无法自动化，标记待人工）。

## Risky Files / Rollback Points

- `PreviewService.renderByCli`（参数构造）：改错会令所有主题渲染失败 → 保留 `--theme` 分支原样。
- `ImageController`/`ArticleProjectController` 的 `themes` 契约：破坏性变更，前后端必须同批。
- `WenyanThemeCatalog` 的 CSS 物化：路径错误会让社区主题全部降级。

## Validation Commands

```bash
mvn -q -DskipTests compile   # 仓库只读时加 -Dmaven.repo.local=/tmp/m2repo
cd frontend && npm run build
./dev.sh restart backend && ./dev.sh logs backend 50
diff -r frontend/src/assets/wenyan-themes src/main/resources/wenyan-themes
```
