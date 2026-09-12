# C4 多轮对话式知识问答

> 父任务：`09-11-knowledge-base-data-foundation`。依赖：C2（新闻域）、C3（前端框架/入口约定）。

## Goal

提供**多轮对话式问答 + 来源引用**（独立入口，非知识中心 Tab）。答案跨域检索（车型 CAR + 新闻 NEWS + 通用 KB）后由 LLM 合成，并带 citations；同一会话可追问、上下文连贯。

## Requirements

- **R1 多轮能力**：扩展 `AiClient` 支持多轮消息（新增方法，不破坏现有 `chat`/`chatJson`）。
- **R2 检索**：复用 `CarRagService.retrieveForGeneration`/`retrieveUnified` 跨三域检索；NEWS 来源可区分。
- **R3 合成**：LLM 基于检索上下文合成答案 + citations（复用 `Citation` 结构或等价）。
- **R4 会话**：`sparkora_qa_session` / `sparkora_qa_message`（`citations` JSON）；`schema.sql` 幂等 + entity/mapper。多轮上下文维护策略明确（历史轮数限制/摘要压缩）。
- **R5 REST**：`POST /qa/sessions`、`GET /qa/sessions`、`GET /qa/sessions/{id}`、`POST /qa/sessions/{id}/messages`；`@PreAuthorize` + `R<T>`。
- **R6 前端**：独立问答页 + 引用展示（复用 `CitationList` 范式）。
- **R7 开关**：浏览/问答不受 `kb_enabled` 控制；仅生成注入可开关。

## Acceptance Criteria

- [ ] AC1：可创建会话、提问、得到带来源引用的答案。
- [ ] AC2：同一会话多轮追问上下文连贯。
- [ ] AC3：答案可引用车型/新闻/通用 KB 三类来源并正确标注。
- [ ] AC4：`kb_enabled=false` 时问答仍可用。
- [ ] AC5：`mvn -q -DskipTests compile` 与 `npm run build` 通过。

## Out of Scope

- 语音、多模态问答。
- 会话分享/导出（可后续）。

## Notes

- 复杂子任务：`task.py start` 前补 `design.md` + `implement.md`。
- 与父任务 `design.md` §3.5/§5/§6 契约衔接。
