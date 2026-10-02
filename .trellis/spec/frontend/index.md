# Frontend Development Guidelines

> Best practices for frontend development in this project.

---

## Overview

- Vue 3 (`<script setup>`) + Vite 5 + Element Plus 2.8 + Pinia + vue-router。
- Element Plus 组件由 `unplugin-auto-import` / `unplugin-vue-components` **自动引入**（模板里直接用 `<el-*>`）；**图标必须显式 import**（`@element-plus/icons-vue`）。
- **PC-only 桌面生产力工作台**（最小宽度 1280；低于时 `DesktopGuard` 不透明遮罩提示，不降级）。不引 Vant 等额外移动端框架。

---

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| [API Layer](#api-layer) | 请求封装与 `R<T>` 拆包约定 | **已填** |
| [Page Conventions](#page-conventions) | 页面结构/CSS 变量/响应式 | **已填** |
| [State & Tabs](#state--tabs) | 状态保持与懒挂载 | **已填** |
| [State & Tabs](#state--tabs) | 大组件拆分落位（组件子目录 / composables / utils） | **已填**（09-27-split-monoliths） |
| [Tests](#tests) | Playwright 视觉回归/冒烟基建、API 拦截层与基线更新 | **已填**（10-01-e2e-testing） |

---

## API Layer

### Convention: 所有后端调用走 `src/api/index.js` 具名导出，不直调 `http`

**What**: 页面通过 `import { carApi, newsApi, kbApi, ... } from '../api'` 调用；禁止在 `.vue` 里 `import http` 直调。

**Why**: 集中契约（路径/参数/超时）、便于全局替换与核对后端 Controller；历史 `KbLibrary.vue` 直调 `http` 已在 C3 规范为 `kbApi`。

**Example**:
```js
// src/api/index.js —— 按领域分对象，风格对齐 carApi
export const kbApi = {
  list: () => http.get('/kb/docs'),
  get: (id) => http.get(`/kb/docs/${id}`),
  create: (data) => http.post('/kb/docs', data),
  update: (id, data) => http.put(`/kb/docs/${id}`, data),
  remove: (id) => http.delete(`/kb/docs/${id}`),
  rebuild: (id) => http.post(`/kb/docs/${id}/rebuild`),
  // 耗时接口单独放宽超时：..., { timeout: 120000 }
}
```

**Related**: `frontend/src/api/http.js`。

### Gotcha: `http.js` 响应拦截器已 `return resp.data`，调用方拿到的是后端 `R<T>`

`axios` 实例响应拦截返回的是 `resp.data`（即后端 `R<T>` 对象），因此调用方读的是 `res.code` / `res.msg` / `res.data`，**不是** `res.data.data`：

```js
const res = await carApi.list()
if (res.code === 0) rows.value = res.data          // 分页接口则是 res.data.rows / res.data.total
```

分页接口（后端 `PageResult<T>`）解包：`res.data.rows` / `res.data.total` / `res.data.page` / `res.data.size`。

---

### Convention: 「建议 → 用户批准」链路的写入必须双写（渲染 + 登记）

系统产出的**建议**（配图候选等）在用户显式批准前**零副作用**；批准后若「渲染层」与「登记字段」是两套机制，必须**同时写**，只写一处会导致隐性不一致：

```js
// 采用一张建议图(09-15 article-auto-illustrate)：两处都写
// 注意候选是 ImageSearchHit(record)：id 字段名是 imageId，不是 id！
const imageId = img.imageId
const url = img.url                                   // 必须用图床原图 URL(thumbUrl 是 webp 派生，微信素材接口不支持)
await imageApi.addBodyImage(projectId.value, imageId) // ① 登记(计数+防误删；可失败，先做)
editorRef.value?.insertMdAtAnchor?.(group.headingPath, `\n![](${url})\n`)  // ② 真正渲染
```

- 只写 markdown → 发布页计数错、图片可能被误删；只写登记字段 → 根本不渲染（该字段不参与渲染，见 `docs/spec/image.md` 已知债务）。
- **先登记后插入，且插入失败要回滚登记**：登记是可失败的网络/鉴权写；若先插正文再登记失败，会留下「正文有图、版本插图关联行（原 `body_image_ids`，P1-⑦ 后为 `sparkora_article_version_image`）没有」的隐性不一致。反之插入失败时回滚登记，避免「计数虚高但正文无图」。
- 回滚前判断该图是否**本次新登记**（对照 `imgSnapshot.bodyImageIds`），已登记过的图不得因插入失败被移除（会误删用户既有插图）。
- **接口 DTO 是 record 时字段名可能与实体不同**：建议/检索类候选走 `ImageSearchHit`（`imageId`/`url`/`thumbUrl`），图库实体走 `id`/`url`。混用会静默传 `undefined`（如 `POST /images/undefined/body` → 400「参数 imageId 格式不正确」），且**编译与构建都不会报错**——对照 `frontend/src/api/index.js` 的接口注释确认字段名。
- **不得提供任何自动写入路径**：不设自动插入开关；生成建议（检索）可自动，写入必须用户点。服务侧也不得注入可写字段的组件。

**Related**: `frontend/src/views/project/StepPreview.vue`（智能建议 tab）、`frontend/src/components/MarkdownEditor.vue`（`insertMdAtAnchor`）。

### Convention: 按标题定位插入时「找不到必须退回光标处」

`MarkdownEditor.insertMdAtAnchor(headingPath, text)` 按标题文本**首次出现**定位插入点；**标题不存在/为空必须退回 `insertMd`（光标处）而非静默丢弃**——丢掉用户内容比插错位置更糟。同名标题接受「插到第一个同名标题后」的近似行为（已知限制，写入 spec）。

返回值为 `boolean`：`true`=已插入（按标题定位 **或** 退回光标处），`false`=编辑器未就绪、**未插入任何内容**——调用方据此回滚已登记的版本插图关联行。`insertAtCursor` 同样返回 `boolean`（原先 `return` 无值，会被 `!== false` 误判为成功）。

## Page Conventions

### Convention: 页面骨架统一（AppShell + usePageHeader）

```vue
<template>
  <div class="page">
    <!-- 三态：loading(骨架) / error(重试) / empty(el-empty) -->
    <!-- 页内工具带 / 主体内容 … -->
  </div>
</template>
<script setup>
import { onBeforeRouteLeave } from 'vue-router'
import { usePageHeader } from '../composables/usePageHeader'
const header = usePageHeader()
const syncHeader = () => {
  if (!header) return
  header.crumbs = [{ label: '模块' }, { label: '当前页' }]          // 必须是对象数组,不要传字符串
  header.actions = [{ key: 'new', label: '主操作', type: 'primary', onClick: handler }]
}
syncHeader()
watch(userRoleReactive, syncHeader)
onBeforeRouteLeave(() => { if (header) { header.crumbs = []; header.actions = [] } })
</script>
```

- 全局外壳 `AppShell`（`frontend/src/layouts/AppShell.vue`）统一提供：左侧 rail 模块导航 + 顶部上下文条（折叠钮 / 面包屑 / 主操作 actions / 主题切换）+ 用户区；页面**不再自绘页头**（`TopBar` / `page-header` 已退役）。登录路由不套壳。
- 页面顶部信息经 `usePageHeader()` 注入的 reactive `{ crumbs, actions }` 写入上下文条：`crumbs` 为 `[{ label }]` 对象数组（`AppShell` 按 `c.label` 渲染，**传字符串会渲染 `undefined`**）；`actions` 为 `{ key, label, type, onClick, disabled?, icon? }[]`（`icon` 传图标组件对象）。离开页面用 `onBeforeRouteLeave` 清空，避免污染相邻页面。**必须整体持有 `usePageHeader()` 返回的 reactive 壳后改其属性**（`const hdr = usePageHeader(); hdr.crumbs = [...]`）——解构 `{ crumbs, actions }` 拿到的是普通数组，后续 `crumbs.value = ...` 写不进壳，面包屑/动作会静默不渲染（09-28 pc-ui 批 1 试点 → 批 2 首轮回归的坑，`StepBrief` 已修）。
- **ProjectLayout 持有面包屑（09-28 实测）**：项目步骤页（`StepBrief`/`StepVersions`/`StepPublish`/`StepPreview`）部署在 `ProjectLayout` 的 `<router-view>` 内，其 `watch([id, route.name, project, loadError], syncHeader, {immediate:true})` 每次路由切换都会把 `header.crumbs` 重写为 `项目/#id/<步骤名>`（Vue pre-flush 按 uid 排序，`ProjectLayout` watcher 晚于子页 `setup()` 执行，覆盖子页赋值）。因此**子步骤页写 `crumbs` 是死代码**——只写 `header.actions` 即可，面包屑统一由 `ProjectLayout` 呈现；若将来要子页接管，需移除 `ProjectLayout` 的 crumbs 写入。
- 三态齐全是硬性要求：`v-if="loading"` 骨架、`v-else-if="error"` 错误+重试、空态 `el-empty`。
- 样式使用 `frontend/src/assets/main.css` 的 CSS 变量（`--n-*` 色阶、`--brand` 语义别名、`--control-h-*` 档位、`--fs-*`、`--lh-*`、`--radius-*`、`--sp-*`、`--focus-ring`），不要硬编码颜色/字号/圆角/间距。
- **PC-only 密度约定**：控件高度统一走 `--control-h-sm/md/lg` = 28/32/40px 档位，不引入移动端 44px 触控目标；交互态必须提供 `:focus-visible` 可见焦点环（`--focus-ring`），hover 显隐的操作在无 hover 环境靠 `:focus-within` 兜底。
- **不可破坏类名契约**：`.wenyan-preview`（`utils/wenyanRender.js` 的 `PREVIEW_SELECTOR` 把 wenyan 主题 CSS 从 `#wenyan` 重写为该类注入 `document.head`，`PreviewPane` 渲染容器持该类——改名会**静默废掉全部 15 套主题**）与 `sparkora-img-missing`（`utils/pendingImageStore.js` 产出，`PreviewPane` 以 `:deep()` 消费失效占位样式）。凡涉及这两个类名的改动，改前改后必须 `grep` 确认两端仍匹配，并实测 1 内置 + 1 社区主题渲染。
- **步骤页主体容器 `.step-body`（09-28 pc-ui 批 2）**：项目流步骤页（`StepBrief`/`StepVersions`/`StepPublish`）用 `.page.step-body` 作为主体——该容器自身撑满剩余高度并滚动，为 ctxbar 页级动作 + 全幅网格/面板提供工作台主从布局；`StepPreview` 例外（保留 `.preview-page`，内部有独立双 pane flex 布局）。页面不再自绘「去卡片/页头/动作行」，主操作统一进上下文条 `header.actions`。
- **主从分栏用共用 composable `useSplitPane`（09-28 pc-ui 批 2）**：分栏列宽状态机（拖拽/键盘调宽/折叠 + localStorage 持久化）抽到 `src/composables/useSplitPane.js`（先例 `QaChat.vue` 会话栏），不要在各页内联复制。localStorage 键约定 `sparkora.<域名>SideWidth` / `sparkora.<域名>SideCollapsed`；读写在隐私/禁用模式下可能抛 `SecurityError`，用 `try/catch` 兜底；`pointercancel` 与 `pointerup` 都要结束拖拽清理监听。
- **Enter 提交必须 IME 安全（09-28 pc-ui 批 2）**：`@keyup.enter` 提交表单前判 `if (e.isComposing || e.keyCode === 229) return`——中文输入法组字过程中回车是「选词」而非「提交」，不判会在拼音上屏瞬间误触发创建/发送（与 `StepBrief` 的 `ClarifyForm`、`ProjectEdit.onEnterSubmit`、`QaChat` 发送同一口径）。**非幂等提交入口必须有同步重入守卫**（`if (loading.value) return` 且置 loading 必须早于第一个 `await`）——`validate()` 是异步的，先 await 校验再置 loading 会让双击/连按 Enter 并发执行两次创建（09-28 实测 `ProjectEdit` 重复建项目）。
- **Enter 提交的绑定必须带按键修饰符（09-30 fix-keydown-enter-submit 教训）**：需要 Enter 提交的输入，一律写 `@keydown.enter="handler"` 或 `@keyup.enter="handler"`，**禁止写裸 `@keydown="handler"` 再在 handler 里自判**。裸 `@keydown` 会在**任意按键**都触发 handler：若 handler 无条件 `preventDefault()`/提交，则数字键被吞（打不出数字）、`Ctrl+V` 被拦截（粘贴失效）、任意键误触发创建/锁定。`ProjectEdit` 三处单行框与 `ClarifyForm` 两处自由输入曾因漏写 `.enter` 回归（`537498b` 引入），现象即「创作主题输入数字直接触发简报生成、无法粘贴」。选 `keydown` 还是 `keyup`：Enter 的 keydown 需 `preventDefault` 阻止单行框隐式表单提交时用 `@keydown.enter`（如 `QaChat` 发送）；否则 `@keyup.enter` 亦可。`StepPreview`/`QaChat` 分栏 `@keydown`（`onSplitKey`/`onKey`）是方向键语义，不属本条。
- **Element Plus 焦点可视化分两类，不可一刀切排除（10-01-fix-input-focus-brief-title 教训）**：EP 的 `el-input` 单行框焦点描边画在**外层 wrapper**（`.el-input__wrapper.is-focus`，inner 透明/padding:0），而 `el-textarea` 的焦点描边**就画在 inner 自身**（`.el-textarea__inner:focus`）。`main.css` 在 EP 样式后加载，故若用 `.el-input__inner:focus-visible, .el-textarea__inner:focus-visible { box-shadow:none }` 排除全局 `--focus-ring` 误画，会**同特异性（0,2,0）覆盖 EP 的 textarea 焦点环，使多行框彻底无焦点可视化**（`textarea:focus-visible` 同理命中 inner 并顺带清掉圆角）。单行框只需排除 `.el-input__inner`（其焦点交给 wrapper）；textarea **不得排除**——全局 `:focus-visible`（0,1,0）本就低于 EP 的 `.el-textarea__inner:focus`（0,2,0），textarea 从来不受误画影响。改这类焦点规则必须用真实 EP 组件在无头浏览器实测单行/多行/select 三种控件（`:focus` 与 `:focus-visible` 的匹配、box-shadow、border-radius）。

---

## State & Tabs

### Convention: 大组件拆分落位（09-27-split-monoliths）

巨石 view 拆分的既定落位（先例：`StepPreview.vue` 994→460、`ImageLibrary.vue` 908→354）：

- **子组件**：按域放 `src/components/<domain>/`（`preview/`、`image/`），域内单一用途组件不再堆扁平目录；跨域共用组件（`AiImageDrawer`/`MarkdownEditor`）保持 `src/components/` 扁平原位。
- **composable**：放 `src/composables/`（本任务新建目录）。命名 `use<Domain><Concern>.js`，一个文件可导出多个相关 composable（如 `useImageLibraryOps.js` 导出 `useImageSourceTrace`/`useImageUpload`/`useImageCardOps`/`useImageTagDialogs`）。
- **契约**：composable 接收 ref/回调（`projectId` ref、`onFilterChange`、`getSnapshot` 等），返回状态与动作；不吞宿主跨切状态（`contentMd`/`editorRef`/`saveState`/筛选态仍归宿主）。
- **纯派生函数**（无响应式依赖）抽到 `src/utils/`（先例 `imageDisplay.js`/`imageRegenerate.js`），供多组件复用且可单测。
- **子组件与父通信**：父持有状态的用 `props` + `emits`（对象式 props、数组式 emits）；父持有的 ref 传入子组件时经具名 `update:*` 事件回写（如 `busy` → `@update:busy`），不得在子组件内直接改 props。
- 拆出的每个文件 ≤ ~400 行、宿主 view ≤ ~450 行为目标区间。

**Related**: `frontend/src/components/preview/*`、`frontend/src/components/image/*`、`frontend/src/composables/*`。

### Pattern: Tab 面板用 `v-if` 懒挂载实现「首访加载 + 切回保留状态」

**Problem**: `el-tabs` 默认切换不销毁面板，但首次进入即请求所有 Tab 会造成无谓加载。

**Solution**: 记录已访问 Tab 集合，用 `v-if` 控制面板挂载——首次切到某 Tab 才挂载并请求，之后切走再切回组件不销毁、状态保留。

```vue
<el-tabs v-model="activeTab" @tab-change="onTabChange">
  <el-tab-pane label="车型" name="car">
    <CarKnowledgePanel v-if="loadedTabs.car" />
  </el-tab-pane>
  <el-tab-pane label="新闻" name="news">
    <NewsKnowledgePanel v-if="loadedTabs.news" />
  </el-tab-pane>
</el-tabs>
```

**Why**: 避免一次性请求全部数据，同时不丢失用户已浏览状态。

### Convention: 同步类异步任务前端范式

`createJob` → 拿 `jobId` → `setInterval(2000)` 轮询 `getJob` → 终态（非 `RUNNING`）`stopPoll()` + 刷新列表；`onBeforeUnmount(stopPoll)` 清理定时器。参照 `CarSync.vue` / `NewsKnowledgePanel.vue`。

### Convention: 防抖定时器必须 `onBeforeUnmount` 清理

搜索框的 `setTimeout` 防抖（`kwTimer` 等）在组件卸载时若不清，回调会在离开页面后仍触发 `load()`（无谓请求/控制台报错）。**每个防抖 timer 都要在 `onBeforeUnmount` 里 `clearTimeout`**（09-13 先例：`ImageLibrary.vue` 的 `kwTimer` / `refKwTimer`）。

```js
let kwTimer = null
const onKeywordInput = () => { clearTimeout(kwTimer); kwTimer = setTimeout(load, 300) }
onBeforeUnmount(() => { clearTimeout(kwTimer) })
```

### Convention: 客户端「取消」≠ 服务端停止：必须如实告知 + 取消判据先于超时判据

长耗时非幂等请求（生图、发布、批量重建…）给「取消」入口时，`AbortController` 只能**停止客户端等待**——后端请求线程仍在跑，下游可能照常处理完成并**落库**。三条硬性要求（09-27 先例：`AiImageDrawer.vue` 的生成取消，与后端 [`external-cli-integration.md`](../backend/external-cli-integration.md) 的非幂等条款同源）：

1. **提示必须如实告知**：「已取消本次请求（已提交给服务端的任务可能仍会完成并入库）」。谎报「已取消/无产出」= 让用户以为白等，且重试即重复数据。
2. **取消后不得弹成功提示**：即使 `await` 拿到了响应，只要 `signal.aborted` 为真就走取消分支（否则会弹「已进图库」，用户无法分辨图到底出没出来）。
3. **判据顺序：取消 → 超时 → 传输层**。超时判据常含「message 含 `timeout`」这类宽匹配，排在取消前面会把取消显示成「请重试」——等于诱导用户重复提交。

```js
const isCanceled = (e, ctl) => !!ctl?.signal?.aborted || e?.code === 'ERR_CANCELED' || e?.name === 'CanceledError'
const ctl = new AbortController()
try {
  const res = await reqFn(ctl.signal)      // 收「工厂」而非已发起的 promise：signal 必须在发请求前拿到
  if (ctl.signal.aborted) return
  handle(res)
} catch (e) {
  if (isCanceled(e, ctl)) ElMessage.info('已取消本次请求（已提交给服务端的任务可能仍会完成并入库）')
  else reportLayeredError(e)               // 后端 msg / 超时 / 传输层，逐类给可执行建议
} finally { abortCtl = null; busy.value = false }   // 取消后必须复位，否则按钮永久 loading
```

- 入口补 `if (busy.value) return` 防重入（非幂等动作不靠 UI 禁用单点防重）；`onBeforeUnmount` 里 `abortCtl?.abort()` 断掉在途请求。
- 取消入口**默认始终可见**（单次也可能卡满超时），张数少/预计快完成时用次要样式，避免诱导取消。
- `signal` 在 `api/index.js` 里必须是**末尾可选形参**并透传 axios config——不传=行为不变，存量调用方零改动。
- **`http.js` 的全局错误拦截器会把上面三条全打回原形**：axios 的取消也会走响应拦截器的 reject 分支，
  弹一条**红色英文** `canceled`；超时/断网则弹 `timeout of 300000ms exceeded` / `Network Error`。
  结果是「两个 toast + 取消被显示成错误」，正是后端 `error-handling.md`「禁止把框架内部串透给用户」在前端侧的同一坑。
  ⇒ **要做自定义错误分层的请求必须传 `skipGlobalErrorToast: true` 关掉全局提示**（401 处理不受影响，仍无条件跳登录）。
  参照 `http.js` 拦截器 + `api/index.js` 的 `imageApi.generateText/generateFromImageUpload/regenerate`。
- 等待提示**不写预估耗时**（各下游差异大，编造数字比不给更糟）；要写就写「可随时取消」。
- **回显类信息不得与图不同源**：结果区顶部回显「本次按 X（实际 Y）· N 张」若取提交时快照，而失败/取消时结果区仍渲染**上一批**候选，
  就会「回显是新的、图是旧的」；须在非成功路径回滚快照（`settled` 标记），或发起时清空候选。二者选一，别留半新半旧。

### Convention: 语义档位与底层取值不同值时，档位要独立记忆、不可反查

用户选的是**语义档**（比例 `3:4` / `9:16`、密度「高清」/「标准」）而下游契约收**底层取值**（像素 `1024x1536`、比特率）时，若多个档位映射到同一取值（如 `3:4` 与 `9:16` 同为 `1024x1536`），**禁止由底层取值反查档位**——必然歧义，用户在两档间切换时选中态乱跳。做法：独立 ref 记档位（`genRatio`），底层取值由其 `computed` 派生（`genSize`），映射表放 `src/utils/` 作单一真源；非精确档在控件上写出实际取值，不做后处理。参照 `utils/imageGenRatio.js` / `AiImageDrawer.vue`。

### Convention: 缩略图按真实宽高展示，缺失回落而非破版

有 `width`/`height` 的实体，网格缩略图应内联 `aspect-ratio: w / h` + `fit="contain"` + 纸色底，竖图不裁成横图；两列**缺失/为 0/非法时必须回落固定比例**（如 `4/3`）——不回落会得到 `0 / 0`（被忽略）或 `NaN`（整条声明作废）导致破版。网格用 `align-items: start` + 单元格限高保持横竖混排稳定，**不引 masonry**。本地未入库图片（如粘贴的参考图）用自身 `blob:` URL 探测宽高，**复用同一条 URL**（另建 = 多一条待 revoke 的 URL）。参照 `AiImageDrawer.vue` 的 `ratioStyleOf` / `probeRefSize`。

### Convention: 抽屉内多 Tab 的表单状态必须按 Tab 拆分

`el-tabs` 切换默认不销毁面板，两个 Tab **共用同一个 `ref`** 会导致内容互相污染（在文生图输入，切到图生图看到同一段文字）。每个 Tab 的表单字段用**独立 ref**（如 `aiPromptText` / `aiPromptImg`），提交各自读取。

### Convention: 弹窗选数据源不要复用主列表的筛选/分页态

主列表（带服务端筛选+分页）被「选择弹窗」复用时，会退化成「只能看当前第一页 + 继承主列表筛选」的隐性 bug。弹窗应持有**独立数据源 + 独立搜索/分页**（09-13 先例：参考图选择弹窗 `refImages`/`refKeyword`/`refPage` 独立调接口 + 防抖搜索 + `el-pagination`）。

### Convention: 路由 query 驱动的筛选必须双向同步 URL

筛选条件从 `route.query` 预置（如从新闻页点标签跳 `/images?tag=主题/销量`）时，**清除筛选也要同步清 URL**（`router.replace`），否则 `route.query` 残留旧值，用户再次从外部点同一筛选时 vue-router 判定重复导航、`watch(route.query)` 不触发，表现为「点了没反应」（09-15 先例：`ImageLibrary.vue` 的 `syncRouteTag()`）。

```js
// 筛选态变更（选/清单个 chip、全清）→ 回写 URL
const syncRouteTag = () => router.replace({ query: { ...route.query, tag: tagFilter.value.length ? tagFilter.value.join(',') : undefined } })
// watch 内先比对当前筛选态，一致则短路，避免自身回流触发重复 load
watch(() => route.query.tag, (v) => { const next = parseTag(v); if (sameFilter(next, tagFilter.value)) return; applyFilterFromRoute() })
```

- 从 query 预置时要**完全镜像**（含清空分支）：外部 `tag=` 为空应清空筛选，而不是保留旧值。
- 09-13 遗留教训：`tagFilter` 从 `''` 改 `[]` 时，`hasFilter`/`clearAllFilters`/`activeChips`/`locateInList`/`load()` 全部波及处需一次性核对。

### Convention: 外部相对 URL 解析

后端返回的图片/链接可能是相对路径（如新闻 `imageUrl`/`url`）。前端统一用一个 `resolveUrl`：`/^https?:\/\//i` 开头原样返回，否则拼接源站（新闻 = `https://www.byd.com`）。

### Convention: 后端 TEXT 列存 JSON 的字段，前端消费必须兼容「字符串 / 数组」两态

后端 JSON 字段统一以 TEXT 列存**字符串**（`citations`、`image_refs`、`similarityReport` 等，见 database-guidelines「JSON 字段统一 TEXT 列」），但不同链路（或未来后端改为直接返回数组/DTO）可能给出数组。前端消费前必须归一：

```js
// QaChat.vue imgRefsOf / CitationList.vue localList 同款兼容写法
const listOf = (raw) => {
  if (Array.isArray(raw)) return raw
  if (typeof raw === 'string' && raw) { try { const v = JSON.parse(raw); return Array.isArray(v) ? v : [] } catch { return [] } }
  return []
}
```

- 兼容写法要**容错**（`JSON.parse` 失败返回空数组），不能抛异常打断渲染；历史行字段为 `NULL` → 归一为空数组 → `v-if` 不渲染（零回归）。
- 派生展示字段（如配图）用 `v-if="… && listOf(m).length"` 而非 `v-if="m.field"`（字符串 `"[]"` 与 `null` 都要正确判空）。
- 引用/建议类条目若是后端 record（`QaImageRef`/`ImageSearchHit`），**字段名以 record 为准**（`imageId` 非实体 `id`）——对照 `src/api/index.js` 注释确认；混用会静默传 `undefined`（09-15 P0 先例，编译与构建都不报错）。

### Convention: 图片预览必须用原图 URL，缩略图只用派生 URL

配图/图片列表的**预览大图**（`preview-src-list`）与**插入正文**必须用原图 `url`；`thumbUrl`（七牛 `imageView2`/webp 派生）只用于网格缩略展示。原因：webp 派生图微信素材接口不支持（40113 unsupported file type，2026-09-11 全站预览坏图先例）。

```vue
<el-image :src="img.thumbUrl || img.url" :preview-src-list="[img.url]" preview-teleported hide-on-click-modal />
```

### Convention: 上传类图片必须先补「文件名扩展名」，不能只验 MIME

后端 multipart 上传/参考图校验（`ImageService.readValidatedImage`）**按 `file.getOriginalFilename()` 的扩展名**判断白名单（png/jpg/jpeg/webp）——剪切板与拖拽得到的 `File` 经常没有扩展名（`image` / `blob`，甚至名字为空），前端若只按 `file.type`（MIME）放行，后端会以「仅支持 png/jpg/webp 格式图片」400 拒绝。

```js
// AiImageDrawer.setLocalRef（09-26）：无有效扩展名时按 MIME 补名后再预览/提交
const ext = extOfMime(file.type)            // image/png→png、image/jpeg→jpg、image/webp→webp
const named = /\.[a-z0-9]+$/i.test(file.name || '')
  ? file
  : new File([file], `reference.${ext || 'png'}`, { type: file.type || 'image/png' })
```

- MIME 校验保留（快速反馈），但**不可替代**扩展名补全——两者是不同层、不同失败模式。
- 大小上限（`IMAGE_MAX_UPLOAD_MB`，默认 10MB）也需前端前置校验，避免把大文件白传一遍。

**Related**: `frontend/src/components/AiImageDrawer.vue`、`docs/spec/image.md` §6。

### Convention: 会话缓存持有 ObjectURL 时，同一 File 必须全局只建一个 URL

模块级会话缓存（`imageRefCache`）为每个 `File` 创建 `blob:` 预览 URL 时，若「一个 File 出现在多个条目 / 同一条目的数组里多次」而各建各的 URL，删除/淘汰时只会 revoke 其中一个，其余成为**永久泄漏**（`blob:` 不会被 GC 回收）。规则：

- 建 URL 前先按 **File 引用**（而非下标/文件名）查已存在的 URL 复用——**跨条目 + 条目内数组**两个维度都要去重（09-26 img2img-multi-ref check 修复先例：`files[]` 内重复 File 曾被各建一个 URL）。
- revoke 前必须确认**没有任何其他条目仍引用该 File**（`otherEntryUsesFile`），否则会 revoke 掉别处仍在用的预览。
- 覆盖写（同 key 换 File）、`deleteEntry`、`evictIfNeeded`、`clear` 四条路径都要走同一套「按 File 引用计数/查重」逻辑，缺一会泄漏或误删。
- `onBeforeUnmount` 只负责 revoke **组件自身**创建的预览 URL；缓存持有的 URL 由缓存的删除/淘汰路径管，两者所有权分离。

```js
// 条目内 + 跨条目去重（先查已建 URL，命中即复用）
const assigned = new Map()   // File -> url（本轮条目内）
for (const f of info.files || []) {
  let url = assigned.get(f) ?? urlForFile(f)   // urlForFile 内部再扫全部条目
  assigned.set(f, url)
  previewUrls.push(url)
}
```

**Related**: `frontend/src/utils/imageRefCache.js`、`frontend/src/components/AiImageDrawer.vue`。

### Convention: 前端暂存待上传资源用「正文占位 token + 会话暂存区」，上传延迟到显式动作

**What**: 用户在前端产生、但**不应即时上传**的资源（09-27 先例：预览页剪贴板粘贴图，只在点「去发布」时才上传七牛），统一用**自定义 token 占位**表示，而不是把临时 URL（`blob:`/临时对象存储 URL）写进会落库的正文。

- 正文（会落库）写 `![](sparkora-img:<id>)`，`<id>` 仅含 `[A-Za-z0-9_-]`；资源本体放**模块级会话暂存区**（`utils/pendingImageStore.js`，内存 Map、**不持久化**，同 `imageRefCache` 约定）。**token 前缀字面量只允许存在一份**：`pendingImageStore.TOKEN_PREFIX`（`TOKEN_RE` 由它拼出），下游（如 `bodyImageRefs` 计数解析）一律 import，不各写一份。
- **预览投影**与**落库内容**分离：渲染产物的展示层把 token 的 `src` 换成 `blob:` URL（`projectBodyTokens`，渲染后按属性精确匹配）；markdown 输入侧与落库正文**始终保留 token**。绝不在 markdown 阶段替换成 blob URL（会污染落库/草稿）。
- 上传触发点收敛到**一个显式动作**（本例＝预览页「去发布」）；转存成功后把 token 精确替换为公网 `url`，再落库正文。
- **落库必须先于清理（09-27 P0 教训）**：`remove(id)`/`revokeObjectURL` 只能发生在「URL 版正文保存**成功**」之后。反过来（先清后存）会留下「图床已有对象 / 正文仍是 token / 内存条目已删」的窗口，窗口内一次整页重载就是永久静默丢失。保存失败 → 保留全部条目 + 中止跳转 + 可直接重试；`goPublish` 开头的 `isDirty → saveContent` 保证重试时补存 URL 版正文（`extractTokens` 转空后不再重复上传），不会「不保存正文就跳转」。
- **失效必须可见（09-27）**：暂存区不持久化 ⇒ token 一定会「有失效的一天」。解析不到条目时**不许留破图/留空**，渲染为可见占位块（`<span class="…-missing">中文处置提示</span>`，样式用 `:deep()` 定义以穿透 `v-html`），并在页面顶部挂警示条；同时在**每一个会产出对外产物的动作**（复制排版 / 去发布 / 发布）阻断，且**文案区分「待上传」与「已失效」**——失效场景点「去发布」也传不上去，笼统提示「先去发布」等于把用户引到无效操作。
- **token 替换必须带 id 边界断言**：`replaceToken` 用 `sparkora-img:<id>(?![A-Za-z0-9_-])`，否则 `i1` 会命中 `i12` 前缀，误改其他图的引用。
- **防呆双保险**：前端（复制/发布前 `hasToken` 拦截）+ 后端（组装前 `content.contains("sparkora-img:")` 中止），防刷新丢失暂存后带失效占位发布。**警示/阻断的取词口径要与阻断方一致**（都用 `extractTokens`），否则会出现「无警示却仍被阻断」。
- **跨项目隔离 + 释放**：暂存按 `projectId` 分组；项目切换时清旧项目条目并 revoke；`watch(projectId)` 的 oldId 在「卸载重挂载」路径为 `undefined`，须额外在 `onMounted` 调 `clearOthers(currentProjectId)` 兜底，否则旧项目 blob URL 会话级泄漏。
- **token 与渲染管线的兼容前提**：`@wenyan-md/core`(marked 15) 对非法 `src` 协议**原样保留**（实测 `![![](sparkora-img:x)` → `<img src="sparkora-img:x" alt="" title="">`），故 HTML 侧替换成立；若未来渲染器改为清洗未知协议，需回归此假设。

**Why**: 会落库的正文若写入 `blob:`（刷新即失效、跨设备不可移植）或临时 URL，会产生死链且难以检测；自定义 token 可被前端、后端、测试一致识别，替换点单一可审计。

**Related**: `frontend/src/utils/pendingImageStore.js`、`frontend/src/composables/usePendingImageFlush.js`、`frontend/src/components/MarkdownEditor.vue`、`docs/spec/preview.md` §4.1。

### Convention: 同步长耗时操作（非幂等）的等待提示必须与实耗相符 + 禁止重复触发

**What**：调用**非幂等**下游操作（发布到草稿箱、扣款、发消息、批量重建）时，等待中提示要写「**大致量级 + 明确不要重复触发**」，而不是随口写「约十几秒」。

- **等待提示必须 ≥ 实耗量级**：09-27 先例——`/publish` 实测 43s+，文案写「约十几秒」会让用户以为卡死，
  **刷新/重试**（自然排障动作）→ 而超时不等于失败，服务端可能已写入 → **每次重试一篇重复草稿**。
  现文案：`约 1 分钟…请勿刷新或重复点击(重复发布会产生重复草稿)`。
- **axios timeout 必须 ≥ 后端阈值**：`projectApi.publish` 的 timeout（300s）必须 ≥ 后端
  `WENYAN_MCP_PUBLISH_TIMEOUT_MS`（180s）。前端先放弃 = 浏览器断开但后端仍在写 = 同一类事故。
  改后端阈值时**必须联动** `frontend/src/api/index.js` 与 `frontend/nginx.conf.template`。
- **入口加防重入守卫**：`el-button :loading` 会隐式禁用，但「双击开出两个确认弹层」「Enter 快速确认」
  仍可能在 `publishing` 置位后二次进入。异步动作函数入口补一行 `if (busy.value) return`
  （先例 `StepPublish.doPublish`）。非幂等操作**永远不要**依赖 UI 禁用单点防重。

**Related**: `frontend/src/views/project/StepPublish.vue`、`frontend/src/api/index.js`、`.trellis/spec/backend/external-cli-integration.md`（后端侧同一约定的非幂等条款）、[docs/spec/publish.md](../../../docs/spec/publish.md) §1.1。

### Convention: 「数量」类展示以**解析正文/正文真源**为唯一口径，不用关联表也不用派生快照

**What**: 同一事实（本文「正文插图数」）在多个页面/组件出现时，只能有**一个计算函数**，其余地方 import 它；真值必须是「会被渲染/发布的那份数据」。

- 09-27 先例：预览工具栏与发布页摘要的插图数统一走 `utils/bodyImageRefs.js` 的 `countBodyImages(md)`（解析 markdown `![](target)`，按 target 去重）。**弃用**的两个口径：① 「图库快照 images 集合」当分母（**含封面**→封面被算成插图）；② 「版本-图片关联表 `bodyImageIds`」当分子（登记是 best-effort，手工编辑正文不摘登记→虚高）。
- **封面走独立字段（`cover_image_id`），天然不参与正文插图计数**；别为了「凑一个分母」把它拉进来。
- **不同状态分开呈现**：已就绪 / 待上传 / 已失效是三态。「待传 N」只算**还能传**的（暂存条目仍在），已失效的另由警示条呈现——把失效也算进「待传」会误导用户去点一个注定失败的动作。
- 配套约定：需要「写入真源 + 登记辅助表」时，**先写真源（渲染/正文）再 best-effort 登记**，登记失败不阻断、不回滚正文；辅助表只服务引用保护/查询，**不得升格为计数真源**。

**Why**: 三个口径各算各的，用户在预览页看到 3 张、发布页看到 2 张且封面混在里面，排障成本远高于写一个纯函数；纯函数还能被多处复用与单测。

**Related**: `frontend/src/utils/bodyImageRefs.js`、`frontend/src/views/project/StepPreview.vue`、`frontend/src/views/project/StepPublish.vue`、`docs/spec/image.md` §7.7。

---

## Tests

### Convention: Playwright 旁挂 `frontend/tests/`，spec 必须从 fixtures 导入（10-01-e2e-testing）

**What**: 前端自动化门禁 = `npm run build` + Playwright（`frontend/playwright.config.js` + `frontend/tests/{fixtures,smoke,visual}`）。测试**完全旁挂、产品代码零改动**（不碰 `src/`、不碰 `vite.config.js`）。

- **导入契约**：所有 spec 一律 `import { test, expect } from '../fixtures/index.js'`，**不要**从 `@playwright/test` 直连——扩展后的 `page` 上才装了「`/api` 全拦截 + 预置登录态」，直连会绕过 mock，页面 401 跳登录或打到真后端。
- **API 全拦截**：`installMocks` 用 `page.route('**/*')` 且**先判 `u.pathname.startsWith('/api/')`** 再分派；**禁止用 `**/api/**` glob**——vite dev 自身的 `/src/api/*.js` 模块请求同样匹配，被回 JSON 后浏览器 MIME 校验拒载 → 应用白屏。未命中的 `/api/*` 返回 `R.fail(404,'未 mock')`，让前端走自身空态而不是抛错。
- **登录态**：`authed()` 经 `addInitScript` 预置 `localStorage.sparkora_token` / `sparkora_user`（key 与 `store/user.js` 一致）；登录页用例单独 `localStorage.clear()` 冷启动走真实表单。
- **视觉确定性**：`applyTheme(page, theme)` 必须在 `goto` **之前**调用（主题由启动时读 `localStorage.sparkora_theme` 应用，goto 后改 localStorage 不生效）；截图前 `prepareStable`（禁动画/过渡/光标 + `networkidle` + `document.fonts.ready`）+ 契约锚点可见；宽度×明暗矩阵单一真源 `tests/fixtures/matrix.js`。
- **基线资产**：`tests/visual/*.spec.js-snapshots/*.png` **必须提交**；`test-results/`、`playwright-report/` 是运行产物（根 `.gitignore` 已忽略）。版式有意变更后用 `npx playwright test tests/visual --update-snapshots` 重录；**正式全量基线在 pc-ui 批 3 后一次性重录**（`10-01-e2e-testing` Deferred）。
- **断言锚点**：优先契约类名/结构——`.wenyan-preview`、`sparkora-img-missing`、`.page.step-body`、`aside.step-rail`、`.ctxbar .crumb` / `.ctxbar-right button`、`.bubble-row`、`.splitter`——不写脆弱 `nth-child`。
- **fixture 字段名必须对齐实体契约**：版本是 `versionLabel`（不是 `label`）、项目创建人是 `createdBy`（不是 `creator`）。字段写错时页面**静默渲染兜底值**（`—`/空），测试与截图都不报错，是最隐蔽的 fixture bug。
- **步骤页路由会被状态机前跳**：`ProjectLayout.loadProject` 在 `routeStepIndex < activeStepOf(status)` 时 `router.replace` 自动前跳（`VERSIONS_READY` 落在 `/versions` 会跳到 `/preview`）。要截/测非活跃步骤页，走用户真实路径——先落活跃页再点步骤 rail（`onMounted` 才触发前跳，客户端切换不会再跳）。

**Related**: `frontend/tests/`、`frontend/playwright.config.js`、`.trellis/tasks/10-01-e2e-testing/`（README 级用法见 `frontend/tests/README.md`）。

---

## Anti-patterns

### Don't: 直接使用数据库原始字段名渲染派生数据

`CarModelEntity.introImages` 是**图库 asset id 列表**（JSON），不是 URL。展示必须用后端填充的非持久化 `introImageUrls: string[]`（C1 契约）。任何把 `introImages` 当 URL 用的代码都是 bug。

### Don't: 在 `.vue` 里 `import http` 直调接口

见上「API Layer」。历史遗留的 `CarLibrary.vue` `http.post('/car/models/rebuild-all')`（未 import，ReferenceError 隐患）已在 09-12 kb-cleanup 修为 `carApi.rebuildAll()`；新增接口一律先补 `src/api/index.js` 具名导出。

---

## Common Mistakes

### Common Mistake: 忘记 `http.js` 已经拆包

**Symptom**: 读 `res.data.data` 得到 `undefined`。
**Cause**: 响应拦截器已 `return resp.data`，调用方拿到 `R<T>` 本体。
**Fix**: 读 `res.data`（R 的 data 字段）；分页再进一层 `res.data.rows`。

### Common Mistake: 动态组件 `:is` 用了自动引入的 Element Plus 组件

**Symptom**: `<component :is="'ElDrawer'">` / `:is="ElDrawer"` 在运行时渲染不出组件或报未注册；构建可能通过。
**Cause**: `unplugin-vue-components` 只改写**模板里的静态标签**（`<el-drawer>`），**不处理 `:is` 中的运行时值**——组件未进入作用域。
**Fix**: 需要按条件切换的 Element Plus 组件必须**显式 import**（与图标同理）：

```vue
<script setup>
import { ElDrawer } from 'element-plus'   // 动态 :is 用，不能依赖自动引入
const isInline = computed(() => props.mode === 'preview')
</script>
<template><component :is="isInline ? 'div' : ElDrawer" v-bind="wrapperAttrs">…</component></template>
```

**Prevention**: 凡把组件名写进 `<component :is>`（或作为 prop/变量传递组件）的，一律显式 import 并核对构建后是否真的渲染。

---

**Language**: All documentation should be written in **English**。本文件按仓库既有习惯使用中文正文说明。
