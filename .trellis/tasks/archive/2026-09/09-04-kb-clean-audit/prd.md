# 比亚迪数据清洗链路体检与优化

## Goal

量化体检「BYD 同步采集 → 参数清洗 → 切块 → 向量化」链路的数据质量,回答「当前清洗能否支撑系统(车型对比 + 通用知识库)运行」;落地低成本高收益的数据层修复,使清洗结果可观测、可重建、可支撑检索。

## 背景(代码取证 2026-09-04)

链路:`CarModelService.syncOne` → `persistParams`(原始参数入 `car_param`)→ `CarCleanService.cleanForModel`(规则引擎 `ParamCleaner` + AI 兜底 `AiParamCleaner`,写 `car_param_clean`)→ `CarDocService.rebuildForModel`(基于清洗数据切块向量化)→ `CarRagService` 检索。

### 已确认缺陷(均为代码级实证)

| # | 缺陷 | 位置 | 影响 |
|---|---|---|---|
| D1 | AI 兜底清洗失败后的 STRING 降级结果 `cleanMethod` 误标为 `"RULE"` | `CarCleanService.cleanOne` 降级分支 | 清洗质量不可观测:统计 RULE/AI 占比时把失败兜底混入规则命中;无法定位需人工复核的数据 |
| D2 | 参数块 chunk_text 首行无车型全名 | `CarDocService.buildParamGroupDocs` | 同名车系跨动力版本(EV/DM-i)检索时块不可区分,09-03 海狮08 价格混淆的根因之一(S6.2 P1 遗留项) |
| D3 | 同步任务报告只有 success/failed 车型数,无清洗质量维度 | `CarSyncJobService` | AI 兜底率/失败率不可见,清洗规则退化(官网改版)无告警通道 |
| D4 | embedding 逐块串行调用,单块失败仅 warn 静默丢知识 | `CarDocService.rebuildForModel` | 50+ 车型 × 15+ 块 = 千次串行 HTTP;失败块无声缺失,检索覆盖度下降且无感知 |
| D5 | 块文本直接拼 `paramValue` 原始值 | `CarDocService` | 清洗层已产出类型化 `car_param_clean`(NUMBER+unit/LIST/ENUM),切块却回退用 `paramValue`,清洗价值未传导到检索层 |

### 体检范围(实施时量化)

- 对库中现有车型跑统计:每车型参数总数 / 规则命中 / AI 兜底 / STRING 原样占比;`clean_method='RULE'` 且 `value_type='STRING'` 的可疑占比(D1 修复后可信)。
- 抽样 10 条 AI 兜底结果人工核对数值正确性(AI 清洗的置信度 0.5 默认值实际含义)。
- 切块后:每车型块数、含数值块占比、零信息块残留(应已为 0,S6.2 修复)。

## Requirements

- **R1 修复 D1**:降级分支 `cleanMethod` 改标 `"FALLBACK"`(新值,区别于 RULE/AI);存量数据跑一次重清洗脚本/接口刷新。
- **R2 修复 D2**:参数块与车型概述块首行加车型全名(`CarDocService` 切块处),与 KB 块首行策略对齐。
- **R3 修复 D5**:PARAM_GROUP 块生成改用 `car_param_clean.param_value`(+unit 拼接,如 `轴距:2820mm`),原始值仅在清洗缺失时兜底。
- **R4 修复 D4(最小版)**:`rebuildForModel` embedding 调用加并发(固定小线程池,如 4)+ 单块失败重试 1 次;重建完成日志输出「成功 X/总 Y/失败 Z」,失败 Z>0 时 warn 汇总(块 id 列表)。
- **R5 修复 D3(可观测)**:`cleanForModel` 返回清洗统计(rule/ai/fallback 计数),`CarSyncJobService` 记录进 job 失败明细或日志;提供单车型清洗统计查询接口 `/api/car/models/{id}/clean-stats`(ADMIN/EDITOR/VIEWER 均可读)。
- **R6 体检报告**:对当前库数据跑量化统计,报告落 `research/clean-audit-report.md`,给出「能否支撑运行」结论与后续 P1/P2 建议(高密度参数块、规则告警通道等,不在本期实施)。

## Acceptance Criteria

- [ ] AC1 重新同步任一车型后:`car_param_clean` 中 `clean_method` 仅出现 RULE/AI/FALLBACK 三值;STRING 兜底记录全部为 FALLBACK(D1)。
- [ ] AC2 重建向量后,PARAM_GROUP 块文本首行含车型全名,且数值来自清洗值(如 `轴距:2820mm` 格式)(D2/D5,用真实车型块文本验证)。
- [ ] AC3 重建向量日志含「成功/失败块计数」;人为断网 embedding 时重建完成且失败块有明细日志,不静默(D4)。
- [ ] AC4 同步任务明细或日志含每车型清洗统计(RULE/AI/FALLBACK 占比)(D3)。
- [ ] AC5 体检报告完成,含量化表格与「能否支撑运行」明确结论。
- [ ] AC6 `mvn -q -DskipTests compile` 绿;新增/调整单测(ParamCleaner 边界、切块首行)全绿;`CarRagServiceTest` 不回归。

## Constraints

- **不改 `CarRagService` 检索策略与 S6.1/S6.2 语义**(归 `09-04-kb-generalize` 的 R5/R6)。
- 不改 `BydCmsClient` 采集契约;不动 schema 表结构(本任务仅 DML 层与代码)。
- 修复后需**重新同步+重建**受影响车型才生效,报告需说明此操作前提(spec §6b 同款约定)。
- AI 兜底清洗的成本控制:不做批量回刷历史参数(仅新同步触发),历史数据修正走 R5 统计暴露 + 人工决策。

## Notes

- 设计细节见 `design.md`(若实施中确认需要);本任务偏修复+量化,预期 PRD+implement.md 即可,design.md 视需要补。
- 执行清单见 `implement.md`。