# design.md — Playwright 视觉回归 + 冒烟 E2E

## 1. 架构与边界

### 1.1 新增目录结构（全部在 `frontend/` 内，与 src 隔离）

```
frontend/
├─ playwright.config.js          ← 【新增】baseURL/webServer/projects/快照策略
├─ package.json                  ← 【改】加 @playwright/test devDep + 三个脚本
├─ tests/                        ← 【新增】测试根（不进 src，不被 vite build 打包）
│  ├─ fixtures/                  ← 固定 fixture + 路由拦截 + 登录态注入
│  │  ├─ index.js                ←   安装拦截/登录态的 setup helper
│  │  ├─ data.js                 ←   固定 JSON 数据（项目/版本/预览/图库/问答…）
│  │  ├─ stable.js               ←   禁动画/隐藏光标/等稳 等确定性工具
│  │  └─ matrix.js               ←   宽度×明暗矩阵单一真源
│  ├─ smoke/                     ← 冒烟用例
│  │  ├─ login.spec.js
│  │  ├─ project-list.spec.js
│  │  ├─ project-steps.spec.js
│  │  ├─ preview.spec.js
│  │  ├─ qa-chat.spec.js
│  │  └─ image-library.spec.js
│  └─ visual/                    ← 视觉回归用例
│     ├─ project-list.visual.spec.js
│     ├─ image-library.visual.spec.js
│     ├─ step-preview.visual.spec.js
│     └─ qa-chat.visual.spec.js
└─ src/                          ← 【零改动】生产代码，本任务不动一行
```

**边界铁律**：本任务**只增测试基建，不改任何产品代码**。不改 `src/`、不改后端 `src/`、不改 `vite.config.js`（只加 devDep/脚本到 package.json）。若为让测试可跑发现必须改产品代码，先停下回规划。

### 1.2 不依赖真实后端的核心机制：API 全拦截

前端 vite dev 正常会把 `/api` 代理到后端 `:5661`（`vite.config.js`）。测试里用 `page.route('**/api/**')` 在**浏览器内**拦截所有 `/api` 请求，按 URL 前缀返回固定 fixture：

- 请求**永不到后端** ⇒ 后端不需要启动，AI/图床/微信全不触发（AC4）。
- 返回写死 JSON ⇒ 无真实时间戳/随机值，截图可重复（AC5 幂等）。

拦截通过「`<pattern, handler>` 表」匹配，命中按前缀分派，未命中返回 `R.fail(404)`（与后端 `R<T>` 一致，前端 `res.code!==0` 走自己的空态，**不炸**）。

### 1.3 登录态绕过

两条并存：
- **默认**：每个测试 `beforeEach` 里 `addInitScript` 预置 `localStorage` 的 `sparkora_token`（伪 token）/`sparkora_user`（伪用户 JSON），并拦截 `/api/auth/me` 返回该用户 ⇒ 直接进 `/` 不经登录页。key 契约来自 `store/user.js:6-7`（`sparkora_token`/`sparkora_user`）。
- **登录页用例**：`tests/smoke/login.spec.js` 走真实表单交互，拦截 `/api/auth/login` 返回 `{code:0,data:{token,user}}`，断言提交后跳 `/` 且 token 落 localStorage。

### 1.4 视觉回归确定性

`webServer` 自动起 vite dev（`npm run dev`，5173），`reuseExistingServer: !CI`。

每张截图前调用 `stable.js` 的 `prepareStable(page)`：
- 注入 CSS 强制 `*{animation:none!important;transition:none!important;caret-color:transparent!important}`。
- `await page.waitForLoadState('networkidle')`。
- 等关键锚点可见（各 spec 自定，如 ProjectList 等 `el-table` 行、StepPreview 等 `.wenyan-preview`）。
- `document.fonts.ready`（字体加载完，避免回流抖动）。

主题（明暗）：`store/theme.js` 的机制是给 `<html>` 加/去 `dark` class。fixture helper 提供 `setTheme(page, 'dark'|'light')`：预置/切 `document.documentElement.classList` 并持久化（对齐 theme store 的 localStorage key）。

### 1.5 viewport 矩阵

config 用 `use.viewport = null`，由视觉用例显式设 viewport。矩阵以「项目」为单位遍历：`for w of [1280, 2560] for theme of ['light','dark']` ⇒ 每页 4 张，截图名 `{page}-{width}-{theme}.png`。断言 `toHaveScreenshot({ animations:'disabled', caret:'hide' })`。

