# implement.md — Playwright 视觉回归 + 冒烟 E2E

> 顺序即依赖。每步可独立验证；任一步失败只回滚该步（测试基建旁挂，产品代码零影响）。
> 前置：`task.py start` 前需用户批准最终规划摘要。

## 0. 启动前检查

- [ ] 工作区干净：`git status --porcelain`（确认无其他未提交改动）
- [ ] 读 `.trellis/spec/frontend/index.md`（不可破坏类名契约、R6 Enter/IME、非幂等重入守卫——冒烟断言要用）
- [ ] 确认 `frontend/src` 无 vitest/playwright 现存依赖（`grep playwright frontend/package.json` 期望空）

## 1. Playwright 基建

- [ ] `cd frontend && npm i -D @playwright/test`
- [ ] `npx playwright install chromium`（下载浏览器到用户缓存）
- [ ] 新建 `frontend/playwright.config.js`：
  - `testDir: './tests'`；`baseURL: 'http://localhost:5173'`
  - `webServer: { command: 'npm run dev', port: 5173, reuseExistingServer: !process.env.CI }`
  - `use: { viewport: null, headless: !process.env.PW_DEBUG }`
  - `expect: { toHaveScreenshot: { animations:'disabled', caret:'hide', maxDiffPixelRatio: 0.01 } }`
  - `workers: 1`（截图一致性优先）
- [ ] 改 `frontend/package.json` 脚本：
  - `test`: `playwright test`；新增 `test:visual`: `playwright test tests/visual`；`test:smoke`: `playwright test tests/smoke`；保留 `build`: `vite build`
- [ ] 验证：`npx playwright test --list` 列出用例（此刻可为空）

## 2. 拦截层 + fixture（无产品代码改动）

- [ ] `tests/fixtures/stable.js`：`prepareStable(page)`（禁动画/过渡/光标 CSS + `networkidle` + `fonts.ready`）、`setTheme(page, theme)`（对齐 `store/theme.js` 的 `html.dark` + localStorage key）
- [ ] `tests/fixtures/data.js`：固定 fixture 数据（项目列表 3 条含中文主题/状态、时间戳写死；某项目有 2 个版本；预览 HTML 含 `.wenyan-preview` 内联样式与图床 URL；图库图片列表；问答会话；风格列表）
- [ ] `tests/fixtures/index.js`：
  - `installMocks(page)`：`page.route('**/api/**')` 按 design.md §2.1 路由表分派；未命中返回 `R.fail(404,'未 mock')`
  - `authed(page)`：`addInitScript` 预置 `sparkora_token`/`sparkora_user`（对齐 `store/user.js:6-7`）+ mock `/api/auth/me`
  - `test.extend` 提供 `page` 已装 mock + 登录态的 fixture（供 smoke/visual 复用）
- [ ] `tests/fixtures/matrix.js`：`MATRIX = [{width:1280,theme:'light'},{1280,'dark'},{2560,'light'},{2560,'dark'}]`；`SMOKE_VIEWPORT = {width:1440,height:900}`
- [ ] 验证：写一个临时用例打开 `/`，断言无未拦截请求报错、页面渲染出 fixture 项目行（跑通后并入正式用例）

## 3. 冒烟 E2E（功能零回归）

- [ ] `tests/smoke/login.spec.js`：真实填表提交 → mock `/api/auth/login` → 断言跳 `/`、token 落 localStorage
- [ ] `tests/smoke/project-list.spec.js`：渲染 fixture 行数；搜索框输入触发 `/api/projects` 请求；创建入口可见
- [ ] `tests/smoke/project-steps.spec.js`：进 `/projects/:id/preview` 等——断言步骤 rail 可见、`ProjectLayout` 轮询命中 mock；断言步骤页 `.step-body` / 上下文条 actions 渲染（`usePageHeader` 接线）
- [ ] `tests/smoke/preview.spec.js`：断言 `.wenyan-preview` 容器存在（契约类名）；预览渲染 HTML 被 iframe/srcdoc 承载；分栏拖拽后宽度变化
- [ ] `tests/smoke/qa-chat.spec.js`：输入并发送（mock `/api/qa/ask`）→ 消息上屏；**IME 安全**：模拟 composition 后 Enter 不触发发送（复用 spec R6 口径）
- [ ] `tests/smoke/image-library.spec.js`：进入图库渲染 mock 图；chip 筛选回写 URL `?tag=`（`syncRouteTag`）
- [ ] 验证：`npm run test:smoke` 全绿（在**后端未启动**下跑，证明拦截层生效）

