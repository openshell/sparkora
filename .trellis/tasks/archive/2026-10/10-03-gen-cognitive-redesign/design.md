# 设计:文章生成认知层重构

> 关联 PRD:`prd.md`。本文只写技术设计(边界/契约/数据流/权衡/迁移),不写执行清单(见 `implement.md`)。

## 1. 设计原则

1. **不确定性三分离**:Intent(用户意图)/ World(外部事实)/ Domain(领域约束)用三套机制解决,绝不合流。当前 `keyQuestions` 把 Intent 与 World 混为一维,是本轮首要修复点。
2. **结构先于内容**:先定论证骨架与证据需求,再填事实。
3. **信息充分性为收敛条件**:澄清结束的条件是「够了」,不是「问完了」。
4. **显式结构化契约**:阶段产物是结构化 JSON(TaskBrief / WritingBlueprint),作为下游唯一接口,而非拼接文本。
5. **可评估**:产物带可计算质量信号,支持回归。
6. **复用工程骨架**:状态机写权仍归 `ProjectStatusService`,`start*`/`@Async run*`+轮询范式、AI 截断提额重试、降级可见、元话语三层防线全部沿用。

## 2. 目标架构与边界

```mermaid
flowchart TD
    subgraph 认知层(本次重构)
      A["意图澄清 ClarifyConversationService<br/>多轮对话/slot-gap<br/>充分性收敛"] --> TB["TaskBrief<br/>结构化意图契约"]
      TB --> RP["研究规划 ResearchPlannerService<br/>纯事实子问题+假设+hints"]
      RP --> RES["既有并行研究 SubAgentRunner/FactSheetService"]
      RES --> BP["蓝图生成 BlueprintService<br/>thesis+论证结构+evidenceMap+质量信号"]
      BP --> GATE{"人工评审门<br/>用户确认/编辑"}
      GATE --> W["写作 WriterService<br/>按 evidenceMap 硬约束取用"]
    end
    subgraph 复用
      ST["ProjectStatusService"]
      AI["AiClient.structured / chat"]
      TOOL["SearchTool KB/WEB"]
    end
```

**新增组件**(`com.sparkora.deep.service` 或新子包 `com.sparkora.deep.cognitive`):

- `ClarifyConversationService` — 意图澄清多轮对话状态机 + 充分性判定。
- `TaskBriefAssembler` — 汇总澄清会话为结构化 TaskBrief。
- `ResearchPlannerService` — 基于 TaskBrief 产出纯事实研究规划(从 `ClarifyService` 剥离)。
- `BlueprintService` — 基于 TaskBrief + 研究规划 + fact_sheet 产出写作蓝图(替代 `BriefService.generateFromFactSheet` 的认知职责)。

**保留/改造**:

- `ClarifyService` 保留状态机/异步/并发骨架,认知职责迁移;`ClarifyPlanDto` 拆分。
- `BriefService` 保留状态机委托与事务边界,`generateFromFactSheet` 改为委托 `BlueprintService`;或直接由 `BlueprintService` 承担并让 `BriefService` 退役。
- `DeepWriterService.write` 改为按蓝图取用。

## 3. 数据模型变更

用户已授权**清空现有项目数据**,故不写数据回填,只做结构收敛。新增 Flyway `V10__cognitive_layer.sql`:

- `sparkora_article_brief` 增列(JSON String,沿用现有 MyBatis-Plus 约定):
  - `clarify_session TEXT` — 多轮澄清会话 `{slots:[{id,label,question,answer,source(USER/PICKED/DEFAULT/INFERRED),confidence,done}],turns:[{idx,questionId,question,answer,at}],status(ASKING/CONVERGED/ABORTED)}`。
  - `task_brief TEXT` — 结构化意图契约 `{purpose,audience,tone,angles[],mustCover[],mustAvoid[],successCriteria,lengthTarget,slotMeta[]}`。
  - `writing_blueprint TEXT` — 写作蓝图(见 §4.3)。
  - `blueprint_status VARCHAR(20)` — `GENERATING/REVIEWING/CONFIRMED`(人工评审门;`plan_status` 继续表示研究计划态)。
  - `blueprint_quality TEXT` — 质量信号 `{argumentDensity,evidenceCoverage,gapCount,taskBriefConsistency}`(也可内联进 blueprint,列化便于查询)。
