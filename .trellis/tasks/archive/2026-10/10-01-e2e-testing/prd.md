# 前端 UI 自动化测试（Playwright 视觉回归 + 冒烟 E2E）

## Goal

为 09-28-pc-ui-refactor 提供界面测试自动化能力，解决当前**唯一前端门禁只是 `npm run build`**、验收完全靠人工目视的缺口。用 Playwright 承担两件事：

1. **视觉回归**：把「四档宽度（1280/1440/1920/2560）× 明暗双模」下的版式基线固化为截图，任何非预期版式漂移（溢出、错位、token 回归、响应式残迹复燃）被自动 diff 拦下。
2. **冒烟 E2E**：覆盖关键路径（登录 → 项目列表 → 项目详情步骤流 → 预览分栏 → 问答/图库关键交互）的功能冒烟，守住重构期间的功能零回归。

用户价值：把 AC2/AC3/AC4/AC5 的验收从「人眼看」变成「机器判」，重构后续批 3 及后续改动有可回归网；发布前可一键跑出「版式 + 功能」双清单。

## Background / 已确认事实（代码证据）

- 前端**零测试基建**：`frontend/package.json` 的 `test` 脚本是 `vite build` 别名（`package.json:9`），无 `tests/` 目录、无 `*.spec.js`、无 vitest/playwright 依赖与配置；`frontend/` 下无测试相关文件。
- **唯一前端门禁 = `npm run build`**（后端 `mvn test` 510 个 Java 用例不覆盖前端）。
- 鉴权模型（`frontend/src/store/user.js:6-7,29-37`）：登录态存 `localStorage` 的 `sparkora_token` / `sparkora_user`；`api/http.js:13-16` 请求拦截器带 `Bearer`，`:19-21` 响应拦截器 401 恒定清 token 跳登录。⇒ E2E 可用「**预置 localStorage + 网络拦截**」绕过登录，无需真实账号/密码。
- **无 CI**：`.github/workflows` 不存在，`origin` 为 GitHub 私有仓库（未配置远端 default 分支）。⇒ 首版可只在本地跑；CI 接入属可选后续。
- 后端依赖 PostgreSQL + `.env`（AI/图床/微信凭据）。**跑前端 E2E 不应依赖真实后端数据**（AI 调用慢且不稳定）⇒ 用 Playwright `page.route` 拦截 `/api/**` 返回固定 fixture。
- Vite dev server 代理 `/api` → 后端 `:5661`（`vite.config.js`；`frontend` dev 端口 5173）。E2E 跑前端 dev server 即可，`/api` 全拦截不进后端。
- 现存页面（批 3 待迁移前）：`LoginView / ProjectList / ProjectEdit / StepBrief|StepVersions|StepPreview|StepPublish / ImageLibrary / QaChat / CarLibrary|CarDetail|CarSync / KbLibrary / StyleLibrary / KnowledgeCenter / knowledge/{CarKnowledgePanel,NewsKnowledgePanel} / SettingsView`。
- 项目详情步骤页在 `ProjectLayout` 的 `<router-view>` 内；步骤 rail 左侧竖排；预览页双 pane 可拖拽分栏（`StepPreview.vue`，`localStorage['sparkora.previewSplit']`）。

## Requirements

### R1 Playwright 基建落地
- 在 `frontend/` 新增 `@playwright/test` 依赖 + `playwright.config.js`：
  - `baseURL` 指向 vite dev server（5173），`webServer` 自动起/复用 dev server。
  - 浏览器固定 Chromium（跨平台稳定）；viewport 用参数化而非全局固定。
  - 脚本：`test`（全量）、`test:visual`（视觉回归）、`test:smoke`（冒烟）。
- 测试文件按域放 `frontend/tests/`（如 `tests/visual/*.spec.js`、`tests/smoke/*.spec.js`），与 src 隔离。

### R2 API 全拦截 fixture 层（确定性）
- 用 `page.route('**/api/**')` 拦截所有后端调用，返回**固定 fixture**（写死 JSON），确保：
  - 截图可重复（无真实时间戳/随机数据抖动）。
  - 不依赖后端在跑、AI 可用、图床可用。
- 提供共享 fixture 模块 `tests/fixtures/`（项目列表、版本、预览 HTML、图库、问答会话等最小集），按 URL 路由匹配返回。
- 登录态：`beforeEach` 预置 `localStorage` 的 `sparkora_token`/`sparkora_user`（或拦截 `/api/auth/me`），跳过真实登录表单交互（登录页本身单列一条真实表单冒烟）。

### R3 视觉回归（版式基线）
- 覆盖页面（PC-only 工作台骨架 + 三种版式原型 + 关键页）：登录页、ProjectList（表格）、ImageLibrary（网格）、StepPreview（分栏/画布）、StepVersions（网格）、StepPublish（面板）、QaChat（主从）、SettingsView。
- **宽度矩阵**：至少 1280 与 2560 两档（端点宽度，覆盖 AC2 最窄与最宽；1920/1440 可选补）。每页 × 明暗双模 ⇒ 每页 4 张基线。
- 截图前必须**等稳**：等 `networkidle` + 关键元素可见 + 一帧稳定（禁动画/`caret:'hide'`），避免时序抖动。
- 基线图提交仓库（`tests/__screenshots__` 或 Playwright 默认 `*-snapshots`），CI/`--update-snapshots` 更新。
- 用 `toHaveScreenshot` 像素比对；敏感差异容忍度按需设置 `maxDiffPixelRatio`。

