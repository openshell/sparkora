# Research: wenyan 主题扩展机制（09-11-wenyan-themes）

## 结论速览

发布链路的主题应用点在**本机 wenyan CLI 渲染阶段**，wenyan-server 只收渲染后的 HTML（`gzhContent`），不感知主题。因此「支持更多主题并可发布」= 让本机 CLI 能渲染这些主题 + 后端白名单放行 + 前端下拉展示，**无需改动 server 协议或往 server 注册**。

## 实测证据（CLI 2.0.11，server 同版本，均在本机执行）

| 命令 | 结果 |
|---|---|
| `wenyan theme -l` | 内置 8 主题：`default orangeheart rainbow lapis pie maize purple phycat` |
| `wenyan render -t rainbow --file x.md` | EXIT=0，即使不在 `.env` 白名单也能渲染（白名单是 Sparkora 侧约束，非 CLI 约束） |
| `wenyan render --custom-theme <chazi.css> --file x.md` | EXIT=0，自定义主题可渲染 |
| `wenyan render -t default --custom-theme <chazi.css> --file x.md` | 输出仍是 default 观感 → **`--theme` 覆盖 `--custom-theme`** |
| `wenyan render --custom-theme https://.../chazi.css` | EXIT=1 → **不支持网络 URL** |
| `wenyan render --custom-theme /nope.css` | EXIT=1 → 非法路径失败（走降级链） |
| `wenyan theme --add --name X --path <css>` | EXIT=0，写入 `~/.config/wenyan-md`；重名 EXIT=1「主题已存在」 |
| `wenyan theme --rm X` | EXIT=0 |

## 关键设计推论

1. **社区主题渲染命令**：`wenyan render --custom-theme <CSS绝对路径>`，**不带 `--theme`**。内置主题渲染：`wenyan render --theme <id>`，**不带 `--custom-theme`**。
2. **CSS 必须随后端包内置**：`--custom-theme` 不支持 URL，需把 7 个 CSS 复制到 `src/main/resources/wenyan-themes/`，运行时按 classpath 定位绝对路径传给 CLI。
3. **不写宿主 `~/.config`**：`--custom-theme` 免注册，避免污染宿主环境与重名冲突。
4. **前端已具备社区主题能力**：`wenyanRender.js` 的 `resolveThemeCss` 对 `custom:*` 直传 CSS；`buildWechatHtml` 对自定义主题走 `applyStylesWithResolvedCss`。此前仅后端白名单拒绝而移出下拉。
5. **前端主题色点映射已存在**：`StepPreview.vue:396` / `StepPublish.vue:193` 的 `THEME_COLORS` 已含 8 内置主题色；社区主题色点在 `wenyanThemes.js:16-24` 已定义（`color` 字段）。

## 现状代码锚点

- 白名单：`WenyanProperties.themeNames`（`.env WENYAN_THEME_NAMES`），`themeNameList()`（`WenyanProperties.java:51`）。
- 校验：`PreviewService.validTheme`（`:137`）、`requireTheme`（`:202`）。
- 渲染：`PreviewService.renderByCli`（`:217`），构造 `--theme` 参数于 `:225`。
- 目录下发：`ImageController.previewOptions`（`:147`）、`ArticleProjectController.publishOptions`（`:425`）均 `m.put("themes", ...)`。
- 前端消费：`StepPreview.vue:717` `themeOptions.value = res.data?.themes`；`StepPublish.vue:246` 初始化 `theme.value`。
- 社区 CSS 源：`frontend/src/assets/wenyan-themes/*.css`（7 个，1268 行）。

## 未决/风险

- 社区主题 CSS 是否含 `#wenyan` 选择器前缀（决定 CLI `--custom-theme` 与前端 scoped 重写是否一致）：需在实现时核对；前端 `applyPreviewTheme` 用 `replaceAll('#wenyan', PREVIEW_SELECTOR)`，CLI 则原生按 `#wenyan` 作用。
- `author`/`source_url` 透传 server（上一任务遗留风险）与本任务无关，不扩大范围。
