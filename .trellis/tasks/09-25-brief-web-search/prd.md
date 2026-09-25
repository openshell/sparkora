# 评估并优化简报外部搜索

## Goal

把简报深度研究的外部搜索从“代码已接入、运行时不可控”升级为来源可见、策略可配置、证据可追溯的机制，优先改善时效信息覆盖和事实来源可信度；API 密钥始终只由部署环境管理。

## Background

- 当前唯一可达的深度链路是 `/deep/clarify → /deep/clarify-answer → /deep/run → /deep/brief`，快速生成入口已返回 410；研究完成自动生成简报，见 `docs/spec/brief-generation.md:7,112`。
- 当前外部搜索顺序硬编码为 `SEARXNG → Tavily`，首个 provider 返回非空即停止；实现见 `src/main/java/com/sparkora/deep/service/SubAgentRunner.java:69-86`。
- Tavily 类和 HTTP 契约已真实存在，不是占位实现，见 `src/main/java/com/sparkora/deep/tool/TavilySearchTool.java:15-84`；但仅使用 `search_depth=basic`，只读取 title/url/content 摘要，不使用时间范围、原始正文、回答字段或评分，见同文件 `:58-84`。
- SearxNG 存在非空默认值，未显式配置时仍会尝试请求，见 `src/main/java/com/sparkora/deep/tool/SearxngSearchTool.java:30-36`。
- 2026-09-04 的历史实测记录 SearxNG 上游引擎曾全部 Suspended/CAPTCHA；9 月 15 日历史任务明确要求保持 SearxNG 优先，见 `.trellis/tasks/archive/2026-09/09-04-deep-research/research/current-state.md:27-36` 与 `.trellis/tasks/archive/2026-09/09-15-brief-research-flow-fixes/prd.md:57-61`。
- 当前每问题只执行一个 WEB query；`webQuotaPerAgent=max(1, 8/n)` 实际是单 provider 返回条数，不是全程调用/结果预算，见 `src/main/java/com/sparkora/deep/service/DeepResearchService.java:140-153`。
- 搜索结果不按 URL 去重、不做相关性重排或新鲜度治理；事实来源由 LLM 自由输出且没有 sourceId 后验校验，见 `src/main/java/com/sparkora/deep/service/SubAgentRunner.java:87-140`。
- 现有设置页仅支持管理员调整知识库与外部搜索总开关，无 provider 优先级配置，见 `frontend/src/views/SettingsView.vue:12-40` 与 `src/main/java/com/sparkora/service/SettingService.java:27-87`。
- 历史证据显示生产运行曾出现 `webCalls` 统计与单一 WEB 事实 0.4 置信案例；本轮未读取或输出 `.env` 密钥，未发起真实 Tavily 付费请求。

## User Outcomes

- 系统能够清楚显示一次研究实际使用的搜索策略、命中的来源及降级情况。
- 管理员能够调整外部搜索策略，而普通编辑和查看者不能绕过权限或接触密钥。
- 外部搜索产出的事实能够回溯到本次真实搜索命中，不把模型生成的 URL 当作可信证据。
- 对时效性主题能利用用户锁定的澄清答案构造更具体的查询，并显式暴露覆盖缺口。

## Requirements

