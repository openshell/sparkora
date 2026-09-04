# 清洗链路缺陷取证(2026-09-04,代码级实证)

- D1 STRING 兜底误标 RULE:`CarCleanService.cleanOne` 最后 fallback 分支 `fallback.setCleanMethod("RULE")` — 应为独立值 FALLBACK。
- D2 块首行无车型名:`CarDocService.buildParamGroupDocs` 生成 `参数分组:xxx\n参数:值…`,跨版本检索不可区分(S6.2 prd P1 明确遗留)。
- D3 无质量维度:`CarSyncJobService.runJob` 仅 success/failed 计数;`cleanForModel` 返回 void。
- D4 串行+静默丢块:`CarDocService.rebuildForModel` 循环内逐块 `insertDocWithEmbedding`,单块 catch 后仅 log.warn("文档块向量化失败…")。
- D5 清洗价值未传导:切块拼 `c.getParamValue()`(原始值),`car_param_clean` 的 numeric_value/unit/list_values/enum_value 字段未用于块文本。

# 量化口径(research/clean-audit-report.md 用)

- SQL:`SELECT clean_method, value_type, count(*) FROM sparkora_car_param_clean GROUP BY 1,2;`
- 可疑占比(修复前):`clean_method='RULE' AND value_type='STRING'` 需人工抽样确认真实规则命中 vs 兜底混入。
- 每车型:`SELECT model_id, clean_method, count(*) … GROUP BY 1,2` + 参数总数对照 `sparkora_car_param`。
- 块统计:`SELECT model_id, chunk_type, count(*) FROM sparkora_car_doc GROUP BY 1,2`;含数值块占比按 chunk_text 正则。
