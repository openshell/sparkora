# 设计:六阶段深度生成流程(deep-generation)

## 1. 总体编排

```
用户选「深度模式」
   │
   ▼
POST /deep/clarify ──> ①理解(LLM,1次) ──> 研究计划 JSON 落库 ──> ②澄清问题生成(LLM,1次)
        └─ 返回 questions[] ;项目状态 → CLARIFYING
用户填表单 ──> POST /deep/clarify-answer ──> 需求 JSON 锁定落库
   ▼
POST /deep/run ──> ③研究(并行子代理,虚拟线程) ──> ④汇总(主代理,1次) ──> 事实手册落库
        状态 → RESEARCHING → (完成) 
   ▼
POST /deep/generate ──> ⑤写作(风格+手册,1~2次) ──> ⑥数值回查(正则,0次LLM) ──> 版本落库
        状态 → BRIEF_READY(深度模式产物复用既有 brief/version 表)
```

- 三段式 API 使「等用户填表单」天然异步;每阶段产物落库 → 断点从任意阶段续跑。
- 与既有状态机的关系:CLARIFYING/RESEARCHING 挂在 brief 生成前(项目层新字段 `gen_mode` + brief 层状态扩展),完成后回归既有 BRIEF_READY→…主链路,S5/S6 语义零改动。

## 2. 数据结构(schema 追加,幂等)

```sql
-- 简报表扩展(复用 sparkora_article_brief,加列)
ALTER TABLE … ADD COLUMN IF NOT EXISTS gen_mode VARCHAR(10) DEFAULT 'FAST';    -- FAST/DEEP
ALTER TABLE … ADD COLUMN IF NOT EXISTS clarify_questions TEXT;   -- JSON[{q,type,options[],required}]
ALTER TABLE … ADD COLUMN IF NOT EXISTS clarify_answers TEXT;     -- JSON[{q,a}]  (锁定需求)
ALTER TABLE … ADD COLUMN IF NOT EXISTS research_plan TEXT;       -- JSON {keyQuestions[],dataNeeds[],hypotheses[],toolHints[]}
ALTER TABLE … ADD COLUMN IF NOT EXISTS research_notes TEXT;      -- JSON[子代理笔记]
ALTER TABLE … ADD COLUMN IF NOT EXISTS fact_sheet TEXT;          -- JSON {entries[{key,value,source{type,url,docId?},confidence}],gaps[],warnings[]}
```

- 不建新表:深度模式产物是 brief 的前体,放同表加列最小侵入;幂等 ALTER 走启动 SQL(`schema.sql` 加 `DO $$ BEGIN … EXCEPTION WHEN duplicate_column THEN NULL $$` 或按 PG 版本用 `ADD COLUMN IF NOT EXISTS`)。

## 3. 研究笔记与事实手册结构(契约)

```json
// 研究笔记(每个子代理一份)
{ "question": "海狮08 各版本价格与配置梯度",
  "facts": [
    {"claim": "900km 5座尊荣型 239,900", "value": "239900",
     "source": {"type": "KB", "docId": 411, "modelName": "海狮08EV", "score": 0.82}, "confidence": 0.9},
    {"claim": "竞品 Model Y 当前起售价 …", "source": {"type": "WEB", "url": "…", "tool": "TAVILY"}, "confidence": 0.5}
  ],
  "gaps": ["二手残值数据本地知识库与外部均未获得"] }

// 事实手册(汇总产物,写作唯一数值来源)
{ "entries": [ {"key": "海狮08EV 起售价", "value": "239,900", "source": {…}, "confidence": 0.9} ],
  "uncovered": ["残值率"], "warnings": ["竞品价格仅单一 WEB 源,已标待核实"] }
```

- 置信规则:KB 数值 0.9;WEB 交叉 ≥2 源 0.7;单 WEB 0.4 且进 warnings;冲突条目降 0.3 并双源并列记录。

