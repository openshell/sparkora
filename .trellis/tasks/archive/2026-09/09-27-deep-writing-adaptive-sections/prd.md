# 深度写作注入目标字数 + 自适应小标题分节

## Goal

让深度写作（唯一 TOPIC 创作正文链路）按文章目标长度自适应分节：注入 `wordCountTarget`，并按目标字数分档决定「`##` 小标题数量 / 每节段数」，替代当前写死的「2~4 个」——解决**长文章小标题过少、层级过平**的可读性问题。

## Background（已确认事实，含证据锚点）

- **B1 · 分节规则写死**：`DeepWriterService.java:210` 的 system prompt 固定「全文用 **2~4 个** `## 小标题`分节,每节 2~3 段」，与长度无关。
- **B2 · 深度写作不读目标字数**：`wordCountTarget` 目前只被 `VersionService` 消费（`:266/:298`）；`DeepWriterService.write` 全链路未读 `p.getWordCountTarget()`（`write` 仅经 `extractH1` 取 `p.getTopic()`）。前端默认 `wordCountTarget=1500`、范围 100–10000（`frontend/src/views/ProjectEdit.vue:87,140`）。
- **B3 · 现有测试逐字锁定 system prompt**：`DeepWriterServicePromptTest.java:203-212` 断言无 kind 时 system prompt **整段逐字等于**含「2~4 个」的旧文案——改分节必须同步重构该断言（其真实意图是「无 kind 时不出现分组铁律」，非锁定排版文案）。
- **B4 · 排版铁律三处重复**：同段文案另存于 `VersionService.java:184-185`（仿写/封存 FAST 链路）；本任务不改该处（见 Out of Scope）。

## Requirements

- **R1 · 注入目标字数**：`DeepWriterService.write` 读取项目 `wordCountTarget` 并注入 user prompt（口径对齐 `VersionService`：`目标字数：N`；null → `1500`）。
- **R2 · 自适应分节分档**：按目标字数决定「小标题数量区间」与「每节段数」，生成排版铁律文案注入 system prompt；分档规则为**纯函数**（可单测、无副作用）。
- **R3 · 分档取值**（确定性，供实现与测试对齐）：

  | 目标字数 | 小标题数 | 每节段数 |
  |---|---|---|
  | ≤ 800 | 2~3 | 2~3 |
  | 801~1800 | 3~5 | 2~3 |
  | 1801~3000 | 5~8 | 2~3 |
  | > 3000 | 8~12 | 2~4 |

  - null → 按 1500（中档 3~5）；≤0 或异常 → 按默认档处理，不抛。
  - 其余排版铁律（加粗突出、单段 ≤5 行、禁止整篇无分节）保留不变。

- **R4 · 既有测试重构**：`DeepWriterServicePromptTest` 的「无 kind → system prompt 逐字等价」用例改为断言「**不含** kind 分组铁律（参数事实/背景素材）」等实质契约，不再逐字锁定含节数文案的旧字符串；kind 分组行为与其余用例不受影响。

## Acceptance Criteria

- [ ] **AC-01（R1）**：`write` 发出的 user prompt 含 `目标字数：` + `wordCountTarget` 值；`null` → `1500`。
- [ ] **AC-02（R2/R3）**：system prompt 的排版铁律按目标字数分档（默认 1500 → `3~5` 个；>3000 → `8~12` 个且每节 2~4 段；≤800 → `2~3` 个）；非「2~4」固定值。
- [ ] **AC-03（R3）**：分档纯函数单测覆盖边界：`0/-1/null/800/801/1800/1801/3000/3001/10000`，返回值符合表且不抛。
- [ ] **AC-04（R4）**：`DeepWriterServicePromptTest` 重构后全绿；无 kind 时不出现「参数事实/背景素材」；有 kind 时分组铁律仍注入；简报四字段/平铺格式等既有断言不回归。
- [ ] **AC-05**：`mvn -q -DskipTests compile` 通过；`mvn test` 全绿。
- [ ] **AC-06（红线）**：不改数据库 schema / 无 Flyway 迁移；不改 `/deep/*` 响应主结构、`fact_sheet`/`research_notes` 结构、项目状态值域；不改 `VersionService`（仿写链路）；不新增密钥与外部依赖。

## Out of Scope（另立任务）

- **仿写 / 封存 FAST 链路的排版铁律**（`VersionService.java:184-185` 的 `layoutRules`）统一改造——本任务仅深度写作；如需一致可另立任务（含仿写链路 `wordCountTarget` 已有、可直接套用同一分档函数）。
- `audienceRefine` 注入、`selectedTitle`/`extraInfo` 注入、outline 可读化、factRisks 转指令、`verifyNumbers` 对齐 kind（此前评估列出的其它深度写作缺口）。
- 生成后大纲覆盖自检、两阶段 refine、逐节生成。
- 前端展示调整（本项目无前端改动）。

## Open Questions

- （无。范围与分档表已定；仿写链路一致性列入 Out of Scope。）