- `clarify_questions` / `clarify_answers` / `research_plan` 语义收敛:
  - `clarify_questions`/`clarify_answers` 由 `clarify_session` 取代(可删列或保留空置;倾向**删列**以彻底消除混装)。
  - `research_plan` 保留但内容收敛为**纯事实规划** `{keyQuestions[](仅事实),dataNeeds[],hypotheses[],toolHints[]}`。
- `sparkora_article_brief` 旧 `outline`/`coreViewpoints`/`titleCandidates`/`audienceRefine`/`factRisks` 由蓝图取代(可删列;写作仍可取 blueprint 内字段)。
- 表 `sparkora_clarify_turn`(可选关系化):若要支持逐轮追加/断点续跑与并发,建议关系表而非单 JSON;若图省事可先 JSON。**设计倾向**:先 JSON(与现有 brief JSON 范式一致),若对话轮次多再评估关系表。

## 4. 契约设计

### 4.1 TaskBrief(意图契约)

```
{
  "purpose": "让读者了解…",
  "audience": {"value":"…","source":"USER","confidence":0.9},
  "tone": {"value":"…","source":"PICKED","confidence":0.8},
  "angles": [{"value":"…","source":"INFERRED","confidence":0.6}],
  "mustCover": [{"value":"…","source":"USER","confidence":1.0}],
  "mustAvoid": [{"value":"…","source":"USER","confidence":1.0}],
  "successCriteria": "…",
  "lengthTarget": 1500,
  "slotMeta": [{"id":"purpose","filled":true,"source":"USER"}]
}
```

- 每槽位 `source` ∈ `USER|PICKED|DEFAULT|INFERRED`,下游据 `confidence` 决定是否需人工确认。
- 研究规划与蓝图**只读 TaskBrief**,不再直接读 `clarify_answers` 拼接文本。

### 4.2 澄清会话接口(多轮)

`ClarifyConversationService` 提供:

- `start(projectId)` — 落/复用会话,返回首题(或首个缺口批次)。
- `answer(projectId, turnId, answer)` — 记录答案,触发**下一个最佳问题或收敛判定**;返回 `{nextQuestion?|converged:true, taskBrief?}`。
- `converge()` — 强制收敛并产出 TaskBrief。
- `abort()/reset()` — 放弃/重来。

收敛算法(sufficiency):LLM 判定 + 确定性兜底——槽位缺口清单中「必要槽位」(purpose/audience/mustCover)未填则必问;全部必要槽位已填且 LLM 判定「足以产出高质量初稿」则收敛。问题选择:对每个缺口槽位估计**信息增益×对成文影响**,取最高者作为下一问(`answer` 一轮一问或一问一小批)。保留现有确定性兜底思路(如 `ensureBackgroundQuestion` 的对应物迁移到研究规划)。

并发/自愈:沿用 `uq_brief_planning` 式部分唯一索引思路,澄清会话加 `status='ASKING'` 部分唯一索引保证同项目至多一个进行中会话。

### 4.3 WritingBlueprint(写作蓝图)

```
{
  "thesis": "中心论点",
  "audienceAngle": "面向…,切入…",
  "argumentStructure": [
    {"sectionId":"S1","heading":"…","role":"HOOK|CONTEXT|ARGUMENT|EVIDENCE|COUNTER|CONCLUSION",
     "claim":"分论点","narrativeIntent":"…","argumentRelation":"支撑 thesis / 回应 S2"}
  ],
  "narrativeArc": "读者认知路径(问题→张力→解答→行动)",
  "evidenceMap": [
    {"sectionId":"S1","argument":"分论点","evidenceNeeded":"所需证据类型",
     "entryKeys":["f_123","f_456"],"coverage":"COVERED|PARTIAL|MISSING",
     "note":"…"}
  ],
  "constraints": ["合规红线/必避"],
  "gaps": [{"sectionId":"S3","reason":"无对应事实"}],
  "quality": {"argumentDensity":4,"evidenceCoverage":0.75,"gapCount":1,"taskBriefConsistency":0.9}
}
```

- `entryKeys` 绑定 `fact_sheet.entries[].key`(既有字段,见 `brief-generation.md §5`)。
- `coverage` 由确定性计算:该 section 的 `entryKeys` 中有效条目数 / 所需,`MISSING` 触发 `gaps`。
- **人工评审门**:蓝图落库后 `blueprint_status=REVIEWING`,前端可编辑论据/调整 `entryKeys`;`POST /deep/blueprint/confirm` 置 `CONFIRMED` 才解锁写作。编辑本身不改 fact_sheet,只改蓝图结构。

