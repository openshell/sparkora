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


## Session 12: 系统说明文档重构:总览+模块结构,删除 s0-spec
<!-- trellis-session: v=2 fp=eadfce8159c98112 -->

**Date**: 2026-09-20
**Task**: 系统说明文档重构:总览+模块结构,删除 s0-spec
**Branch**: `main`

### Summary

将 1188 行 s0-spec 巨石重构为 docs/README.md 总览 + docs/spec/** 17 份模块文档,删除原文件并同步全部引用

### Main Changes

- 新增 docs/README.md 总览(架构图/概念地图/模块索引表/全局约定/配置总览)
- 拆解 s0-spec 为 docs/spec/** 模块文档并删除原文件(决策 B:彻底迁移移除)
- article-generation-flow 重写为当前唯一深度链路(去 FAST 双模式)
- 同步代码注释/README/AGENTS/Trellis spec/skill 全部引用指向模块文档
- 新增 .trellis/spec/guides/docs-structure-guide.md(引用指向文件而非编号)

### Git Commits

| Hash | Message |
|------|---------|
| `ce67176` | feat(docs): 重构系统说明文档为总览+模块结构 |
| `247efdb` | docs(spec): 沉淀文档结构层级与「引用指向文件而非编号」约定 |
| `e7c47fe` | chore(task): 系统说明文档重构任务工件 |

### Testing

- [OK] 反向信息完整性核对:表/接口/env/枚举零丢失;死链 0;回链 17/17
- [OK] mvn -q -DskipTests compile EXIT 0;Java diff 仅注释行

### Status

[OK] **Completed**


## Session 13: Docker 容器化产线部署(compose)
<!-- trellis-session: v=2 fp=6c1834f5b36413a8 -->

**Date**: 2026-09-25
**Task**: Docker 容器化产线部署(compose)
**Branch**: `main`

### Summary

将产线部署从手写脚本改为 docker compose 一键起容器；新增后端多阶段镜像(Maven 构建 → JRE21+Node+@wenyan-md/cli 2.0.11，内置 CLI 支撑预览同核渲染)、前端 nginx 镜像(envsubst 模板反代 /api)、docker-compose.yml(端口单一来源 SERVER_PORT、env_file 注入 .env、./data 挂卷持久化、401 视为存活的健康检查)、双 .dockerignore、docs/deploy.md；同步 .env.example/文档。保留 dev.sh 本地热重载联调，零业务代码改动。AC1-AC7 全通过(compose 起停/nginx 反代 401/容器内 CLI 真实渲染/数据卷持久化/无密钥泄露/构建无回归)；并沉淀 .trellis/spec/backend/container-deployment.md。

### Git Commits

| Hash | Message |
|------|---------|
| `40f4a4b` | feat(deploy): Docker 容器化产线部署(backend+frontend compose) |

### Status

[OK] **Completed**



## Session 14: Session 14: 简报外部搜索策略路由 + 事实手册近似归并
<!-- trellis-session: v=2 fp=965aa3cf1ed4b0ad -->

**Date**: 2026-09-26
**Task**: Session 14: 简报外部搜索策略路由 + 事实手册近似归并
**Branch**: `main`

### Summary

S9 外部搜索改为策略路由(默认 Tavily 优先、ADMIN 全局可配、密钥只在 .env)；修复 LLM 降级时 WEB 结果数口径并区分 LLM_FALLBACK；新增事实手册近似 claim 归并(数值签名硬门槛+无数字高阈值,修复 MULTI 类型未落 JSON 旧 bug)恢复多来源交叉验证；引用面板与手册展示 WEB 实际 provider。mvn test 285 全绿、npm run build 通过、后端容器 healthy。

### Git Commits

| Hash | Message |
|------|---------|
| `17b7f99` | feat(S9): 简报外部搜索改为策略路由并支持 Tavily 优先全局配置 |
| `1fc81d5` | fix(deep): LLM 汇总降级时保留 WEB 结果数口径并区分 LLM_FALLBACK 原因 |
| `deca60f` | fix(deep): 事实手册近似 claim 归并恢复来源交叉验证并展示 WEB provider |

### Status

[OK] **Completed**


## Session 15: Session 15: 修复并行子代理研究进度实时回写
<!-- trellis-session: v=2 fp=b63ccab5ee140731 -->

**Date**: 2026-09-26
**Task**: Session 15: 修复并行子代理研究进度实时回写
**Branch**: `main`

### Summary

定位进度页「首个问题耗时、其余瞬过」根因:DeepResearchService.doRunAsync 并行 submit 但按收集顺序串行回写,慢的 future[0] 阻塞后继 agent 落库。改为 submit 前批量置全部 agent RUNNING + 每 agent 独立收集器完成即回写(真乱序),updateAgent/writeNotes 加 per-brief 锁防并发丢更新;保留超时 cancel(true)+FAILED、失败隔离、汇总顺序。新增 DeepResearchServiceProgressTest(6 用例),mvn test 291 全绿,后端容器 healthy。

### Git Commits

| Hash | Message |
|------|---------|
| `7870156` | fix(deep): 子代理研究进度改为完成即回写并保证并发写安全 |

### Status

[OK] **Completed**


## Session 16: 固化容器化约定:代码变动后主动重建
<!-- trellis-session: v=2 fp=ce110f90fd809191 -->

**Date**: 2026-09-26
**Task**: 固化容器化约定:代码变动后主动重建
**Branch**: `main`

### Summary

把「每次完成后端/前端代码变动后主动 docker compose up -d --build 重建容器」固化为跨会话约定,写入 AGENTS.md(Commands 段,gitignore 本地文件)与 docs/deploy.md(§3/§6/§8 三处一致);并沉淀进 .trellis/spec/backend/container-deployment.md 的 Common Mistakes。纯文档变更,无业务代码改动。质检发现并修复 docs/deploy.md 两处与新约定矛盾之处。根因:compose 仅挂载 ./data,源码烘焙进镜像,restart/裸 up -d 不重建。

### Git Commits

| Hash | Message |
|------|---------|
| `6fa516e` | docs(deploy): 固化代码变动后主动重建容器约定 |

### Status

[OK] **Completed**


## Session 17: Session 17: 深度研究素材覆盖与降级补齐（snippet保真/背景维度/动态子代理/简报提额）
<!-- trellis-session: v=2 fp=c2b058017e9a005e -->

**Date**: 2026-09-26
**Task**: Session 17: 深度研究素材覆盖与降级补齐（snippet保真/背景维度/动态子代理/简报提额）
**Branch**: `main`

### Summary

任务 09-26-deep-research-coverage：R1 降级保真 snippet（rawFallback+FactSheet+写作prompt）、R2 背景维度（LLM判断+信号词兜底）、R3 进度页展示 LLM_FALLBACK、R4 汇总截断提额4096重试、R5 maxAgents 4→6、R6 深度简报提额8192+失败16384重试并删除死FAST简报路径。新增/改测试 BriefServiceTest/SubAgentRunnerTest/FactSheetServiceTest/ClarifyServiceTest；mvn test 308 全绿。提交 3342d63、7dbb0c5，两次重建后端镜像均 healthy。

### Git Commits

| Hash | Message |
|------|---------|
| `3342d63` | feat(S9): 深度研究降级保真 snippet 并补齐背景维度与动态子代理 |
| `7dbb0c5` | fix(deep): 深度简报提额并失败重试一次，删除已下线的 FAST 简报路径 |

### Status

[OK] **Completed**


## Session 18: AI 生图体验改进：粘贴/本地参考图 + 抽屉共用组件
<!-- trellis-session: v=2 fp=9d5cd6ed52c57977 -->

**Date**: 2026-09-26
**Task**: AI 生图体验改进：粘贴/本地参考图 + 抽屉共用组件
**Branch**: `main`

### Summary

父任务 09-26-image-gen-ux-paste 收口：①后端子任务 img2img-ref-upload 新增 multipart 接口 POST /api/images/generate-from-image-upload（参考图字节直传 AI、不落图库、结果 ref_image_id=NULL），提取 ImageService.readValidatedImage 复用校验；②前端子任务 image-gen-drawer-ux 抽取共用组件 frontend/src/components/AiImageDrawer.vue（library/preview 双模式，props/emits 契约），支持粘贴(Ctrl/⌘+V)/本地文件/图库三来源参考图，新增 utils/imageRefCache.js 会话缓存(LRU 20, 不持久化)支撑本地来源重生成，缓存失效置灰+tooltip；图库页与预览页两入口统一，消除重复模板。check 修复 4 处缺陷：粘贴文件名无扩展名导致后端 400、图库页缓存重生成 projectId 回退、语义搜索模式误置灰、缓存参数快照漂移。docs/spec/image.md §1/§6/§8/§10 与 .trellis/spec/frontend/index.md（上传须补扩展名、动态 :is 须显式 import）同步。验证：mvn compile + 313 单测 + npm run build 全绿。

### Git Commits

| Hash | Message |
|------|---------|
| `cdc1ecf` | feat(img2img): 新增参考图直传的图生图接口（不落图库） |
| `1a991e8` | feat(ui): AI 生图抽屉共用组件 + 粘贴/本地参考图与界面重构 |
| `482c677` | docs(image): 同步图生图直传接口与生图抽屉规格 |

### Status

[OK] **Completed**


## Session 19: 图生图多参考图（≤4，混合来源）+ 405 部署修复验证
<!-- trellis-session: v=2 fp=00db1bef6ff46ef0 -->

**Date**: 2026-09-27
**Task**: 图生图多参考图（≤4，混合来源）+ 405 部署修复验证
**Branch**: `main`

### Summary

父任务 09-26-img2img-multi-ref 收口：①后端子任务新增多 image part 客户端（AiImageClient.generateImage2Image 收 List<byte[]>+List<String>，保序，空/不匹配抛 AiException）+ 统一多图接口 generate-from-image-upload（files[] + refImageIds[]，保序 files 前 refs 后，总数 1~4，逐张校验，ref_image_id 仅单张图库来源落 id 否则 NULL）；新增 IMAGE_MAX_REQUEST_MB(45)。②前端子任务：AiImageDrawer 参考图区由单张改有序 refs[]（local/library，REF_MAX=4，可粘贴多文件/本地多选/图库多选/混合、逐张移除、n/4 计数），统一走多图 multipart；imageRefCache 扩展为整组 {files[],refImageIds[],names[],previewUrls[]}；两宿主重生成同口径（缓存命中复用整组，仅单图库来源走后端 /regenerate，刷新后本地来源置灰）。check 修复：条目内重复 File 的 ObjectURL 泄漏、图库弹窗重复选图占用名额。③父任务：docs/spec/image.md 多图契约同步（§1 ref_image_id 规则、§6 接口行、§8 页面职责、§10 已知限制）。集成验证：mvn compile + 320 单测 + npm build 全绿；docker compose up -d --build 后实测 405→400（0 张「请至少选择 1 张参考图」/ 5 张「最多支持 4 张参考图」/ 不存在 id「参考图不存在」），真实多图端到端通过（2 本地图→refImageId null；本地+图库混合→null；单图库→refImageId=208；图库双选→null；对单图库结果调 /regenerate 成功），测试图 7 张已精确删除、图库总数恢复 168 基线。

### Git Commits

| Hash | Message |
|------|---------|
| `0699a25` | feat(img2img): 图生图支持多张参考图（多 image part + files[]/refImageIds[] 集合接口） |
| `02c0b78` | docs(spec): 固化 multipart 集合参数绑定与顺序/计数约定 |
| `fc2f6d7` | feat(ui): 图生图抽屉支持多张参考图(粘贴/本地/图库,上限4)与统一多图提交 |
| `4311f21` | docs(spec): 固化会话缓存 ObjectURL 按 File 引用去重约定 |
| `3ef655a` | docs(image): 图生图多参考图契约同步（files[]/refImageIds[]、上限4、ref_image_id 规则） |

### Status

[OK] **Completed**


## Session 20: P0 加固:超时对齐+并发回写修复+JWT查库校验
<!-- trellis-session: v=2 fp=9ef74e1de7f53701 -->

**Date**: 2026-09-27
**Task**: P0 加固:超时对齐+并发回写修复+JWT查库校验
**Branch**: `main`

### Summary

修复设计评审 P0 三项:nginx 反代超时 180s→300s 对齐前端最长 axios;8 处项目表 updateById 全字段回写改 UpdateWrapper 条件更新(状态白名单防回退,首版 current 两拆分);JwtAuthenticationFilter 查库校验 enabled/role(60s 缓存,fail-closed)。325 测试通过,新增 JWT 5 用例。spec 四处同步

### Git Commits

| Hash | Message |
|------|---------|
| `9e2eb5c` | fix(security): JWT 查库校验 + 产线反代超时对齐 300s |
| `ecc3445` | fix(s2): 项目状态推进改 UpdateWrapper 条件更新,消除全字段回写覆盖 |
| `410f960` | docs(spec): 同步 JWT 查库校验与反代超时契约 |

### Status

[OK] **Completed**


## Session 21: P1-⑤ 状态机推进收敛到 ProjectStatusService
<!-- trellis-session: v=2 fp=dca57e4b22add40f -->

**Date**: 2026-09-27
**Task**: P1-⑤ 状态机推进收敛到 ProjectStatusService
**Branch**: `main`

### Summary

把散落 5 类+schema.sql 的项目状态推进逻辑收敛到唯一 ProjectStatusService:抢占/成功推进(首版两拆分)/失败回退/发布终态/错误列/陈旧自愈常量/409守卫提示语全部单点;6 调用方纯委托;语义逐字等价(P0 修复基线),345 测试全绿(新增 20),前端零改动。P1 父任务第五项完成。

### Git Commits

| Hash | Message |
|------|---------|
| `dcedf49` | refactor(s2): 项目状态机推进收敛到 ProjectStatusService |
| `05900ae` | docs(spec): 状态机写权收敛契约同步 |

### Status

[OK] **Completed**


## Session 22: P1-④ 生成链路异步化(同步占位+@Async+轮询)
<!-- trellis-session: v=2 fp=b2e393d2cd80ca17 -->

**Date**: 2026-09-27
**Task**: P1-④ 生成链路异步化(同步占位+@Async+轮询)
**Branch**: `main`

### Summary

三条项目状态驱动的生成链路(imitation/analyze、generate/versions、deep/generate)改为同步毫秒级返回+@Async后台+前端轮询;deep 改批量 styleIds[] 并补 claim(专用 claimDeepVersionsGenerating);移除 advanceVersionsReadyFromReady;新增 25 测试(370 全绿);error-handling.md 新增 start/run 异步切分约定。

### Git Commits

| Hash | Message |
|------|---------|
| `6cd8bed` | feat(s2): 生成链路异步化(同步占位+@Async+状态机委托) |
| `217e35f` | feat(ui): 三生成链路改轮询驱动刷新 + deep/generate 批量 styleIds |
| `66142c1` | docs(spec): 生成链路异步化契约同步 + start/run 异步切分约定 |

### Status

[OK] **Completed**


## Session 23: P1-⑥ Flyway 版本化迁移
<!-- trellis-session: v=2 fp=e0d3e559f519b063 -->

**Date**: 2026-09-27
**Task**: P1-⑥ Flyway 版本化迁移
**Branch**: `main`

### Summary

引入 Flyway 替代 schema.sql 兼职迁移:schema.sql 全量逐字固化为 V1__baseline.sql,既有库 BSLN@1 跳过、空库 V1 自举;文档/spec 33 处引用改指 Flyway

### Main Changes

- pom 增 flyway-core + flyway-database-postgresql(Boot BOM 10.10.0);application.yml 换 spring.flyway.*(baseline-on-migrate=true/baseline-version=1)
- db/schema.sql → db/migration/V1__baseline.sql(逐字)+ 新增 migration/README.md;删旧 schema.sql
- database-guidelines.md Migrations 段整体改写为 Flyway 约定;补 PG 主版本超前 Flyway 告警 gotcha

### Git Commits

| Hash | Message |
|------|---------|
| `c92f475` | feat(db): 引入 Flyway 版本化迁移替代 schema.sql 兼职 |
| `6002212` | docs(spec): schema.sql 引用改指 Flyway 迁移 + 版本化约定 |

### Testing

- [OK] mvn -q -DskipTests compile 通过;mvn test 370 全绿
- [OK] 隔离 pgvector 容器端到端:空库 V1 type=SQL、既有库 BSLN@1 跳过、重启幂等、V2 前向迁移;pg_dump 结构等价

### Status

[OK] **Completed**

### Next Steps

- P1-⑦ JSONB/表结构变更(已解锁,走 V2+ 迁移);或 P1-⑧ 知识域写入统一


## Session 24: P1-⑦ body_image_ids 规范化(Flyway V2)
<!-- trellis-session: v=2 fp=62543056b953400d -->

**Date**: 2026-09-27
**Task**: P1-⑦ body_image_ids 规范化(Flyway V2)
**Branch**: `main`

### Summary

P1-⑦ scope A:body_image_ids 逗号列 → sparkora_article_version_image 关联表(V2 迁移含保序回填+DROP);JSONB 复核判定为有意约定不处置

### Main Changes

- V2__article_version_image.sql:建表+regexp_split_to_table WITH ORDINALITY 保序回填+DROP COLUMN
- ArticleVersionImageEntity/Mapper;ImageService 引用检查改精确 SQL、modifyBodyImage insert/delete、快照契约不变;PreviewService 查关联表
- spec/docs 同步:JSONB 有意约定裁定 + 有序小集合关联表先例

### Git Commits

| Hash | Message |
|------|---------|
| `51ffef1` | feat(db): body_image_ids 规范化为 version-image 关联表（V2 迁移） |
| `a80be75` | docs(spec): 图像版本关联表字段同步 + JSONB 有意约定裁定 |
| `d248513` | chore(task): archive 09-27-jsonb-normalize; update P1 parent task map |

### Testing

- [OK] mvn test 382 全绿(370 基线+12);npm run build 通过;隔离 pgvector 容器验两路径迁移+保序回填

### Status

[OK] **Completed**

### Next Steps

- P1-⑧ 知识域写入统一 / P1-⑨ 拆巨石


## Session 25: P1-⑧ 知识域写入侧统一 + 向量模型名防护
<!-- trellis-session: v=2 fp=54ffd6ed9702de59 -->

**Date**: 2026-09-27
**Task**: P1-⑧ 知识域写入侧统一 + 向量模型名防护
**Branch**: `main`

### Summary

统一 CAR/KB/NEWS 切块(TextChunker)、并发嵌入(EmbeddingBatchRunner)、事务边界(REQUIRES_NEW+自注入)，修 CAR/NEWS 失效 @Transactional；V3 迁移给 4 张向量表加 embedding_model，检索按当前模型过滤，EmbeddingClient 维度校验，EmbeddingModelReconcileRunner 启动对账，NEWS 补 POST /{id}/rebuild；424 测试全绿，隔离 pgvector 容器验迁移与检索过滤；check 修正切块分隔符过度统一(AC4)

### Git Commits

| Hash | Message |
|------|---------|
| `73980ab` | feat(ai): 知识域写入侧统一(切块/并发嵌入/REQUIRES_NEW) + 向量模型名防护(V3/检索过滤/维度校验/NEWS重建端点) |
| `25b5b12` | docs(spec): 向量模型列/维度校验/重嵌入口契约同步 |
| `f0f7e59` | chore(task): archive 09-27-knowledge-write-unify; update P1 parent task map |

### Status

[OK] **Completed**


## Session 26: P1-⑨ 拆巨石（后端控制器+前端大组件）
<!-- trellis-session: v=2 fp=a0dee05afe2be1ea -->

**Date**: 2026-09-27
**Task**: P1-⑨ 拆巨石（后端控制器+前端大组件）
**Branch**: `main`

### Summary

三巨石纯结构拆分、零行为变化：ArticleProjectController 555→155 行按子域拆出 ProjectBrief/Version/Image/Preview/Publish 5 个薄控制器（路由 25/25 等价、鉴权矩阵逐字一致、preview 400/409 不一致保留、方法体逐字搬迁）；StepPreview 994→441 + PreviewToolbar/Pane/ImageDrawer + 4 composable；ImageLibrary 908→354 + 5 子组件 + 4 composable + 2 utils。mvn test 424 全绿、npm run build 通过。

### Git Commits

| Hash | Message |
|------|---------|
| `9801eaf` | refactor(web): ArticleProjectController 按子域拆分（路由/鉴权等价） |
| `8351abf` | refactor(ui): StepPreview 抽子组件 + composable |
| `0cacce9` | refactor(ui): ImageLibrary 抽子组件 + composable |
| `de362e1` | docs(spec): 巨石拆分后的权威代码路径同步 |

### Status

[OK] **Completed**


## Session 27: 简报到写作断链修复(09-27-brief-writing-linkage-fix)
<!-- trellis-session: v=2 fp=f783ec93ab289fdc -->

**Date**: 2026-09-27
**Task**: 简报到写作断链修复(09-27-brief-writing-linkage-fix)
**Branch**: `main`

### Summary

5 项 P0:R1 简报四字段注入写作 prompt(空字段逐字兼容);R2 kbAuthoritative 仅限参数型(背景题永不因 KB 命中 MODEL_INFO 跳过 WEB);R3 研究窗口保背景题(selectResearchWindow,Option A maxAgents=6);R4 正文截断提额重试 4096→8192(ChatResult 增 finishReason);R5 webQuery 否定答案过滤。check 修 4 处(畸形JSON块头重复/AC-01 尾部指引句兼容/格式粘连/spec 漂移)+新增回归测试。mvn test 448 全绿,npm build 通过。

### Git Commits

| Hash | Message |
|------|---------|
| `e7f9809` | fix(deep): 简报字段注入写作 prompt + 正文截断提额重试 |
| `864d5b0` | fix(deep): 背景题不被 KB 权威误跳过 WEB + 研究窗口保背景题 + webQuery 否定答案过滤 |
| `032b57e` | docs(spec): 简报注入/提额重试/背景题 WEB 门控/研究窗口/否定过滤契约同步 + 可选段落注入坑 |

### Status

[OK] **Completed**


## Session 28: P1 架构一致性父任务收口(Cross-child 整合验收)
<!-- trellis-session: v=2 fp=81930710d54cd348 -->

**Date**: 2026-09-27
**Task**: P1 架构一致性父任务收口(Cross-child 整合验收)
**Branch**: `main`

### Summary

父任务 09-27-p1-arch-consistency 6/6 子任务全归档后执行整合验收:①项目表 status/last_*_error 写权唯一集中 ProjectStatusService(VersionService.setCurrent 仅写 current_version_id 选择列,属⑤划定例外);②spec 同步无矛盾(schema.sql 残留仅 baseline-description 文案、body_image_ids 零残留);③mvn test 448 全绿+npm build 通过;④AGENTS.md 当前阶段补 P1 收口段+控制器列表按子域拆分现状+ProjectStatusService 条目(文件被 gitignore,磁盘已更新)。父任务归档。

### Git Commits

| Hash | Message |
|------|---------|
| `7b49298` | chore(task): archive 09-27-p1-arch-consistency |

### Status

[OK] **Completed**


## Session 29: Session 18: 背景题 Tavily 正文补抓 + 事实手册 kind 分类 + 简报注入研究假设
<!-- trellis-session: v=2 fp=fab9931317257270 -->

**Date**: 2026-09-27
**Task**: Session 18: 背景题 Tavily 正文补抓 + 事实手册 kind 分类 + 简报注入研究假设
**Branch**: `main`

### Summary

任务 09-27-tavily-extract-kind-hypotheses（机制 B）：R1 SearchTool.extract default + Tavily POST /extract、WebSearchRouter.extract 委托降级、SubAgentRunner 仅背景题 top1-2 URL 补正文、截断唯一在工具层(DEEP_WEB_CONTENT_MAX_CHARS=2000);R2 SearchHit/WebHit 增 nullable content 保留旧构造器、rawFallback 透传;R3 背景题 ctx 注入正文、参数题不触发;R4 fact_sheet entry 增 kind(簇首问题类型,兜底 param);R5 写作按 kind 分参数事实/背景素材两段(全无 kind 退化旧平铺);R6 简报注入 research_plan.hypotheses。check 阶段修复 2 缺陷(SubAgentRunner 二次截断违反单点化、WebSearchRouter.extract 缺测)。mvn test 479 全绿;无 schema 变更;后端镜像重建 healthy。

### Git Commits

| Hash | Message |
|------|---------|
| `652b187` | feat(deep): 背景题 Tavily 正文补抓 + 事实手册 kind 分类 + 简报注入研究假设 |

### Status

[OK] **Completed**


## Session 30: Session 19: 深度写作注入目标字数 + 自适应小标题分节
<!-- trellis-session: v=2 fp=27c0a8105c83a529 -->

**Date**: 2026-09-27
**Task**: Session 19: 深度写作注入目标字数 + 自适应小标题分节
**Branch**: `main`

### Summary

任务 09-27-deep-writing-adaptive-sections：R1 write 读项目 wordCountTarget 注入 user prompt「目标字数：N」(null/≤0→1500)；R2/R3 纯静态 sectionSpec/layoutRule 按目标字数分档小标题数与每节段数(≤800→2~3/801~1800→3~5/1801~3000→5~8/>3000→8~12)替换写死「2~4」；R4 重构 DeepWriterServicePromptTest 逐字断言为实质断言；write 取一次 project 快照复用 extractH1 不放大查询。check 修复分隔符半角→全角对齐 VersionService + 补 4 处测试覆盖。mvn test 488 全绿；无 schema/前端/配置变更；后端镜像重建 healthy。

### Git Commits

| Hash | Message |
|------|---------|
| `fbad06b` | feat(deep): 深度写作注入目标字数并按长度自适应小标题分节 |

### Status

[OK] **Completed**


## Session 31: Session 20: 提取共享分节档位 LayoutRules 并统一 VersionService
<!-- trellis-session: v=2 fp=3ba716cc60d32324 -->

**Date**: 2026-09-27
**Task**: Session 20: 提取共享分节档位 LayoutRules 并统一 VersionService
**Branch**: `main`

### Summary

任务 09-27-shared-layout-rules：R1 新增 com.sparkora.service.LayoutRules（纯静态：DEFAULT_WORD_COUNT_TARGET/normalizeTarget/SectionSpec/sectionSpec）；R2 DeepWriterService 删内部档位类型改委托共享类、layoutRule 文案逐字零回归；R3 VersionService.generateOne 排版铁律首行按项目 wordCountTarget 自适应(TOPIC/IMITATION 两分支同源)，其余两 bullet 逐字保留；R4 新增 LayoutRulesTest 边界 + VersionServiceAsyncTest 分档断言。只共享分类结果不统一文案格式。check 无缺陷；mvn test 494 全绿；无 schema/配置/前端/响应结构变更；后端镜像重建 healthy。

### Git Commits

| Hash | Message |
|------|---------|
| `aaa3db8` | refactor(deep): 提取共享分节档位 LayoutRules 并统一 VersionService 自适应分节 |

### Status

[OK] **Completed**


## Session 32: 预览页剪贴板传图：前端暂存 + 发布时转存七牛
<!-- trellis-session: v=2 fp=df2e4bd6bf775e58 -->

**Date**: 2026-09-27
**Task**: 预览页剪贴板传图：前端暂存 + 发布时转存七牛
**Branch**: `main`

### Summary

预览页粘贴图片不再即时上传七牛，改前端会话暂存（pendingImageStore + 占位 token sparkora-img:<id>），右侧预览投影 blob 即时可见；点「去发布」时 usePendingImageFlush 逐张上传、token 替换为公网 URL 后落库；复制排版拦截、跨项目隔离、发布页+后端双防呆。检查修复 3 项（clearOthers 泄漏、replaceToken id 前缀碰撞、dead code）。npm build / mvn compile 通过，容器已 --build 重建。

### Git Commits

| Hash | Message |
|------|---------|
| `d2bd68b` | feat(preview): 预览页剪贴板传图前端暂存 + 发布时转存七牛 |

### Status

[OK] **Completed**


## Session 33: Playwright 视觉回归 + 冒烟 E2E 基建
<!-- trellis-session: v=2 fp=11c43c5151257532 -->

**Date**: 2026-10-01
**Task**: Playwright 视觉回归 + 冒烟 E2E 基建
**Branch**: `main`

### Summary

为 PC UI 重构建前端自动化门禁:Playwright 旁挂 frontend/tests(/api 全拦截 + 预置登录态,后端零接触),7 条冒烟 + 6 页视觉基线(1280/2560 × 明暗 = 24 张),44/44 连续 3 次全绿;突变 --paper 改色验证 24/24 报红后还原;git diff src/ 与 frontend/src/ 为空,产品代码零改动;正式全量基线待 pc-ui 批 3 后重录。

### Git Commits

| Hash | Message |
|------|---------|
| `c6c876f` | test(ui): Playwright 视觉回归 + 冒烟 E2E 基建 |

### Status

[OK] **Completed**


## Session 34: 修复单行输入框 @keydown 缺 .enter 修饰符导致的按键误触发提交
<!-- trellis-session: v=2 fp=ea70eeddc91688e9 -->

**Date**: 2026-10-01
**Task**: 修复单行输入框 @keydown 缺 .enter 修饰符导致的按键误触发提交
**Branch**: `main`

### Summary

定位并修复 pc-ui 批2 (537498b) 引入的回归:ProjectEdit 三处单行框与 ClarifyForm 两处自由输入绑定裸 @keydown,导致按任意键都 preventDefault/提交——创作主题无法输入数字(且直接触发简报生成)、Ctrl+V 粘贴失效、澄清表单任意键即锁定。改为 @keydown.enter(保留 keydown 以在 Enter 时 preventDefault 阻止单行框隐式提交),handler 逻辑零改动。前端 spec 补「Enter 提交必须带按键修饰符」定式与本次回归现象。npm run build 通过,grep 确认仅剩 splitter 类 @keydown。规划阶段同时定位了另一任务 10-01-fix-input-focus-brief-title 的两个根因(main.css 全局 :focus-visible 误伤 el-input inner;selected_title 未被 DeepWriterService 消费)。

### Git Commits

| Hash | Message |
|------|---------|
| `572fa5d` | fix(ui): 修复单行输入框 @keydown 缺 .enter 修饰符导致的按键误触发提交 |

### Status

[OK] **Completed**


## Session 35: 修复输入框聚焦样式与简报标题选择
<!-- trellis-session: v=2 fp=df3abb31373a2d3f -->

**Date**: 2026-10-02
**Task**: 修复输入框聚焦样式与简报标题选择
**Branch**: `main`

### Summary

两个前端 bug 修复:① Element Plus 单行输入框聚焦时左侧孤立描边(d66e41f);② 简报选定标题未生效,发布/预览仍用项目名(83f6243)。均已提交,任务归档。

### Git Commits

| Hash | Message |
|------|---------|
| `d66e41f` | fix(ui): 修复 Element Plus 输入框聚焦样式错位(左侧孤立描边) |
| `83f6243` | fix(S6): 修复简报选定标题未生效(发布/预览仍用项目名) |

### Status

[OK] **Completed**


## Session 36: 澄清阶段AI思考过程+max_tokens截断修复+附属信息字段重构
<!-- trellis-session: v=2 fp=aaa3bbfbab43bf4b -->

**Date**: 2026-10-02
**Task**: 澄清阶段AI思考过程+max_tokens截断修复+附属信息字段重构
**Branch**: `main`

### Summary

定位澄清/研究计划阶段失败根因:AI_MODEL(deepseek-v4-pro-cus→deepseek-v4.1-flash)是reasoning模型,max_tokens=4096被推理token吃光→JSON截断。修复:1)AiClient.ChatResult增reasoning分量(回退reasoning_content、截断20000),澄清chatJson提额8192→16384重试一次;2)落research_reasoning并经/deep/status增量透出planReasoning,前端DeepPlanCard增'AI思考过程'折叠面板(修正:完成态简报正文分支也渲染,否则生成后消失);3)删keywords/remark、extraInfo改名contentDescription(V4迁移存量)、全链路注入(澄清/简报/深度写作/多版本;研究仅进子代理汇总ctx不改检索query)。验证:mvn test 534绿、npm run build绿、playwright 44绿、compose重建healthy。提交758cffa。

### Git Commits

| Hash | Message |
|------|---------|
| `758cffa` | feat(brief): 澄清阶段透出AI思考过程+修复max_tokens截断+附属信息字段重构 |

### Status

[OK] **Completed**


## Session 37: 修复正文泄漏事实风险审校话术(手册未提供/无法计算)
<!-- trellis-session: v=2 fp=fce538f84d5d294c -->

**Date**: 2026-10-02
**Task**: 修复正文泄漏事实风险审校话术(手册未提供/无法计算)
**Branch**: `main`

### Summary

线上 version 44 第 17/25 段泄漏作者向审校话术根因:DeepWriterService.appendBriefSection 把 fact_risks 整块(含 suggestion 祈使句)注入正文素材区,模型改写成第三人称陈述。三层防线:R1 新增 ReaderViewRules 只抽 claim 注入「禁止写入正文的断言」块(suggestion 永不入素材区、解析失败绝不按原文兜底);R2 两条正文链路 system 追加共用 READER_RULES 读者视角铁律;R3 新增 MetaLeakCleaner 落库前句级清洗(深度链路置于 ⑥ 数值回查之前、多版本非仿写分支,仿写跳过;清洗致空回退原文)。检查代理另修过度删除/落库隐患,并把共享契约从 VersionService 对 DeepWriterService 的 FQN 反向依赖迁到基础层(ReaderViewRules,与 LayoutRules 同范式)。mvn test 571 绿。

### Main Changes

- 新增 src/main/java/com/sparkora/service/ReaderViewRules.java(READER_RULES + forbiddenClaimsBlock/factRiskClaims,纯静态)
- 新增 src/main/java/com/sparkora/service/MetaLeakCleaner.java(句级删除,零命中逐字原样返回,cleanForPersist 致空回退原文)
- DeepWriterService:user prompt 改注禁写断言块 + system 加铁律 + 落库前清洗;VersionService:删事实风险点整块注入 + system 加铁律 + 非仿写分支清洗
- 不动铁律 1~3 与「事实手册(数值唯一来源):」块头(既有逐字断言锁定);不回溯历史版本、不写生产库、无前端改动

### Git Commits

| Hash | Message |
|------|---------|
| `814457d` | fix(deep): 修复正文泄漏事实风险审校话术(手册未提供/无法计算) |
| `a677b07` | chore(task): archive 10-02-fix-meta-leak-in-article-body |

### Testing

- [OK] mvn -q -DskipTests compile 通过
- [OK] mvn test 571 tests,0 failures/errors/skipped

### Status

[OK] **Completed**

### Next Steps

- 真实 AI 链路端到端复测(新生成正文确认无元话语泄漏);历史 version 44/19/15 如需修复须人工确认后再清洗


## Session 38: C0 依赖升级预检 + axonhub 探针
<!-- trellis-session: v=2 fp=5ea465431ec6e44a -->

**Date**: 2026-10-03
**Task**: C0 依赖升级预检 + axonhub 探针
**Branch**: `main`

### Summary

Boot 3.3.4→4.0.1 + Spring AI 2.0.1 基座;572 用例全绿;探针定档 json_schema 退化/tool calling 支持/reasoning 支持/1024 维;Check 发现 CRITICAL(Reactor Netty 使 detect() 漂移破坏超时归因)→9 处 RestClient 改 .jdk() 并加回归锁定;更新 ai-rag/error-handling spec;归档 C0,父任务与 C1-C7 待规划

### Git Commits

| Hash | Message |
|------|---------|
| `e625709` | feat(deps): 升级 Boot 4.0.1 + 引入 Spring AI 2.0 基座并锁定 JDK HTTP 引擎 |
| `d232873` | docs(spec): 记录 axonhub 能力边界与 Boot4 RestClient 传输引擎约定 |
| `7f091b8` | chore(task): 建立 Spring AI 迁移任务树(父 + C0 探查 + C1-C7) |

### Status

[OK] **Completed**


## Session 39: C2 结构化输出契约化（schema 单一来源 + 自纠错）
<!-- trellis-session: v=2 fp=8a22c7b977974967 -->

**Date**: 2026-10-03
**Task**: C2 结构化输出契约化（schema 单一来源 + 自纠错）
**Branch**: `main`

### Summary

简报/澄清/子代理三链路改走 AiClient.structured（StructuredOutputValidationAdvisor 校验+错误回填自纠错），schema 由 DTO 类型单一派生、prompt {{schema}} 注入去字面量；新增 ClarifyPlanDto/SubAgentFactsDto；截断仍由 parseChat 独立抛异常；补 sanitize 围栏/控制字符回归与截断调用次数断言；604 用例全绿；更新 ai-rag spec 并归档 C2

### Main Changes

- AiClient 新增 structured(...)/jsonSchema(...)，保留旧 API
- BriefService/ClarifyService/SubAgentRunner 走 structured，schema 单一来源
- 3 个 prompt schema 字面量改 {{schema}} 占位(v1→v2)
- 新增 ClarifyPlanDto/SubAgentFactsDto；修 ClarifyService 不可变 list bug

### Git Commits

| Hash | Message |
|------|---------|
| `4e00853` | feat(C2): 结构化输出契约化 - schema 单一来源 + 响应侧自纠错 |
| `15af93c` | docs(spec): 记录 C2 结构化输出契约（schema 单一来源 + 自纠错） |
| `26f235e` | chore(task): archive 10-02-c2-structured-output |

### Testing

- [OK] mvn -q -DskipTests compile 通过
- [OK] mvn test = 604 用例全绿
- [OK] 三站点 grep chatJson=0；prompt schema 字面量 grep=0

### Status

[OK] **Completed**

### Next Steps

- C3 Tool Calling 替换手写 agent（探针：tool calling 支持）


## Session 40: C3 检索工具 ToolCallback 能力层
<!-- trellis-session: v=2 fp=64640066db13a495 -->

**Date**: 2026-10-03
**Task**: C3 检索工具 ToolCallback 能力层
**Branch**: `main`

### Summary

把 SearchTool 暴露为 Spring AI ToolCallback 的按需工厂（非全局 bean），保留确定性编排

### Main Changes

- 新增 SearchToolCallbacks 工厂 + 15 单测；WEB 经 WebSearchRouter；可用性门控；异常降级

### Git Commits

| Hash | Message |
|------|---------|
| `4828458` | feat(C3): 检索工具暴露为 Spring AI ToolCallback（按需工厂，非全局 bean） |

### Testing

- [OK] mvn test 619 全绿

### Status

[OK] **Completed**

### Next Steps

- C4 ChatMemory


## Session 41: C4 ChatMemory 装配问答多轮
<!-- trellis-session: v=2 fp=86f3694ae8661f46 -->

**Date**: 2026-10-03
**Task**: C4 ChatMemory 装配问答多轮
**Branch**: `main`

### Summary

Session summary was not supplied.

### Git Commits

| Hash | Message |
|------|---------|
| `85ccb1d` | chore(task): archive 10-02-c4-chat-memory |

### Status

[OK] **Completed**


## Session 42: C5 向量层 Spring AI 化（EmbeddingModel + PgVectorStore 推迟）
<!-- trellis-session: v=2 fp=de0934d1e275f8d1 -->

**Date**: 2026-10-03
**Task**: C5 向量层 Spring AI 化（EmbeddingModel + PgVectorStore 推迟）
**Branch**: `main`

### Summary

embedding 后端改 Spring AI EmbeddingModel（公共 API 不变）；PgVectorStore 表替换因无法表达 JOIN 活表语义推迟为 Scope B

### Main Changes

- EmbeddingClient 后端改 EmbeddingModel 并保留维度 fail-fast
- 更新 ai-rag spec 记录 C5 与 Scope B 推迟

### Git Commits

| Hash | Message |
|------|---------|
| `804dca0` | feat(C5): embedding 后端切换为 Spring AI EmbeddingModel（保留 raw pgvector 检索） |
| `356e4b7` | docs(spec): 记录 C5 EmbeddingModel 向量后端与 PgVectorStore Scope B 推迟 |
| `74c571ae91469f5ae0916c76a0af22f7a95152df` | chore(task): archive 10-02-c5-pgvector-store |

### Testing

- [OK] mvn test 623 全绿；真实 axonhub embedding 1024 维

### Status

[OK] **Completed**


## Session 43: C6 ImageModel：文生图接 Spring AI ImageModel，图生图保留自研
<!-- trellis-session: v=2 fp=5d329576d194a0e4 -->

**Date**: 2026-10-03
**Task**: C6 ImageModel：文生图接 Spring AI ImageModel，图生图保留自研
**Branch**: `main`

### Summary

文生图改走 Spring AI ImageModel（双构造+回退自建），edits 保留自研 multipart；新增 8 用例；631 全绿；spec 记录 C6

### Git Commits

| Hash | Message |
|------|---------|
| `d8ceaff` | feat(C6): 文生图接 Spring AI ImageModel，图生图 edits 保留自研 |
| `1eb3d7b` | docs(spec): 记录 C6 文生图 ImageModel 与图生图 edits 自研约定 |
| `c008b03` | chore(task): archive 10-02-c6-image-model |

### Status

[OK] **Completed**


## Session 44: C7 正文数值回查归一化(verifyNumbers 复用 ClaimSimilarity 签名)
<!-- trellis-session: v=2 fp=373811386018bd2e -->

**Date**: 2026-10-03
**Task**: C7 正文数值回查归一化(verifyNumbers 复用 ClaimSimilarity 签名)
**Branch**: `main`

### Summary

Session summary was not supplied.

### Git Commits

| Hash | Message |
|------|---------|
| `5463d47` | feat(C7): 正文数值回查改数值签名归一化比对,消除 1200/12000 漏报 |
| `bce9d2b` | docs(spec): 记录 C7 正文数值回查归一化契约 |

### Status

[OK] **Completed**


## Session 45: 父任务集成复核：Spring AI 迁移 C0-C7 全部完成并归档
<!-- trellis-session: v=2 fp=58e8dcfc8b38b2d2 -->

**Date**: 2026-10-03
**Task**: 父任务集成复核：Spring AI 迁移 C0-C7 全部完成并归档
**Branch**: `main`

### Summary

Session summary was not supplied.

### Git Commits

| Hash | Message |
|------|---------|
| `79d55ed` | chore(task): archive 10-02-spring-ai-adoption |

### Status

[OK] **Completed**


## Session 46: E1 向量层迁 PgVectorStore 单表(阶段A对拍一致)+Boot4 Flyway回归修复
<!-- trellis-session: v=2 fp=9526c98c2e08f719 -->

**Date**: 2026-10-03
**Task**: E1 向量层迁 PgVectorStore 单表(阶段A对拍一致)+Boot4 Flyway回归修复
**Branch**: `main`

### Summary

Session summary was not supplied.

### Git Commits

| Hash | Message |
|------|---------|
| `4a24f6a` | feat(E1): 向量检索迁移到 Spring AI PgVectorStore 单表（metadata.domain 过滤） |
| `4443dae` | docs(spec): 记录 E1 PgVectorStore 单表迁移与 Boot4 Flyway 回归修复 |
| `911dd34` | chore(task): 建立 PgVectorStore 迁移任务树(父 + E1归档 + E2-E5) |

### Status

[OK] **Completed**


## Session 47: E2 切块滑动重叠 + 全库重嵌
<!-- trellis-session: v=2 fp=e4fafa0973c70b66 -->

**Date**: 2026-10-03
**Task**: E2 切块滑动重叠 + 全库重嵌
**Branch**: `main`

### Summary

Session summary was not supplied.

### Git Commits

| Hash | Message |
|------|---------|
| `d50acd3` | feat(E2): 切块滑动重叠 + 全库重嵌（向后兼容 6 参重载） |
| `7a8aab9` | docs(spec): 记录 E2 切块滑动重叠与阶段 B 重嵌契约 |
| `10c3566` | chore(task): archive 10-03-e2-chunk-overlap |

### Status

[OK] **Completed**


## Session 48: E3 KB 数据模型规范化：受控 domain + 来源/标签/生效期
<!-- trellis-session: v=2 fp=27df62726291796c -->

**Date**: 2026-10-03
**Task**: E3 KB 数据模型规范化：受控 domain + 来源/标签/生效期
**Branch**: `main`

### Summary

Session summary was not supplied.

### Git Commits

| Hash | Message |
|------|---------|
| `e1bf616` | docs(spec): 记录 E3 KB 数据模型规范化契约（受控 domain + 来源/标签/生效期） |

### Status

[OK] **Completed**


## Session 49: E4 命名规范化 car_doc→car_chunk 完成
<!-- trellis-session: v=2 fp=a34c09cdb1e21711 -->

**Date**: 2026-10-03
**Task**: E4 命名规范化 car_doc→car_chunk 完成
**Branch**: `main`

### Summary

Session summary was not supplied.

### Git Commits

| Hash | Message |
|------|---------|
| `9f819c2` | feat(E4): 命名规范化 sparkora_car_doc → sparkora_car_chunk（块语义） |
| `2a39e26` | docs(spec): 记录 E4 car_doc→块语义命名规范化契约 |

### Status

[OK] **Completed**