- R1 外部搜索机制必须通过独立路由/编排组件实现，不得继续在 `SubAgentRunner` 中硬编码 provider 顺序。
- R2 至少支持 `TAVILY_FIRST` 与 `SEARXNG_FIRST` 两种策略；`BOTH` 为延期能力，不得在 MVP 中让付费 provider 无条件重复调用。
- R3 MVP 默认策略为 `TAVILY_FIRST`（Tavily 优先、SearxNG 兜底），由 ADMIN 在系统设置中全局调整；该反转显式推翻 2026-09-15 的「SEARXNG 优先」决策，依据是 Tavily 已配置却从未被调用、SearxNG 上游曾全部不可用。
- R4 外部搜索总开关必须继续同时受部署级 `SEARCH_WEB_ENABLED` 和运行时 `webSearchEnabled` 控制。
- R5 provider 未配置时必须跳过；异常、超时、无有效结果时允许按有效策略降级。
- R6 每次研究启动时解析一次有效策略和开关快照（全局配置层，非项目级/用户级），后续全局设置变化不改变已启动批次。
- R7 WEB 查询必须包含项目主题与已锁定的澄清答案；未锁定答案不得进入查询。
- R8 WEB 命中必须具有有效 HTTP/HTTPS URL，经过规范化、去重和数量限制后进入子代理。
- R9 每个 WEB 命中必须获得稳定 sourceId；事实只能引用本次输入的 sourceId，URL/provider 不匹配时拒绝该事实或转为 gap。
- R10 搜索运行记录必须保存或可观测到 briefId、agentId、策略、query、provider、结果数、耗时、fallback 原因；不得记录 API 密钥。
- R11 工具健康继续遵守 `DISABLED > UNCONFIGURED > FAILED > OK`，且 `available()` 只代表配置就绪，不得加入失败闩锁。
- R12 不得把 API 密钥、原始敏感环境变量或含密钥的异常文本写入数据库、响应或日志。
- R13 保持现有 `/deep/*` 主接口兼容；新增配置与状态字段应增量兼容。
- R14 同步维护 `schema.sql`、实体/DTO/Mapper、设置与研究进度前端、`.env.example`、权威规格和 Trellis 规范。

## Acceptance Criteria

- [ ] AC-01 当前实现审计结论及真实运行验证边界已记录；默认策略确认为 `TAVILY_FIRST`，配置范围为 ADMIN 全局设置。
- [ ] AC-02 有效策略为 Tavily 优先且 Tavily 返回有效结果时，SearxNG 调用次数为 0。
- [ ] AC-03 首选 provider 异常、超时、返回空结果或结果全部无有效 URL 时，只按策略调用一次后备 provider。
- [ ] AC-04 任一部署级或运行时 WEB 开关关闭时不发起 Tavily/SearxNG 请求。
- [ ] AC-05 同一批次内所有子代理使用同一策略与开关快照；研究启动后修改设置不改变该批次。
- [ ] AC-06 WEB query 包含项目主题和已锁定澄清答案，不包含未选澄清项。
- [ ] AC-07 WEB 命中经过协议校验、URL 规范化与去重；重复 URL 只保留一条。
- [ ] AC-08 每条进入 LLM 的 WEB 命中具有稳定 sourceId；未知 sourceId、URL 或 provider 不得进入事实手册。
- [ ] AC-09 两路搜索均不可用时，研究笔记明确记录 gaps，不生成无来源事实，并允许其他可用资料源继续生成简报。
- [ ] AC-10 状态响应能够区分配置态、健康态、实际调用结果和降级原因；配置就绪不得显示为已验证可用。
- [ ] AC-11 日志与持久化信息包含必要的 briefId/agentId/provider/resultCount/latencyMs/fallbackReason，且不泄漏密钥。
- [ ] AC-12 现有总开关、角色权限和 `toolHealth` 状态值保持兼容；只有 ADMIN 能修改搜索策略配置。
- [ ] AC-13 表结构与 API DTO 使用 `@Valid`、统一响应和既有中文错误契约；数据库列使用幂等迁移，既有单行设置数据保持兼容。
- [ ] AC-14 单元与 HTTP 契约测试覆盖策略解析、首源命中不调用后备源、失败降级、开关门控、URL 处理、sourceId 校验和密钥脱敏。
- [ ] AC-15 `mvn -q -DskipTests compile` 与相关测试通过；如修改前端，`npm run build` 通过。
- [ ] AC-16 `.env.example`、`docs/spec/brief-generation.md`、`docs/spec/settings.md`、前端文案及 Trellis 规范与最终行为一致。

## Out of Scope

- 本轮不实现；实现必须等待最新规划摘要获得用户明确批准并执行 `task.py start`。
- 不在本任务内存储用户填写的 Tavily API Key，不接入 QA/知识浏览链路。
- 不默认引入 Crawl4AI 正文抓取、语义 reranker、付费双源聚合或完整指标平台。
- 不把搜索策略扩展为普通用户个人偏好或每个项目单独一套复杂设置。

## Resolved Decisions

- 默认策略：`TAVILY_FIRST`（Tavily 优先，SearxNG 兜底）。
- 配置范围：ADMIN 在系统设置中调整全局策略；不做项目级或用户级覆盖。
- 密钥边界：Tavily API Key 仍只由部署环境（`.env`）提供，不入库、不下发前端。
