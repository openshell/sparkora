# 执行计划:clean-followup

> 顺序执行;每步验证后签到。

## Step 1 AI 兜底空值防线(R3)

- [ ] `AiParamCleaner.clean`:解析后 `value` 为空/全空白 → 返回 null(视为 AI 失败,走 FALLBACK 兜底)。
- [ ] 单测:AI 返回空值 → null。
- 验证:compile + 测试绿。

## Step 2 摊平核查(R2)

- [ ] SQL 核查车型 39:参数行数 vs 清洗行数 vs 每参数 values_json 版本数,确认是否摊平。
- [ ] 若确认:修 `CarCleanService` 版本下标对齐逻辑(清洗行 = 参数行 × 版本数,不应把所有版本的值摊成多行同参数);
     修复后重清洗车型 39,行数回归 → 记录 research/flatten-findings.md。
- [ ] 若非摊平(官网数据本身如此):仅记录结论。
- 验证:research/flatten-findings.md 落盘;AC2。

## Step 3 批量重建接口(R1)

- [ ] `CarModelController` 新增 `POST /api/car/models/rebuild-all`(ADMIN/EDITOR):循环 56 车型调 `rebuildForModel`,汇总「成功/失败块」返回;同步任务化不做(一次性运维操作,同步接口+前端 loading 即可)。
- [ ] `CarLibrary.vue` 加「重建全部向量」按钮(确认弹窗,ADMIN/EDITOR 可见)。
- 验证:重启后端执行一次,AC1(对账接口核对)。

## Step 4 向量对账接口(R4)

- [ ] `GET /api/car/models/vector-stats`:全库统计 {modelCount, chunkCount, embeddedCount, missingCount, missingTopN:[{modelId,modelName,missing}]}。
- [ ] SQL 手工核对一致 → AC4。
- 验证:curl 实测。

## Step 5 收尾

- [ ] 全量 compile+test;spec §6b 注记(vector-stats/空值防线/摊平结论)。
- [ ] commit(`fix(S6b): 清洗遗留项收尾——空值防线/摊平修复/批量重建/对账接口`)。
- [ ] 归档任务。

## 回滚点

- Step 1/3/4 独立文件级改动,单步 revert 可回;Step 2 若修复解析逻辑,回滚后仅影响增量清洗(存量行不动)。
