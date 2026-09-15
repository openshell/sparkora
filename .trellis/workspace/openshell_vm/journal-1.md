# Journal - openshell_vm (Part 1)

> AI development session journal
> Started: 2026-09-03

---



## Session 1: 配图并入预览:移除 IMAGES_READY 与独立配图步骤
<!-- trellis-session: v=2 fp=82617d6bf8a0a981 -->

**Date**: 2026-09-03
**Task**: 配图并入预览:移除 IMAGES_READY 与独立配图步骤
**Branch**: `s4-preview-publish`

### Summary

将独立配图步骤并入预览,流程五步改四步;彻底移除 IMAGES_READY 状态,VERSIONS_READY 后直接可预览/发布;预览工具栏新增配图面板(图库插入+AI生图);删除 complete-images 接口与 StepImages.vue;同步 spec 文档。附带提交工作区遗留的 S6 多车型关联改动。

### Git Commits

| Hash | Message |
|------|---------|
| `e43842c` | feat(S6): 配图并入预览步骤,移除 IMAGES_READY 状态与独立配图步骤 |

### Status

[OK] **Completed**

## 2026-09-03 · S6.1 知识库必查+降级可见(rag-mandatory-gate)

**Branch**: `feat/qiniu-only-image-library`(沿用当前分支,不建 PR)

### Progress

- [x] CarRagService: RagStatus/RagResult/retrieveForGeneration(必查入口,不抛异常)
- [x] AiProperties/application.yml/.env.example: ragMinScore=0.3 / ragRejectScore=0.5 配置化
- [x] BriefService/VersionService: 必查+降级可见+factRisks 标注+rag_status 落库
- [x] StepBrief.vue/StepVersions.vue 检索状态标签
- [x] docs/s0-spec.md §6b 契约节 + §7b 配置表
- [x] mvn test 6/6 绿(新增 CarRagServiceTest)+ npm build 通过
- 留真机验收:AC1 检索失败模拟(dev.sh 联调断 embedding)、AC3/AC4 真实生成回归

### Notes

- 门槛语义:相似度衡量相关性非事实正确性;防知识库错误数据靠入库源头(PRD 已写明)。
- task.py start 提示 base_branch 即当前分支;用户流程历来在当前分支直接提交,不建 PR,可接受。

## 2026-09-04 项目列表完善(09-04-project-list-enhance)
- 交付:GET /api/projects 白名单排序参数 + ProjectList 搜索/筛选/排序/批量删除 + spec §3.3 同步,commit 0db6e53(分支 feat/project-list-enhance)。
- 检查发现并修复:批量删除后空页回退条件 rows.length<=0 永不触发,改为按 total-count 算最大页。
- 平台问题:use_capability 派发 trellis-implement/trellis-check 子代理三次被"回合打断"连带取消(静默 11min/8min/96s 后 turn interrupt,error: context canceled);子代理零产出。已降级内联实施与检查。待反馈 Reasonix:run_skill 无进度回显/启动慢,建议透传子代理进度。


## Session 2: S11 简报生成重设计:检索设置页+取消快速模式+知识库可暂停
<!-- trellis-session: v=2 fp=a14065cce37bfb09 -->

**Date**: 2026-09-09
**Task**: S11 简报生成重设计:检索设置页+取消快速模式+知识库可暂停
**Branch**: `main`

### Summary

实现检索设置页(sparkora_setting 双开关,知识库默认停用/外部搜索默认启用,写仅 ADMIN);深度链路 applySettingGates 门控工具装配,rag_status 增 DISABLED;FAST 两生成入口封死(410),创建页移除模式单选与锚点车型入口,唯一生成路径为深度流程;多版本改逐风格调 deep/generate。全量 44 测试绿,联调实测 AC1~AC7 通过(项目30/31 非车型主题全链路)。诊断 Reasonix subagent 派发挂起(TRELLIS_CONTEXT_ID 断链+平台管道问题),已固化 AGENTS.md 缓解并给官方 PR 提示词。归档 09-09-brief-gen-redesign 与被 supersede 的 09-03-rag-mandatory-gate。

### Git Commits

| Hash | Message |
|------|---------|
| `54a4dbb` | feat(S11): 简报生成重设计——检索设置页+取消快速模式+知识库可暂停 |
| `b870f5f` | chore(task): 新增 09-09-brief-gen-redesign 任务工件(prd/design/implement) |

### Status

[OK] **Completed**


## Session 3: 简报生成流程重构:消除创建后竞态与冗余入口
<!-- trellis-session: v=2 fp=3534e366a50d8c23 -->

**Date**: 2026-09-11
**Task**: 简报生成流程重构:消除创建后竞态与冗余入口
**Branch**: `main`

### Summary

