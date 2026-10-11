# 建议门槛与图库搜索解耦并校准默认值

## Goal

建议场景默认相似度门槛独立配置(与 AI_IMAGE_MIN_SCORE=0.3 解耦),真实稿件校准默认值(评审 P2:0.3 对「建议」偏宽松,弱相关候选消耗用户信任)。

## Requirements

- **R1 配置解耦**:新增 `AI_ILLUSTRATION_MIN_SCORE`(env → `AiProperties.illustrationMinScore`,JVM 默认值取校准结论,初定 **0.4**);`suggest()` 的 minScore=null 时取该配置。图库搜索(`POST /api/images/search`)行为不变,仍用 `AI_IMAGE_MIN_SCORE`。
- **R2 校验不变**:显式传参仍须 [0,1],越界 400(沿用 `requireMinScore`);null 语义变化仅指「取哪份默认配置」。
- **R3 前端口径一致**:预览页「智能建议」门槛输入默认值与后端默认一致(0.4),提示文案同步(说明默认口径对齐后端校准值)。
- **R4 校准留痕**:用 ≥2 篇真实稿件(如项目 65 销量主题 + 另一篇)生成建议,人工复核 topN 相关度分布,把默认值取值依据与观察结论写进 `docs/spec/image.md` §7;`.env.example` 同步新配置行(带注释默认值)。
- **R5 文档三处同步**:`AiProperties` 注释、`docs/spec/image.md`(接口表 minScore 缺省值描述)、`.env.example`。

## Acceptance Criteria

- [ ] 真机:`POST /{id}/illustration-suggestions` 不带 minScore → 响应候选 score ≥ 新默认值(0.4 显式传参与缺省结果一致数量级)
- [ ] `AI_IMAGE_MIN_SCORE` 只影响图库搜索,不再影响缺省建议(代码层 + 真机一条对照)
- [ ] 前端:新打开建议 tab 门槛输入显示 0.4;显式调低/调高后行为与后端一致
- [ ] `mvn -q -DskipTests compile` + `mvn test` 全绿;`npm run build` 通过
- [ ] `docs/spec/image.md` + `.env.example` 已同步,校准结论有记录

## Non-goals

- 每项目级门槛配置、句级分块检索、嵌入模型更换
- 图库搜索阈值行为与 UI(10-10 已验收)不动

## Notes

- 依赖 parent 环境收口(后端容器重建后真机验收)。
- 与 A(dedup)无依赖冲突:A 改候选内容,C 改分数过滤口径,合并验收时建议结果两者叠加后整体复核一遍。