## 4. SearchTool 抽象(B 阶段工具层)

```java
public interface SearchTool {
    String name();                                  // KB / SEARXNG / TAVILY
    List<SearchHit> search(String query, int maxResults);   // SearchHit{title,url,snippet,source}
    boolean available();                            // SEARXNG 引擎异常→false(降级)
}
```

- `KnowledgeSearchTool`:委托 `CarRagService` 统一检索(阶段 A);source=KB 记 docId/modelName。
- `SearxngSearchTool`:`GET {SEARXNG_BASE_URL}/search?q=&format=json`(**已实测当前引擎全挂,results=0**——实现层容忍:超时/空结果→标记该次不可用,不重试轰炸;用户侧后续修 SEARXNG engines 配置即可生效)。
- `TavilySearchTool`:`POST https://api.tavily.com/search` {api_key(TAVILY_API_KEY), query, max_results, search_depth:"basic"};RestClient 超时 15s,重试 1 次。
- 工具选择:研究计划 toolHints 指定;默认 KB 必用 + WEB(若 `SEARCH_WEB_ENABLED`)按问题类型选用(竞品/政策→WEB,本车参数→KB 优先)。

## 5. 服务与 API(`com.sparkora.deep`)

| 组件 | 职责 |
|---|---|
| `DeepResearchService` | 三段式编排:clarify/run/generate;虚拟线程池;超时/失败聚合 |
| `ClarifyService` | 研究计划与澄清问题生成(prompt 输出 JSON);锁定需求 |
| `SubAgentRunner` | 单子代理执行:prompt(问题+工具说明)→ 按需调工具 → 笔记 JSON 解析(容错:解析失败重试 1 次→缺口) |
| `FactSheetService` | 笔记合并:按 claim 去重/冲突检测/value 归一;置信规则;手册生成(LLM 辅助归类,数值以笔记为准) |
| `NumericVerifier` | 正则抽正文数值(万/元/%/km/kWh 等单位)→ 手册比对 → factRisks |
| `DeepController` | POST /api/projects/{id}/deep/clarify|clarify-answer|run|generate;GET /api/projects/{id}/deep/status |

- prompt 资产:理解/澄清/子代理/汇总/写作五个 system prompt,均为 JSON 输出约束(复用 `AiClient.chatJson`)。

## 6. 前端(含研究过程可视化)

### 6.1 交互流(StepBrief.vue 内深度模式分支)

```
生成模式切换(快速/深度)
  → 深度:主按钮变「生成研究计划」
  → POST /deep/clarify 返回 {plan, questions[]}
     · 展示「研究计划」卡片(可折叠):关键问题/数据需求/工具提示
     · 展示澄清问题表单(el-form:input/radio/必答标记)→ 提交锁定
  → POST /deep/clarify-answer 后按钮变「开始研究」
  → POST /deep/run(202 异步)→ 前端轮询 GET /deep/status(2s 间隔)
     · 「研究进度」面板实时更新(见 6.2)→ 完成后展示「事实手册摘要」
  → 「生成正文」走既有版本入口(gen_mode=DEEP)
```

### 6.2 「研究计划与研究过程」可视化设计(核心诉求)

**信息架构:研究过程不藏在日志里,作为 Step1 的一等公民区块**。深度模式下 Step1 页面结构:

```
┌─ Step 1 · 生成创作简报 ── 模式:深度 [标签] ────────────────┐
│ [研究计划卡](折叠面板,默认展开)                              │
│   研究问题 N 条(编号列表) · 数据需求 · 预计工具 KB/WEB        │
│ [澄清表单](CLARIFYING 时渲染;填完锁定,只读回显)             │
│ [研究进度面板](RESEARCHING 时渲染)                           │
│   ├─ 总进度条:子代理完成数 / 总数                            │
│   ├─ 子代理卡片 ×N:研究问题 | 状态标签(进行中/已完成/失败)    │
│   │   已完成→展开显示:命中 X 条(其中 WEB Y 条)· 缺口 Z 条     │
│   └─ 工具健康行:KB ✓ · SEARXNG ✗(引擎不可用,已降级) · Tavily ✓│
│ [事实手册摘要](研究完成时):条目数 / 高置信数 / 待核实数 /      │
│   WEB 来源占比;点开抽屉看完整条目(key/value/来源/置信度)      │
│ [知识库状态标签] 沿用 S6.1(已引用/低置信/失败/未引用)          │
└─────────────────────────────────────────────────────────┘
```

