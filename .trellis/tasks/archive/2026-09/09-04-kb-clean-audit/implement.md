# 执行计划:kb-clean-audit

> 顺序执行;每步验证后打钩。轻量修复型任务,PRD + 本计划即可(无独立 design.md;实施中若出现结构性取舍再补 design.md)。

## Step 1 清洗可观测(修复 D1/D3,先行——报告依赖它)

- [ ] `CarCleanService.cleanOne` 降级分支 `cleanMethod` 改 `"FALLBACK"`;`cleanForModel` 返回统计对象 `CleanStats { total, rule, ai, fallback }`(DTO 放 `car.dto`)。
- [ ] `CarSyncJobService.runJob` 聚合每车型统计写 job 明细/日志。
- [ ] `CarModelController` 增 `GET /api/car/models/{id}/clean-stats`(R<T> 包装;统计 SQL 按 clean_method 分组计数)。
- 验证:`mvn -q -DskipTests compile` 绿;重启后对已同步车型调统计接口,返回占比。

## Step 2 切块质量(修复 D2/D5)

- [ ] `CarDocService.buildParamGroupDocs`:PARAM_GROUP 块文本改为 `参数名:清洗值(+单位)`;清洗缺失回退 raw;首行 `车型:<全名>`(MODEL_INFO/RIGHTS 块同样加)。
- [ ] 边界:车型全名取 `CarModelEntity.name`;清洗值含 LIST 时拼 `a×b` / `a、b` 原语义分隔。
- [ ] 单测:切块首行断言 + 清洗值优先于原始值断言(不连库,构造 entity)。
- 验证:`mvn test`(现有 CarRagServiceTest + 新增)全绿。

## Step 3 embedding 健壮性(修复 D4 最小版)

- [ ] `CarDocService.rebuildForModel`:embedding 调用并发(固定线程池 ≤4,复用单例,不新建每车型线程池)+ 失败重试 1 次;完成日志「成功 X/失败 Z」,失败块记 id。
- [ ] 注意:并发下 `insertDocWithEmbedding` 事务边界不变(doc 插入仍在主线程,或改并发仅包 embedding 调用,保持简单)。
- 验证:编译 + 单测;联调重建一车型看日志计数。

## Step 4 体检报告(AC5)

- [ ] 写 `research/clean-audit.md` 脚本化统计(直接 SQL:`SELECT clean_method, value_type, count(*) … GROUP BY`),跑当前库。
- [ ] 抽样核对 10 条 AI 兜底清洗结果正确性。
- [ ] 报告落 `research/clean-audit-report.md`:量化表 + 结论「能否支撑运行」+ P1/P2 建议清单(高密度参数块、未知格式告警、批量回刷成本评估)。
- 验证:报告含真实数字(非模板)。

## Step 5 收尾

- [ ] 全量验证:`mvn -q -DskipTests compile` + `mvn test`。
- [ ] spec 同步:docs/s0-spec.md §6b 注记(块首行车型名/清洗三态 method/重建计数日志);`.env.example` 无新增则不动。
- [ ] trellis-check;commit(`fix(S6b): 数据清洗可观测+切块质量+embedding 健壮性`)。
- [ ] 重新同步 1-2 个车型 + 重建向量,验证 AC1~AC3(端到端证据)。

## 回滚点

- 每步独立提交;Step 2/3 改动均限 `CarDocService`/`CarCleanService`,git revert 单步可回。
- 修复生效前提(重新同步+重建)写入报告与 spec,避免「改了没生效」误判。