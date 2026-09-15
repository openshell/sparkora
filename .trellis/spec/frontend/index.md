# Frontend Development Guidelines

> Best practices for frontend development in this project.

---

## Overview

- Vue 3 (`<script setup>`) + Vite 5 + Element Plus 2.8 + Pinia + vue-router。
- Element Plus 组件由 `unplugin-auto-import` / `unplugin-vue-components` **自动引入**（模板里直接用 `<el-*>`）；**图标必须显式 import**（`@element-plus/icons-vue`）。
- 移动端优先响应式；不引 Vant 等额外移动端框架。

---

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| [API Layer](#api-layer) | 请求封装与 `R<T>` 拆包约定 | **已填** |
| [Page Conventions](#page-conventions) | 页面结构/CSS 变量/响应式 | **已填** |
| [State & Tabs](#state--tabs) | 状态保持与懒挂载 | **已填** |

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

## Page Conventions

### Convention: 页面骨架统一

```vue
<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header">
        <div>
          <span class="page-kicker">English Kicker</span>
          <h2>中文标题</h2>
        </div>
        <div class="actions">...</div>
      </div>
      <!-- 三态：loading(骨架) / error(重试) / empty(el-empty) -->
    </div>
  </div>
</template>
```

- 三态齐全是硬性要求：`v-if="loading"` 骨架、`v-else-if="error"` 错误+重试、空态 `el-empty`。
- 样式使用 `frontend/src/assets/main.css` 的 CSS 变量（`--brand/--brand-weak/--ink/--muted/--faint/--line/--line-strong/--card/--paper/--radius/--shadow-hover` 等），不要硬编码颜色。
- 移动端：`@media (max-width: 768px)`；可点元素/按钮 `min-height: 44px`（全局 `main.css` 已给 `.el-button` 兜底，自定义可点元素需自行保证）。

---

## State & Tabs

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

---

**Language**: All documentation should be written in **English**。本文件按仓库既有习惯使用中文正文说明。
