# 技术设计:检索设置页 + 取消快速模式 + 知识库可暂停

> 依据 prd.md;现状证据均已在规划阶段核实(见 PRD Background)。

## 1. 总体结构

```
前端                          后端
┌─────────────────┐   ┌──────────────────────────────┐
│ /settings 设置页  │──▶│ SettingController (GET/PUT)   │
│ (el-switch x2)   │   │   └ SettingService(缓存读)      │
├─────────────────┤   ├──────────────────────────────┤
│ 创建项目(无模式选择)│   │ DeepController(唯一生成入口)   │
│ StepClarify/…     │──▶│  ClarifyService / DeepResearch │
│ (深度流程步骤页)    │   │  SubAgentRunner ◀─ SettingService│
└─────────────────┘   │    └ SearchTool(Searxng/Tavily)  │
                      └──────────────────────────────┘
```

- 设置为**运行时读取**:`SettingService` 内存缓存单行设置表,写时刷新缓存;生成链路不再读 `AI_RAG_KB_ENABLED`(.env 项保留但深度链路不再消费,spec 标注废弃)。
- 生成收敛:项目创建不再落 FAST genMode;`DeepController` 成为唯一简报/版本生成入口。

## 2. 数据与契约

### 2.1 新表 `sparkora_setting`(幂等)

```sql
CREATE TABLE IF NOT EXISTS sparkora_setting (
  id           BIGSERIAL PRIMARY KEY,
  kb_enabled   BOOLEAN      NOT NULL DEFAULT FALSE,   -- 内部知识库(默认停用,用户决策)
  web_search_enabled BOOLEAN NOT NULL DEFAULT TRUE,   -- 外部搜索
  updated_by   BIGINT,
  updated_at   TIMESTAMP    NOT NULL DEFAULT now()
);
```

- 单行模式:首次读取时 `INSERT ... ON CONFLICT DO NOTHING` 不存在则插入默认行(避免「无行=默认」的歧义)。
- 三处同步:`schema.sql` + `SettingEntity`/`SettingMapper` + spec §设置契约。
- 无逻辑删除字段需求(单行常驻),`deleted` 列按 MyBatis-Plus 全局配置处理(建表含 deleted DEFAULT FALSE,entity 标注)。

### 2.2 API 契约(全部 `R<T>`)

| 接口 | 方法/角色 | 请求/响应 |
|---|---|---|
| `/api/settings` | GET · ADMIN/EDITOR | `data: {kbEnabled, webSearchEnabled, updatedAt}` |
| `/api/settings` | PUT · ADMIN | `@Valid SettingUpdateDto {kbEnabled?, webSearchEnabled?}`;返回更新后全量 |

### 2.3 rag_status 增补 `DISABLED`

- 枚举扩为五态:OK / FAILED / LOW_CONFIDENCE / NO_KNOWLEDGE / **DISABLED**。
- 判定:`kbEnabled=false` → 深度链路任何本地检索不发起,产物 rag_status=DISABLED(VARCHAR(20) 容纳,无需 schema 变更)。
- 前端 `CitationList`/状态标签区:DISABLED 显示「知识库已停用(全局设置)」,灰色。

## 3. 关键改动点

### 3.1 后端

1. `SettingService`(新):读缓存 + `update` 刷缓存;`isKbEnabled()` / `isWebSearchEnabled()` 供生成链路调用。
2. `SubAgentRunner`:装配资料工具前查设置——
   - `kbEnabled=true` → 维持现状(KB 检索 + WEB 搜索);
   - `kbEnabled=false` → 仅 `SearchTool`;`webSearchEnabled=false` → 无资料工具,prompt 注入「未检索任何外部资料,factRisks 必须标注」。
3. `DeepResearchService` / `DeepWriterService`:rag_status 判定接入 DISABLED 分支;FACTSHEET 来源可信度文案随开关调整。
4. FAST 链路下线:
   - `BriefService.generate` FAST 分支、`VersionService` 快速生成分支**不删除代码**(存量项目「查看」仍走实体读取),仅**入口封死**:`ArticleProjectController` / `BriefController` 中 FAST 生成路由移除或 410;`genMode` 落库恒为 DEEP。
   - 存量 FAST 项目重新生成 → 深度流程(状态机沿用,`claimGenerating` 原子抢占复用)。
5. `ArticleProjectService.create`:丢弃请求中的 genMode(恒 DEEP);DTO 字段保留但忽略,前端同步移除选择器。

### 3.2 前端

1. 路由 `/settings`(ADMIN/EDITOR 可见,WRITE 仅 ADMIN);`el-switch` ×2 + 说明文案;保存走 PUT。
2. 创建项目页:移除模式单选(genMode);车型关联保留为可选折叠区(锚点语义,非门禁)。
3. `StepBrief.vue` / 版本卡片:DISABLED 状态样式与文案;深度流程步骤页保持现有 S9 交互。

### 3.3 与现有逻辑的边界

- **09-03-rag-mandatory-gate 语义被取代**:「必查」改为「按设置查」;其 FAILED/LOW_CONFIDENCE 降级可见逻辑在深度链路保留(开关开启时行为不变)。归档时在任务 notes 标注 supersede。
- **仿写任务(09-09-article-imitation,planning)**:IMITATION 独立 genSource,不查知识库的决策不变,不受本任务影响;但其「复用 VersionService 生成」若依赖 FAST 分支,实现时改走其自身 PRD 已定的仿写链路——两任务实现顺序上本任务先行,仿写任务基于收敛后的链路落地。
- SEARXNG→Tavily 降级逻辑(`SearxngSearchTool`/`TavilySearchTool`)不动。

## 4. 权衡记录

| 选项 | 取舍 |
|---|---|
| 设置存 .env vs DB | **DB**:用户要求页面控制、运行时可变;.env 改动需重启,不可行 |
| FAST 代码删除 vs 入口封死 | **封死不删**:存量 FAST 产物查看链路共用实体/查询;git revert 单点回滚更容易 |
| 双关闭(kb+web 都关)硬阻断 vs 继续 | **继续生成**(沿用 mandatory-gate 的「不硬阻断」决策):AI 自身知识兜底,factRisks 标注;创作自由 > 资料完备 |
| genMode 字段删除 vs 恒写 DEEP | **恒写 DEEP**:schema 不动,存量行不迁移,回滚成本低 |

## 5. 回滚形态

- 单分支交付;回滚 = git revert(表 `sparkora_setting` 多余无害,INSERT IF NOT EXISTS 幂等)。
- 设置行回滚后无人消费,不产生行为差异。

## 6. 风险

- 取消快速模式后**生成时长/token 成本显著上升**——用户已确认;深度流程现有超时配置(`WENYAN_MCP_PUBLISH_TIMEOUT_MS` 之外的 DEEP 超时)需在联调验证中观察。
- 存量 FAST 项目较多时,用户重新生成体验突变(澄清表单首次出现)——前端需在 StepBrief 给出一次性的「生成流程已升级」提示。
- `sparkora_setting` 单行并发写:仅 ADMIN 可写,竞态影响可忽略(后写胜出)。