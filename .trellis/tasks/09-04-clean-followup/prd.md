# 清洗体检遗留项收尾(P1/P2)

## Goal

落地 `09-04-kb-clean-audit` 体检报告 §6 的后续建议中可低成本执行的 P1/P2 项,使知识库数据全量收敛到新口径并具备对账能力。

## 背景

体检报告(`archive/2026-09/09-04-kb-clean-audit/research/clean-audit-report.md`)遗留:
- **31.6% 文档块(408/1293)无向量**(历史静默丢失);85.5% PARAM_GROUP 块首行无车型名(旧切块口径)。
- 单车型 39 清洗行 6432(占全库 60%)疑似多版本摊平。
- AI 兜底「无值清成空串」4 行错误输出入过库。
- 无全库向量对账手段(本次靠手工 SQL 发现缺失)。

## Requirements

- **R1 存量重建**:提供批量重建入口,对 56 存量车型逐个跑 `rebuildForModel`(新切块口径 + 向量补齐);任务化(复用 sync_job 模式或简单循环接口),前端车型库页可触发。
- **R2 摊平核查与修复**:核查车型 39 的 `car_param → car_param_clean` 行映射;若确认多版本摊平(参数×版本笛卡尔化),修 `CarCleanService.cleanForModel` 的解析逻辑并重清洗该车型验证行数回归合理。
- **R3 AI 兜底空值防线**:`AiParamCleaner` 对 AI 返回 `value` 空/全空白的結果视为失败返回 null(不入库),消除「无值清成空串」。
- **R4 向量对账接口**:`GET /api/car/models/vector-stats` 返回全库 {车型数, 块数, 有向量块数, 缺失块数, 缺失明细 topN}(三角色可读),消除对账盲区。

## Acceptance Criteria

- [ ] AC1 批量重建执行后:全库文档块向量缺失 = 0(对账接口验证);PARAM_GROUP 块首行含「车型:」的占比 = 100%。
- [ ] AC2 车型 39 清洗行数回落至与参数行数同量级(±版本数倍数内);根因有结论写入 research。
- [ ] AC3 新同步/重清洗车型中不再出现 `value_type` 非空但 `param_value` 全空白的 AI 行。
- [ ] AC4 对账接口返回真实统计且与 SQL 手工核对一致。
- [ ] AC5 `mvn -q -DskipTests compile` + 全量测试绿;spec §6b 注记更新。

## Constraints

- 不改检索策略与 KB 逻辑(S7 已收口)。
- 批量重建走既有 `rebuildForModel`,embedding 失败块沿用重试+计数日志,失败不阻断其余车型。
- 轻量任务:PRD + implement.md 即可。
