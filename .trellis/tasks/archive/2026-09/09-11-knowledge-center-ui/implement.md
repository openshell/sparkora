# Implement — C3 知识中心浏览页（车型/新闻）

> 父任务 `09-11-knowledge-base-data-foundation`。执行顺序见下；验证命令统一 `npm run build`（在 `frontend/`）。

## 前置
- [ ] 已读 `prd.md`、本任务 `design.md`、父任务 `design.md` §5/§6。
- [ ] 确认 `/car`、`/car/:id`、`/car/sync`、`/kb` 现有页面不改（AC4）。

## 实现清单（有序）

1. **API 封装**（`frontend/src/api/index.js`）
   - [ ] 新增 `newsApi`：`list(params)`→`GET /news`；`get(id)`→`GET /news/{id}`；`createJob(jobType)`→`POST /news/sync/jobs`；`getJob(id)`→`GET /news/sync/jobs/{id}`；`listJobs()`；`retryJob(id)`。
   - [ ] 新增 `kbApi`：`list()`→`GET /kb/docs`；`get(id)`；`create(data)`；`update(id,data)`；`remove(id)`；`rebuild(id)`。
2. **KbLibrary.vue 换封装**（机械替换，行为不变）
   - [ ] `import { kbApi } from '../api'`，替换 5 处 `http.*` 调用为 `kbApi.*`；保留错误处理与提示文案。
3. **车型面板** `frontend/src/views/knowledge/CarKnowledgePanel.vue`
   - [ ] `carApi.list()` 加载；关键词/网络/状态筛选；卡片网格 + `introImageUrls[0]` 缩略图 + 占位；点卡片 `router.push('/car/'+id)`；编辑器以上「同步车型」跳 `/car/sync`；空态/加载/错误态。
4. **新闻面板** `frontend/src/views/knowledge/NewsKnowledgePanel.vue`
   - [ ] `newsApi.list({page,size:12,keyword})` 列表 + `el-pagination`；`tagNames` JSON 解析；封面 URL 解析；详情抽屉（`newsApi.get`，正文/原文链接/切块数）；图片型空正文提示；编辑器以上同步触发 + 进度轮询 + 终态刷新；空态/加载/错误态。
5. **知识中心页** `frontend/src/views/KnowledgeCenter.vue`
   - [ ] `TopBar` + `container` + 页头 + `el-tabs`（车型 / 新闻）承载两面板。
6. **路由** `frontend/src/router/index.js`
   - [ ] 新增 `{ path: '/knowledge', name: 'knowledge', component: () => import('../views/KnowledgeCenter.vue'), meta: { auth: true } }`。
7. **导航** `frontend/src/layouts/TopBar.vue`
   - [ ] 新增「知识中心」`router-link to="/knowledge"`（登录后可见）；保留现有「车型库」「知识库」链接。

## 验证

```bash
cd frontend && npm run build     # 必须通过
# 联调（可选）：./dev.sh start，浏览器手测 /knowledge 两 Tab、/car、/kb
```

- [ ] AC1 `/knowledge` 可访问，含车型/新闻两 Tab。
- [ ] AC2 车型 Tab 列表可用、卡片跳 `/car/:id`。
- [ ] AC3 新闻 Tab 列表分页 / 详情正文可用。
- [ ] AC4 `/car`、`/kb` 路由仍可用。
- [ ] AC5 `npm run build` 通过。

## 风险 / 回滚点
- KbLibrary 换封装：保持请求方法/路径/参数完全一致，避免行为漂移。
- 新增文件可删；`router`/`TopBar`/`api` 改动均为可逆新增项。
