# 文章生成前必查知识库并按可信度降级

## Goal

简报/正文生成时强制发起知识库检索:检索失败或整体置信度低于门槛时不静默降级,生成继续但 AI 必须在 factRisks 标注数据缺失,并把检索状态(成功/低置信抛弃/失败)透出到前端。

## Background(现状盘点)

- 已实现:项目关联车型时,`BriefService`/`VersionService` 生成前已调 `CarRagService.buildContextForModels`(topK=8, 逐块 minScore=0.3 过滤),命中块以「权威数据不得编造」注入 prompt。
- 缺口 1:检索失败(embedding 服务异常等)仅 `log.warn` 静默降级,AI 与用户均无感知。
- 缺口 2:只有逐块过滤,无「整体置信度过低 → 全部抛弃」的汇总门槛,且抛弃/失败与「无命中」不可区分。
- 缺口 3:阈值 0.3 硬编码在两处调用点,不可配置。

## Requirements

1. **必查(已决策:必查+降级可见)**:项目已关联车型时,简报生成与正文生成两条链路必须发起知识库检索;检索异常不得静默吞掉。
2. **失败降级**:检索失败时生成流程**继续**(不硬阻断),但必须:
   - prompt 中告知 AI「知识库本次检索失败」,要求在 factRisks 标注数据可能缺失/未核实;
   - 检索状态 `FAILED` 透出到生成结果(API 可见)并展示于前端。
3. **整体低置信抛弃**:检索到的最高相似度低于整体门槛时,视为知识不可信,**抛弃全部知识块(不注入 prompt)**,同样要求 factRisks 标注 + 状态 `LOW_CONFIDENCE` 透出。抛弃原因不得与「无命中」混淆。
4. **成功路径不变**:检索命中且过门槛时维持现状(权威数据注入,要求严格依据)。
5. **未关联车型**:视为「已查、无知识对象」,状态 `NO_KNOWLEDGE`,不算失败,不阻断。
6. **配置化**:逐块 minScore 与整体抛弃门槛改为环境变量(经 `AiProperties`),同步 `.env.example` 与 spec;默认值在 spec 记录并注明需按真实分数分布校准。
7. **前端可见**:简报步骤页(`StepBrief.vue`)展示本次生成的知识库检索状态(如「已引用 N 块 / 低置信已抛弃 / 检索失败已降级」)。

## Constraints

- 不引入硬阻断:外部 embedding 服务抖动不得阻断所有创作(用户已决策)。
- 知识库范围仍限项目已关联车型,不扩展到全网检索。
- 诚实边界:相似度衡量「相关性」而非「事实正确性」——知识库本身错误的数据不会因高相似度被拦截,防错依赖入库源头(比亚迪同步+人工清洗),本需求不承诺修复该层。
- 后端响应走 `R<T>` 包装;状态机不变(不新增项目状态,检索状态挂在 brief/生成结果元信息上)。
- 三处同步:`schema.sql`(若需存储检索状态列,幂等)+ entity/mapper + `docs/s0-spec.md`。

## Acceptance Criteria

- [ ] AC1 关联车型项目生成简报时,检索失败场景(可临时断开 embedding 配置模拟)生成仍成功,且响应/前端可见 `FAILED` 状态,brief.factRisks 含数据缺失类风险。
- [ ] AC2 检索最高分低于整体门槛时,知识块不出现在注入 prompt(可从后端日志/单测验证),状态为 `LOW_CONFIDENCE` 而非空上下文。
- [ ] AC3 未关联车型项目生成正常,状态 `NO_KNOWLEDGE`,行为与现状一致。
- [ ] AC4 检索成功路径行为与现状等价(权威数据注入、严格依据),不回归。
- [ ] AC5 阈值可经 `.env` 配置,`.env.example` 与 spec 有默认值说明。
- [ ] AC6 `mvn -q -DskipTests compile` 通过;`docs/s0-spec.md` 增补检索状态契约(字段级)。

## Notes

- 轻量任务,PRD-only。技术落点(已在评估中确认):`CarRagService` 增加整体门槛与检索状态返回(建议 record 携带 status/hitCount/maxScore),`BriefService`/`VersionService` 调整注入与降级分支,阈值入 `AiProperties`。
- 整体门槛默认值需拿真实 query 分数分布校准,spec 中标注「待校准」。