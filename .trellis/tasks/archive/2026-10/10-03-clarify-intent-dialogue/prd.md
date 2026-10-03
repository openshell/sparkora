# C1 意图澄清对话

> 父任务:`../10-03-gen-cognitive-redesign`(需求集/设计/执行见父 `prd.md`/`design.md`/`implement.md`)。
> 依赖:无(地基,最先做)。

## Scope

澄清从「LLM 一次性定长问卷」改为**多轮对话式意图澄清**:缺口驱动、每轮决定下一问或收敛、以信息充分性为收敛条件;产出结构化 **TaskBrief** 作为下游唯一输入。

## Acceptance Criteria

- [ ] 新增 `clarify_session` / `task_brief` 数据模型(Flyway V10)与 entity。
- [ ] `ClarifyConversationService` 提供 start/answer/converge/abort;每轮返回下一问或收敛结果。
- [ ] 收敛判定:必要槽位(purpose/audience/mustCover)硬兜底 + LLM 充分性判定;构造用例证明「够即停」「不足追问」。
- [ ] TaskBrief 每槽位带 `source(USER|PICKED|DEFAULT|INFERRED)` 与 `confidence`。
- [ ] 接口 `POST /deep/clarify/start|answer|converge` 落库并可供前端轮询/驱动。
- [ ] 并发:同项目至多一个 `status='ASKING'` 会话(部分唯一索引);陈旧自愈。
- [ ] 单测:收敛、兜底、异常降级;`mvn test` 通过。

## Out of Scope

- 研究规划(见 C2)、蓝图(C3)、写作(C4)、UI(C5)。