### R4 冒烟 E2E（功能零回归）
- 路径覆盖：
  - 登录页表单提交（真实交互，拦截 `/api/auth/login`）。
  - ProjectList：渲染行、筛选/搜索触发 `/api` 请求、切主题、创建入口。
  - 项目详情步骤流：进入步骤页、步骤 rail 可达性、预览页渲染（`.wenyan-preview` 契约类名存在性断言）、分栏拖拽交互后宽度变化。
  - QaChat：发送消息（Enter + IME 安全：断言 `isComposing` 不误发——可模拟 compositionstart）、分栏折叠/展开。
  - ImageLibrary：进入、语义开关、chip 筛选 URL 同步。
- 断言**契约类名/结构**（如 `.wenyan-preview`、`sparkora-img-missing`、`usePageHeader` 上下文条元素）作为锚点，避免纯视觉断言脆弱。

### R5 无障碍/健壮性底线
- 全部测试 headless 可跑（`CI` env 检测）；本机可 `headed` 调试。
- 视觉回归在有动画/hover 差异处统一禁用动画、隐藏光标。
- 不引入对生产后端/AI/图床的真实调用（性能与稳定性）；所有 AI/发布/生成在测试里是拦截的假响应。

## Out of Scope

- 接入 CI（GitHub Actions 等）——本地可跑为 MVP；CI 化作为可选后续（无现成 workflow 可挂靠）。
- 引入 Vitest / 组件单测（本任务只做 Playwright；纯逻辑单测属另一任务）。
- 真实后端联调 E2E（打真 DB、真 AI、真微信）——太慢/不稳定/有副作用，测试用全拦截。
- 后端测试改造（Java 侧已有 `mvn test` 510 用例）。
- 性能压测、可访问性审计（axe 等）——不做（可后续）。
- 批 3 UI 迁移本身的实现（本任务只为它/后续提供网，迁移归 pc-ui 批 3）。

## Acceptance Criteria

- **AC1 基建**：`npm run test` 在本地全绿；`playwright.config.js` + `tests/` 就位；`package.json` 脚本 `test/test:visual/test:smoke` 可用。
- **AC2 视觉回归管道端到端可跑**：已迁移页（ProjectList/ImageLibrary/StepPreview/QaChat 中至少 3 页）× {1280,2560} × {明,暗} 截图管道跑通、基线已提交且二跑幂等；人为改一处版式（如改 `main.css` 一个 token）后 `test:visual` **报红**（证明它在守）。**正式全量基线在批 3 后重录**（见 Deferred）。
- **AC3 冒烟**：登录表单、项目列表、项目步骤流、预览 `.wenyan-preview` 契约、QaChat Enter/IME、分栏交互等用例全绿；断言基于契约类名/结构。
- **AC4 隔离**：测试跑在**后端未启动**的机器上也全绿（证明 `/api` 全拦截、`webServer` 自起前端）；测试不触碰真实 DB/AI/图床/微信。
- **AC5 稳定性**：连跑 3 次 `npm run test` 结果一致（无时序 flake）；无 `console` 未捕获异常噪声掩盖失败。
- **AC6 文档**：README/任务内注明「如何更新基线」（`--update-snapshots`）、依赖安装（`npx playwright install`）、运行方式。

## Key Decisions

- **方案 = A：Playwright 视觉回归 + 冒烟 E2E**（用户 10-01 裁定；备选 B=Vitest 组件级、C=仅功能 E2E 均未采纳）。
- **API 全拦截**而非真实后端：确定性 + 无副作用 + 快（AI/发布极慢）。
- **宽度取端点档 {1280, 2560}**：覆盖 AC2 最窄/最宽极端；中间档可选。
- **基线时机 = 批 3 之后建正式基线**（用户 10-01 裁定）：批 3 会大改车型域/知识域/Settings/Login 的版式，现在录的全量基线必然全量失效重录。故本任务交付「基建 + 拦截层 + 冒烟 E2E + 视觉回归跑通」，**正式全量基线推迟到 pc-ui 批 3 落地后一次性重录**。

## Deferred（明确推迟，非阻塞）

- **正式全量视觉基线**：批 3 完成后一次性建立（`npx playwright test --update-snapshots`）。本任务只保证视觉回归管道端到端可跑通 + 用少量页面证明「改坏了会报红」。
- **批 3 新页面的视觉覆盖**：CarLibrary/CarDetail/CarSync/KnowledgeCenter/knowledge/*/KbLibrary/StyleLibrary/Settings/Login 的截图在批 3 迁移完成后再纳入基线（本任务仅先覆盖已迁移页 ProjectList/ImageLibrary/StepPreview/StepVersions/StepPublish/QaChat）。

## Risks

- **视觉回归 flake**：动画/字体/时序。缓解：禁动画 + 等稳 + 固定 viewport + Chromium only。
- **批 3 耦合**：视觉基线与 UI 迭代强相关，若现在锁定会频繁失效（同 Q1）。
- **浏览器安装**：`npx playwright install` 需下载 Chromium（网络/磁盘）；CI 化时才需固化。
- **E2E 脆弱**：断言过度依赖 DOM 结构。缓解：优先断言契约类名与用户可见文本，不写脆弱 nth-child。