# Design: 扩展 wenyan 主题库并支持社区主题发布

## 1. Architecture & Boundaries

主题能力集中在三层，**不触碰 wenyan-server 协议**：

```
前端下拉(目录驱动)  →  PUT /preview-style(白名单校验)  →  projects.preview_theme
      │                                                          │
      └─ 浏览器预览：@wenyan-md/core + 社区 CSS(前端打包)         │
                                                                 ▼
发布：PublishService → PreviewService.renderByCli(主题在此应用) → gzhContent(纯HTML) → server
```

- **权威主题目录**放在后端（新类 `WenyanThemeCatalog`），同时供：后端白名单校验、`preview-options`/`publish-options` 下发、前端渲染展示。
- **社区 CSS 双份存在**：后端 `src/main/resources/wenyan-themes/*.css`（CLI `--custom-theme` 用）+ 前端 `src/assets/wenyan-themes/*.css`（浏览器预览用）。两份内容必须一致（见 §5 风险与 §6 校验）。

## 2. Theme Catalog（后端）

新增 `src/main/java/com/sparkora/service/WenyanThemeCatalog.java`：

```java
public record ThemeMeta(String id, String name, String group, String color, boolean bright) {}

// 内置 8（group="builtin"，name 用 id 原样，color 与前端 THEME_COLORS 对齐）
// default/orangeheart/rainbow/lapis/pie/maize/purple/phycat
// 社区 7（group="community"，id="custom:<slug>"，name=中文名，color 取自 wenyanThemes.js）
// custom:chazi(姹紫) mohei(墨黑) nenqin(嫩青) hongfei(红绯)
// lanqing(兰青) shanchui(山吹) quanzhanlan(全栈蓝)
```

方法：
- `List<ThemeMeta> all()` / `List<String> ids()`
- `boolean isCommunity(String id)`
- `ThemeMeta require(String id)`（未知抛 `IllegalArgumentException("未知主题: ...")`）
- `String cssPath(String id)`：社区主题返回已物化的**绝对路径**（见下），内置返回 null。

**CSS 物化（jar 安全）**：classpath 资源在打包为 jar 后 `getFile()` 不可用，故 `@PostConstruct` 时把 `classpath:wenyan-themes/*.css` 逐个复制到 `{IMAGE_STORAGE_DIR}/../tmp/wenyan-themes/{slug}.css`（复用 `ImageProperties.storageRoot()`，与 `PreviewService.createTempMd` 同一数据盘策略），缓存绝对路径。复制失败记录 warn，该主题渲染将走降级链。

## 3. Contracts

### 3.1 `PreviewService` 渲染分支（`renderByCli`）

```
if (catalog.isCommunity(theme))  → cmd = [wenyan, render, --custom-theme, <abs css path>, ...flags]
else                              → cmd = [wenyan, render, --theme, <id>, ...flags]
```
关键约束（实测）：
- 社区主题**不得同时传 `--theme`**（`--theme` 会覆盖 `--custom-theme`）。
- `--custom-theme` **不支持 URL**，必须本地路径。
- `validTheme` 改用 `catalog.ids()` 校验（替换 `wenyanProps.themeNameList()`）；`themeOptions()` 改返回目录 id 列表。

### 3.2 下发接口（响应契约变更）

`GET /api/images/preview-options` 与 `GET /api/projects/{id}/publish-options` 的 `themes` 字段由 `string[]` 变为对象数组：

```json
"themes": [
  { "id": "default", "name": "default", "group": "builtin", "color": "#1a73e8", "bright": false },
  { "id": "custom:chazi", "name": "姹紫", "group": "community", "color": "#773098", "bright": false }
]
```
其余字段（`highlights/defaultTheme/...`）不变。前端两处消费点同步改造。

### 3.3 持久化

`projects.preview_theme` 直接存主题 id（含 `custom:*`），沿用现有 `PUT /preview-style` 与 `publish-options` 回显，**无需 schema 变更**（列宽 VARCHAR(64) 足够）。

### 3.4 `.env`

`WENYAN_THEME_NAMES` 不再作为权威清单（目录改为代码内固定）；`WenyanProperties.themeNames` 字段保留但标记废弃、不再参与校验。`WENYAN_DEFAULT_THEME`/`WENYAN_HIGHLIGHT`/`WENYAN_MAC_STYLE`/`WENYAN_FOOTNOTE` 不变。`.env.example` 同步注释说明。

## 4. Frontend

- `frontend/src/utils/wenyanThemes.js`：保留 CSS_STORE（浏览器渲染社区主题仍需），`CUSTOM_THEMES` 可保留供渲染兜底；色点/名称改由后端目录提供，避免两处真值。
- `StepPreview.vue`：
  - `themeOptions` 存目录对象数组；`computed` 拆 `builtinThemes` / `communityThemes`。
  - 下拉改 `el-option-group`（label「内置主题」/「社区主题」），选项显示 `name` + 色点（`color`/`bright` 来自目录）。
  - `themeLabel(id)` 查目录 → name。
- `StepPublish.vue`：只读回显的 `themeLabel`/`themeColor`/`themeIsBright` 改用目录数据。
- `api/index.js` 无需改动（端点不变）。

## 5. Compatibility / Migration

- **响应契约破坏性变更**（`themes` string[] → object[]）：前后端同仓同步发布，无外部消费者。spec §11/§12 同步。
- 旧项目 `preview_theme` 存的是内置 id，仍在新目录内，兼容。
- 若某社区 CSS 缺失/物化失败，该主题仍可选，但预览/发布走 `degraded` 降级并给出中文原因（现有机制）。

## 6. Trade-offs

| 选择 | 理由 | 代价 |
|---|---|---|
| 目录固定在后端代码 | 社区主题与 CLI CSS 路径强绑定，env 无法表达 | 新增主题需改代码（可接受，属固定产品目录） |
| `--custom-theme` 免注册 | 不污染宿主 `~/.config/wenyan-md`，无重名冲突 | 每次渲染多传一个参数 |
| 前端/后端各存一份 CSS | 前端离线预览 + 后端 CLI 各取所需 | 两份需保持一致（见校验） |
| `themes` 直接改对象数组 | 前端一次拿到分组/名称/色点，消除本地重复真值 | 破坏性契约变更（同仓可控） |

## 7. Rollout / Rollback

- 回滚点：`WenyanThemeCatalog` 与 `renderByCli` 分支、两个 options 接口、两个 Vue 视图、spec 文档。
- 回滚策略：还原 `validTheme` 用 `.env WENYAN_THEME_NAMES`，前端 `themes` 按 string[] 解析即可回到旧行为。
- 验证命令见 `implement.md`。

## 8. Risks / Open Technical Unknowns

- **CSS 一致性**：前后端两份社区 CSS 可能漂移 → `implement.md` 加一步 diff 校验（复制后 `diff` 应为空）。
- **`:root` 变量泄漏**：社区 CSS 含 `:root { --sans-serif-font... }`，前端 scoped 重写只替换 `#wenyan`，`:root` 变量会成为全局（影响有限，仅字体变量）。实现时确认是否需要额外处理；CLI 侧原生支持。
- 社区主题在微信编辑器的最终观感需真机/草稿箱人工确认（无法自动化）。