**感知设计原则**:
1. **过程可观测**:子代理状态由后端实时落库(research_notes 按 agentId 逐条更新 status),前端 2s 轮询 `GET /deep/status` 拿增量,不引入 WebSocket(复杂度不成比例,轮询量级 ~10 次足够)。
2. **结果可追溯**:事实手册摘要 → 点击展开完整条目抽屉,每条数值带来源徽标(KB 蓝/WEB 紫 + 域名)与置信度条;正文数值与手册一一对应,用户能回答「这个数字哪来的」。
3. **失败透明**:子代理失败/工具降级不静默——进度面板显示失败标签与原因摘要,gaps 计入手册;用户知道哪些信息是查到的、哪些是 AI 定性表述的。
4. **移动端**:进度面板纵向单列,子代理卡片全宽;抽屉改全屏;触控目标 ≥44px。

### 6.3 后端支撑(为可视化新增/调整)

- `GET /api/projects/{id}/deep/status`:返回 {stage, agents:[{id, question, status, startedAt, finishedAt, factCount, webCount, error}], toolHealth:{KB, SEARXNG, TAVILY}, factSheetSummary{total, highConfidence, pending, webSources}}。
- `SubAgentRunner` 执行时**逐 agent 状态落库**(research_notes 中每条加 status 字段:PENDING/RUNNING/DONE/FAILED),而非仅存最终笔记——轮询的数据源。
- 阶段状态映射前端:`CLARIFYING`(橙)/`RESEARCHING`(蓝)/完成回 BRIEF_READY。

### 6.4 组件拆分(新增,避免 StepBrief 膨胀)

- `DeepPlanCard.vue`:研究计划展示(问题/数据需求/工具提示)。
- `ClarifyForm.vue`:澄清问题表单(生成/回显只读两态)。
- `ResearchProgress.vue`:子代理进度面板 + 工具健康行(轮询逻辑内聚)。
- `FactSheetSummary.vue`:手册摘要 + 完整条目抽屉。
- 均放 `frontend/src/views/project/deep/`,StepBrief 按状态条件渲染;样式沿用既有 panel/tag 体系,不引新组件库。

## 7. 配置

| 变量 | 默认 | 说明 |
|---|---|---|
| `SEARCH_WEB_ENABLED` | `true` | WEB 搜索总开关(SEARXNG+Tavily) |
| `TAVILY_API_KEY` | 空 | Tavily 密钥(.env,用户提供) |
| `DEEP_RESEARCH_TIMEOUT_MS` | `120000` | 单子代理超时 |
| `DEEP_MAX_AGENTS` | `4` | 子代理数上限 |

## 8. 测试设计

- `ClarifyServiceTest`:研究计划 JSON 解析容错/问题生成。
- `SubAgentRunnerTest`:笔记解析(合法/畸形 JSON)/工具降级(SEARXNG 空→Tavily)/超时。
- `FactSheetServiceTest`:冲突检测/置信规则/缺口归类。
- `NumericVerifierTest`:数值抽取与手册比对(命中/未命中/单位变体)。
- 编排层 Fake AiClient+Fake tools 全覆盖,不连真实服务。

## 9. 回滚

- 深度模式为独立入口:关掉前端入口 + `SEARCH_WEB_ENABLED=false` 即回快速模式;新列可空,不破坏既有链路;单提交 revert 兜底。