宽度参数化放 `tests/fixtures/matrix.js`（单一真源），冒烟默认 1440×900。

## 2. 数据流与契约

### 2.1 拦截路由表（覆盖已迁移页所需最小集）

按 `api/index.js` 实际路径（`/api` 前缀）匹配：

| 路径前缀 | 用途 | 页面 |
|---|---|---|
| `/api/auth/me` | 登录态校验 | 全部 |
| `/api/auth/login` | 登录 | LoginView |
| `/api/projects` (GET list) | 项目列表 | ProjectList |
| `/api/projects/:id` (GET) | 项目详情 | ProjectLayout 轮询 |
| `/api/projects/:id/brief` | 简报 | StepBrief |
| `/api/projects/:id/versions` (GET) | 版本列表 | StepVersions |
| `/api/projects/:id/preview-options` | 主题/高亮 | StepPreview |
| `/api/projects/:id/preview` (POST) | 渲染 HTML（含 `.wenyan-preview`） | StepPreview |
| `/api/projects/:id/images` (GET) | 插图 | StepPreview |
| `/api/images` (GET list) | 图库网格 | ImageLibrary |
| `/api/qa/conversations|ask` | 问答会话/发送 | QaChat |
| `/api/styles` | 风格列表 | StepVersions |

未命中 ⇒ 返回 `R.fail(404)` 让前端走自身空态，不抛错。字段以 `api/index.js` 注释 + 各页面读取结构为准（实施时逐一核对，fixture 要够页面真渲染出内容，否则空态截图没意义）。

### 2.2 契约类名锚点

冒烟/视觉断言优先锚定跨文件**契约类名**（spec 已记），避免脆弱 nth-child：
- `.wenyan-preview`（wenyan 主题注入目标）— StepPreview 预览容器必有。
- `sparkora-img-missing` — 失效占位（保留断言位）。
- AppShell 上下文条元素（面包屑/动作）— 验证 `usePageHeader` 接线。
- 步骤 rail / `.step-body` / 预览 `.preview-page`。

### 2.3 不碰的东西

不改 `store/` `api/` `utils/` `composables/` `router` `constants` `components` `views` 任何一行；不改 `main.css` token、不改任何页面样式——视觉基线是「记录现状」，不是「改版式」。

## 3. 兼容与迁移

- **对产品零影响**：测试基建完全旁挂。`npm run build` 产物不含 `tests/`（vite 不打包 tests，不在 src 且无 import 引用）。
- **`package.json` 脚本**：`test` 改为 `playwright test`（全量）；新增 `test:visual` / `test:smoke`；`build` 保留 `vite build`（原 `test` 的 vite build 语义由 `npm run build` 承接，仓库内已知引用已核）。
- **浏览器安装**：首次 `npx playwright install chromium`（下载到用户缓存，不进仓库）。
- **无 CI**：本地跑；`reuseExistingServer: !CI` 复用已在跑的前端。

## 4. 关键取舍

| 取舍 | 选择 | 理由 | 代价 |
|---|---|---|---|
| 真后端 vs 全拦截 | 全拦截 fixture | 确定性/无副作用/快；AI 与发布极慢且要凭据 | fixture 需维护；真实后端回归覆盖不到 |
| 视觉 vs 组件单测 | 视觉 diff | 本次重构是版式重锤，布局/溢出/换行正是要守的 | 靠禁动画+等稳+固定 viewport 压脆弱性；对 UI 迭代敏感（故批 3 后才建正式基线） |
| 端点宽度 {1280,2560} | 只这 2 档 | 覆盖 AC2 最窄/最宽极端，性价比最高 | 中间档不逐一截图 |
| 正式基线时机 | 批 3 后 | 避免录 30+ 张必然失效的图 | 本任务期间版式无正式回归网 |
| 改 `test` 脚本 | 指向 Playwright，`build` 保留 vite build | 语义清晰 | 依赖旧 `test` 语义的脚本改指 `build` |

## 5. 运维与回滚

- 无后端改动、无 DB 迁移、无新运行时依赖（Playwright 是 devDep）。
- 回滚：删 `tests/` + `playwright.config.js` + revert package.json，产品代码零影响。
- 快照（`*-snapshots/`）随版式更新而 `--update-snapshots`；批 3 迁移后需重录（已记入 Deferred）。