系统化修复主题创作简报生成:clarify 异步化(202)消除创建后「导航早于落库」竞态;brief 侧新增 plan_status(PLANNING/READY)+ 部分唯一索引 uq_brief_planning 做并发幂等;StepBrief 无简报区收敛为唯一 deepStage 状态机,删除 deepMode/6s 有界重探测/裸按钮/两步入口。子代理实现+检查,后端编译与前端构建均通过,check 修复 1 CRITICAL(PLANNING 轮询未带 briefId 导致失败误判)+1 WARNING(成功后未清 lastBriefError),并补规格同步。

### Main Changes

- ClarifyService: start() 同步落 PLANNING 占位+self.runAsync 异步生成;失败删占位行+写 lastBriefError
- DeepController: /deep/clarify 返回 {briefId,stage:PLANNING} 202;stageOf 首判 PLANNING;status 增 planStatus;409 映射
- ArticleBriefEntity/schema.sql: 新增 plan_status 列 + uq_brief_planning 部分唯一索引(幂等)
- ProjectEdit: TOPIC 先 await startDeep 再导航,去 ?gen=deep;仿写分支不动
- StepBrief: 单一 deepStage 状态机(NONE|PLANNING|CLARIFYING|CLARIFIED|RESEARCHING|RESEARCH_DONE);PLANNING 自轮询;restarting 标志
- spec: backend database-guidelines 增异步占位幂等索引先例;error-handling 增「轮询可删除占位按最新行查询」与「成功后未清 last_*_error」两条 Common Mistake

### Git Commits

| Hash | Message |
|------|---------|
| `d87df28` | fix(deep): 研究计划(clarify)异步化——消除创建后竞态与重复落库 |
| `b5fcc78` | fix(ui): 简报页无简报区收敛为单一状态机——消除裸按钮与两步入口 |
| `dbdfc79` | docs(deep): 同步 clarify 异步化、PLANNING 态与 plan_status 规格 |
| `74e2e1f` | docs(spec): 沉淀异步占位幂等索引与轮询/清错教训(clarify 先例) |

### Testing

- [OK] mvn -q -DskipTests compile 通过
- [OK] npm run build 通过
- [OK] 联调: AC1 clarify ~69ms 返回 PLANNING;AC3 重复触发 409 且 DB 仅 1 条 PLANNING;AC4 断点恢复;AC5 失败回引导态;陈旧占位自愈

### Status

[OK] **Completed**

### Next Steps

- 如需浏览器端手测 AC2/AC6/AC7 可补;LLM provider 偶发空 content 为既有问题,不在本轮范围


## Session 4: 扩展 wenyan 主题库为 15 个并支持社区主题发布
<!-- trellis-session: v=2 fp=0b7a0cf69c2dd757 -->

**Date**: 2026-09-11
**Task**: 扩展 wenyan 主题库为 15 个并支持社区主题发布
**Branch**: `main`

### Summary

主题从 4 扩到 15（8 内置 + 7 mdnice 社区），预览/发布全打通。新增 WenyanThemeCatalog 权威目录 + jar 安全物化社区 CSS（随后端包内置）；PreviewService 渲染分支：内置 --theme、社区 --custom-theme <本地CSS>（不传 --theme）；preview-options/publish-options 的 themes 由 string[] 改对象数组 {id,name,group,color,bright}；前端预览页改分组下拉，发布页只读回显；新增 docs/wenyan.md，同步 s0-spec §11/§12，沉淀 external-cli-integration spec。AC1-AC7 全通过，双构建 EXIT=0。

### Git Commits

| Hash | Message |
|------|---------|
| `7ad8e67` | feat(S5): 主题库扩展为 15 个——启用全部内置并支持社区主题发布 |

### Status

[OK] **Completed**


## Session 5: C1 车型库数据基座加固
<!-- trellis-session: v=2 fp=30dfd6dd1660281e -->

**Date**: 2026-09-12
**Task**: C1 车型库数据基座加固
**Branch**: `main`

### Summary

完成父任务下 C1 子任务：intro_images 语义统一(派生 introImageUrls)、删除车型向量兜底清理、KB 向量索引 IVFFLAT→HNSW、手动+定时增量同步(SCHEDULED)。实测车型同步/七牛转存公网200/索引切换/编译构建全通过。更新 backend spec 记录索引幂等切换与物理向量表级联清理约定。父任务余 C2 新闻域/C3 知识中心/C4 问答待做。

### Git Commits

| Hash | Message |
|------|---------|
| `db4fa02` | feat(S6): 车型库数据基座加固——intro_images 语义统一、删除向量兜底清理、KB 索引统一 HNSW、手动+定时增量同步 |
| `924b4bc` | docs(spec): 记录 pgvector 索引幂等切换与物理向量表级联清理约定 |

### Status

[OK] **Completed**


## Session 6: C2 新闻数据接入与独立知识域
<!-- trellis-session: v=2 fp=db2f57ad35db535b -->

**Date**: 2026-09-12
**Task**: C2 新闻数据接入与独立知识域
**Branch**: `main`

### Summary

比亚迪官方新闻全量(167篇)抓取+正文抽取+向量化，作为第三知识域(NEWS)接入统一检索；修复多域检索候选窗口被新闻挤占的严重缺陷；新增 NewsController 6 路由与定时增量同步。