## 4. 视觉回归管道（跑通，不铺满）

- [ ] `tests/visual/*.visual.spec.js`：每页遍历 `MATRIX`，`prepareStable` 后 `expect(page).toHaveScreenshot('name', { animations:'disabled', caret:'hide' })`
- [ ] 覆盖：ProjectList（表格）、ImageLibrary（网格）、StepPreview（分栏/画布）、QaChat（主从）——已迁移页
- [ ] 生成基线：`npx playwright test tests/visual --update-snapshots`，提交 `*-snapshots/`
- [ ] 证明「它在守」：临时改 `main.css` 一个 token → `npm run test:visual` 报红 → 还原（并在结果中说明）
- [ ] 验证：二跑 `npm run test:visual` 幂等全绿（无 flake）

## 5. 稳定性与文档

- [ ] 连跑 3 次 `npm run test`（smoke+visual）结果一致（AC5）
- [ ] 后端零改动证据：`git diff --stat -- src/` 为空
- [ ] `grep -rn "@media (max-width" frontend/tests` 期望 0（测试不引入响应式）
- [ ] README/任务文档注明：首次 `npx playwright install chromium`；`npm run test/test:visual/test:smoke`；更新基线 `--update-snapshots`；正式全量基线在批 3 后重录
- [ ] `sparkora-spec-check`：确认新增测试不违反前端 spec（不改产品代码即应零偏差）

## 6. 收尾（Phase 3）

- [ ] 3.3 spec 同步：`.trellis/spec/frontend/index.md` 增「前端测试基建」条目（测试目录约定、拦截层、基线更新流程、契约类名用于断言）
- [ ] 3.4 commit：`chore(task): Playwright 视觉回归+冒烟 E2E 基建`
- [ ] 3.5 交付说明：正式全量视觉基线待批 3 后补（已记入 prd Deferred）

## 验证命令

```bash
cd frontend
npx playwright install chromium          # 首次
npm run test:smoke                       # 冒烟（后端可不开）
npm run test:visual                      # 视觉（二跑幂等）
npm run test                             # 全量
npx playwright test --update-snapshots   # 更新基线
npx playwright show-report               # 看失败截图/trace

# 边界自查（产品代码零改动）
git diff --stat -- src/                  # 期望空
git diff --name-only | grep -v "frontend/tests\|frontend/package.json\|playwright.config.json\|.trellis/"  # 期望空
```

## 风险与回滚点

| 风险 | 缓解 | 回滚点 |
|---|---|---|
| 为让测试可跑被迫改产品代码 | 铁律：改产品代码即回规划 | 任一步都可单独 revert，产品零影响 |
| 视觉 flake（字体/动画/时序） | 禁动画+等稳+固定 viewport+单 worker | 删 `*-snapshots/` 重录 |
| fixture 字段不对致页面渲染空态 | 实施时逐一核对 `api/index.js` 与页面读取结构 | 改 fixture（旁挂，无产品影响） |
| `test` 脚本改语义影响既有流程 | `build` 保留 vite build；仓库内引用已核 | 回退 package.json 脚本 |

## 明确的非目标

- 接 CI、Vitest 组件单测、真实后端联调 E2E、性能压测、axe 无障碍审计
- pc-ui 批 3 UI 迁移本身（本任务只为它提供网）
- 正式全量视觉基线（批 3 后补）