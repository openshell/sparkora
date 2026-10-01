# 前端 Playwright 测试（视觉回归 + 冒烟 E2E）

Sparkora 前端的自动化门禁：`npm run build`（产线构建）+ 这里的 Playwright 测试。测试**完全旁挂**——不改产品代码、不依赖后端/AI/图床/微信，`/api` 全拦截返回固定 fixture。

## 首次准备

```bash
cd frontend
npm i                 # 依赖含 devDep @playwright/test
npx playwright install chromium   # 下载浏览器到用户缓存（不进仓库）
```

## 运行

```bash
npm run test          # 全量（冒烟 + 视觉）
npm run test:smoke    # 只跑功能冒烟
npm run test:visual   # 只跑视觉回归（基线对比）
npx playwright test tests/smoke/login.spec.js --headed   # 单条用例、有头调试
npx playwright show-report                                # 看失败截图 / trace
```

- 前端 dev server 由 `playwright.config.js` 的 `webServer` 自动起/复用（5173，`reuseExistingServer: !CI`）。
- **后端不需要启动**：所有 `/api/**` 都在浏览器内被拦截，请求不会打到 `:5661`，也不会触发真实 DB/AI/图床/微信。
- 单 worker（`workers: 1`）跑视觉，优先截图一致性。

## 更新视觉基线

版式**有意**变更（或首次补录）后重录基线：

```bash
npx playwright test tests/visual --update-snapshots
```

- 基线在 `tests/visual/<spec>.js-snapshots/*.png`（含平台后缀，如 `-linux.png`），**属于要提交的资产**。
- `test-results/`、`playwright-report/` 是运行产物，已在根 `.gitignore` 忽略，不要提交。
- **正式全量视觉基线待 pc-ui 批 3 落地后一次性重录**（当前 6 页 × 2 档宽度 × 明暗 = 24 条，批 3 会大改版式，届时全部作废重录）。见 `.trellis/tasks/10-01-e2e-testing/prd.md` 的 Deferred。
- 想验证「基线真的在守」：临时改 `src/assets/main.css` 一个 token → `npm run test:visual` 应报红 → 还原。

## 目录结构

```
frontend/
├─ playwright.config.js      baseURL/webServer/快照策略（maxDiffPixelRatio 0.01）
└─ tests/
   ├─ fixtures/
   │  ├─ index.js            test/expect 扩展：安装 API 拦截 + 预置登录态
   │  ├─ data.js             固定 JSON fixture（项目/版本/图库/问答/发布选项…）
   │  ├─ stable.js           applyTheme（goto 前）/ prepareStable（禁动画+等稳）
   │  └─ matrix.js           宽度×明暗矩阵单一真源；SMOKE_VIEWPORT
   ├─ smoke/                 功能冒烟（登录/项目列表/步骤流/预览/问答/图库 + 关键页无 console 噪声）
   └─ visual/                视觉回归（ProjectList / ImageLibrary / StepPreview /
                             StepVersions / StepPublish / QaChat × 1280/2560 × 明暗）
```

## 约定（改动测试前必读）

1. **spec 必须 `import { test, expect } from '../fixtures/index.js'`**，不要从 `@playwright/test` 直连——mock 与登录态 fixture 都挂在扩展后的 `page` 上。
2. **拦截按 `pathname.startsWith('/api/')` 判断**，不用 `**/api/**` glob：否则 vite 自身的 `/src/api/*.js` 模块请求会被回 JSON，浏览器 MIME 校验拒载 → 应用白屏。非 `/api` 的外链资源用固定 PNG 兜底，本地资源 `route.fallback()` 放行。
3. **`applyTheme(page, theme)` 必须在 `goto` 之前**（主题在启动时读 `localStorage.sparkora_theme`）。
4. **截图前等稳**：`prepareStable`（禁动画/过渡/光标 + `networkidle` + `document.fonts.ready`）+ 页面契约锚点可见。
5. **断言用契约类名**：`.wenyan-preview`、`.step-body`、`aside.step-rail`、`.ctxbar .crumb`、`.bubble-row`、`.splitter`……不写脆弱 `nth-child`。
6. **fixture 字段名对齐实体**：版本 `versionLabel`（不是 `label`）、项目 `createdBy`（不是 `creator`）——写错只会静默渲染兜底值，不报错。
7. **步骤页会被状态机前跳**：`ProjectLayout` 在路由落后于 `activeStepOf(status)` 时自动 `router.replace`；要测非活跃步骤页，先落活跃页再点步骤 rail。

## 新增/修改用例的自检

```bash
cd frontend
npx playwright test --list      # 列出用例
npm run test:smoke              # 冒烟（后端可不开）
npm run test:visual             # 视觉（二跑应幂等全绿）
npm run test                    # 全量
git diff --stat -- src/         # 期望为空：产品代码零改动
```