### 4.4 写作硬约束

`DeepWriterService.write` 改为:

- 按 `argumentStructure` 逐节写作,每节只注入该节 `evidenceMap.entryKeys` 对应的 fact 条目(从 fact_sheet 按 key 取)。
- prompt 明确:蓝图未映射的 fact/数值**不得**出现;`coverage=MISSING` 的论点须写成不含具体数值的定性陈述,或显式标记为待核实。
- 保留既有 `fact_risks` 数值回查(正则)作为**后验硬校验**:正文出现蓝图外数值 → 记 high risk(比现状更严格,因蓝图已给出允许集合,可做白名单比对)。

## 5. 数据流(端到端)

1. 创建项目 → `start` 澄清会话(替代原 `/deep/clarify` 一次成型计划)。
2. 用户多轮回答 → 每轮 `answer` 返回下一问或收敛 → `TaskBrief` 落库。
3. 收敛后(或用户点「开始研究」)→ `ResearchPlannerService` 基于 TaskBrief 产出**纯事实** `research_plan`。
4. `/deep/run` 走既有并行研究 → `fact_sheet`。
5. 研究完成 → `BlueprintService` 基于 TaskBrief + `research_plan` + `fact_sheet` 产出蓝图(`blueprint_status=REVIEWING`)。
6. 前端展示/编辑蓝图 → `POST /deep/blueprint/confirm` 置 `CONFIRMED`。
7. `/deep/generate` 写作,按蓝图硬约束取用。

状态机:`plan_status` 继续表示研究计划态;新增 `blueprint_status` 表示蓝图门;项目状态机 `DRAFT→GENERATING_BRIEF→READY→GENERATING_VERSIONS→VERSIONS_READY` 不变(「等待用户确认蓝图」在 `READY` 内以 `blueprint_status=REVIEWING` 表达,或评估新增一个项目状态位——**设计倾向**:不新增项目状态,避免写权扩散,用 brief 侧 `blueprint_status` 表达)。

## 6. 关键机制

- **充分性收敛**:LLM 判定 + 必要槽位硬兜底;可单测(构造「信息足够即停」「不足追问」用例)。
- **问题选择**:信息增益×影响排序;保留确定性兜底(背景题补全逻辑迁移至研究规划)。
- **硬约束取用**:按 `entryKeys` 投影 fact_sheet;白名单数值回查。
- **质量信号**:`argumentDensity`(论点/节数)、`evidenceCoverage`(COVERED 比例)、`gapCount`、`taskBriefConsistency`(LLM 打分或槽位覆盖)。确定性部分本地算,一致性可 LLM 评分。
- **异步/重试**:蓝图生成走 `@Async` + 截断提额重试;澄清每轮为轻量 LLM 调用(单轮超时保护)。
- **降级**:研究失败/低置信照旧注入降级提示并标 `factRisks`;蓝图 `coverage` 反映缺口,不阻断。

## 7. 兼容与迁移

- **无历史兼容负担**:用户授权清空项目数据。迁移脚本直接删旧列/加新列;旧接口 `/deep/clarify`、`/deep/clarify-answer` 可**移除**或标记删除(视前端改造节奏),新增对话式接口。
- `docs/spec/brief-generation.md`、`docs/article-generation-flow.md` 需重写认知层章节;新增蓝图/澄清会话契约表。
- 前端 `StepBrief.vue` 的深度分支 `deepStage` 状态机需扩展 `ASKING/CONVERGED/BLUEPRINT_REVIEW` 等态。

## 8. 权衡

| 决策 | 选择 | 代价 | 理由 |
|---|---|---|---|
| 澄清交互 | 多轮对话 | UI 改造大、LLM 调用多 | 用户已选,质量最高 |
| 蓝图门 | 人工确认 | 多一次交互 | 用户已选,人在关键决策点 |
| 证据约束 | 硬约束 | prompt/校验复杂度升 | 用户已选,可信度优先 |
| 会话存储 | 先 JSON | 关系查询弱 | 与现有 JSON 范式一致,可后评估 |
| 项目状态 | 不新增蓝图态 | 语义借 brief 侧表达 | 写权不扩散 |

## 9. 回滚

- 各子任务独立可回滚;蓝图门可用开关降级为「自动确认」(配置项),保证链路可通。
- 迁移 V10 为结构变更,回滚需反迁移或重置库(数据可清空,风险低)。