### Git Commits

| Hash | Message |
|------|---------|
| `6fa6297` | feat(S11): 新闻数据接入与独立知识域——抓取/正文抽取/向量化/统一检索/NEWS REST |
| `4c6f05a` | docs(spec): 记录多域统一检索候选窗口按域隔离约定 |

### Status

[OK] **Completed**


## Session 7: C3 知识中心浏览页
<!-- trellis-session: v=2 fp=37ea36543d09a515 -->

**Date**: 2026-09-12
**Task**: C3 知识中心浏览页
**Branch**: `main`

### Summary

新增 /knowledge 知识中心(车型/新闻双 Tab)，补 newsApi/kbApi 封装，规范前端 API 分层；check AC1-AC5 全通过

### Main Changes

- 新增 KnowledgeCenter + Car/NewsKnowledgePanel 两面板
- api/index.js 增 newsApi/kbApi；KbLibrary 由直调 http 改 kbApi
- 新增 .trellis/spec/frontend/index.md 前端规范层；spec §16

### Git Commits

| Hash | Message |
|------|---------|
| `5cf4902` | feat(C3): 知识中心浏览页——车型/新闻双 Tab、newsApi/kbApi 封装、/knowledge 路由 |
| `38fb0ee` | docs(spec): 知识中心规格 §16 + 前端规范层(API 分层/页面骨架/R<T> 拆包/Tab 懒挂载) |

### Testing

- [OK] npm run build exit 0；AC1-AC5 全 PASS

### Status

[OK] **Completed**

### Next Steps

- 继续 C4 多轮对话式知识问答


## Session 8: C4 多轮对话式知识问答
<!-- trellis-session: v=2 fp=464badc57b4072f3 -->

**Date**: 2026-09-12
**Task**: C4 多轮对话式知识问答
**Branch**: `main`

### Summary

跨三域(CAR/KB/NEWS)检索合成多轮问答:QaService+会话/消息表(S12)+/api/qa+/qa 页面与引用;CitationList 增 NEWS 分支;补 AI/RAG 规范层与 knowledge-base.md 评估结论。AC1-AC5 全通过(71 tests,build 绿)。

### Git Commits

| Hash | Message |
|------|---------|
| `07fa8d0` | feat(S12): 多轮对话式知识问答——跨三域检索合成、会话/消息持久化、/qa 页面与引用 |
| `c94c415` | docs(spec): 新增 AI/RAG 规范层(单轮/多轮合成 + 三域检索契约 + 开关契约) |

### Status

[OK] **Completed**


## Session 9: kb-cleanup 遗留清理
<!-- trellis-session: v=2 fp=28026f8f236fe148 -->

**Date**: 2026-09-12
**Task**: kb-cleanup 遗留清理
**Branch**: `main`

### Summary

Session summary was not supplied.

### Git Commits

| Hash | Message |
|------|---------|
| `c047f7c` | fix(cleanup): 知识库遗留清理——前端 rebuildAll 封装、新闻列表去 content、定时同步陈旧自愈、新闻块类型测试与死代码 |

### Status

[OK] **Completed**


## Session 10: 简报研究链路修复:配置绑定、工具健康与进度分子
<!-- trellis-session: v=2 fp=6b70267a31b18b6d -->

**Date**: 2026-09-16
**Task**: 简报研究链路修复:配置绑定、工具健康与进度分子
**Branch**: `main`

### Summary

修复简报页深度研究链路三类缺陷:进度分子 doneCount 缺失、DeepProperties 前缀漂移致配置整块静默失效、SearchTool 可用性惰性闩锁与 toolHealth 展示失真

### Main Changes

- DeepProperties 绑定前缀 sparkora.ai.deep → sparkora.deep,修复 SEARCH_WEB_ENABLED / tavily-api-key 未绑定
- SearchTool 增 configured()/lastCallOk(),available() 收敛为纯配置判定,消除一次失败永久禁用
- toolHealth 由布尔改状态码 OK|DISABLED|UNCONFIGURED|FAILED,纳入 SettingService 运行时门控
- ResearchProgress 补 doneCount 进度分子、状态码工具标签、useRoute() 替代手工路径解析

### Git Commits

| Hash | Message |
|------|---------|
| `bb3c0ad` | fix(deep): 修复研究链路配置绑定与工具健康展示 |
| `f39bbb1` | docs(spec): 同步工具健康状态码契约并沉淀前缀漂移/闩锁教训 |
| `0d65fd4` | chore(task): 简报研究链路修复任务工件 |

### Testing

- [OK] mvn -q -DskipTests compile EXIT 0；mvn test 85 通过 0 失败；npm run build EXIT 0
- [OK] check 子代理实测 R2 绑定探针与 R4 live toolHealth 状态码通过

### Status

[OK] **Completed**

### Next Steps

- 可选手测 UI 渲染级 AC(进度递增、工具标签)
