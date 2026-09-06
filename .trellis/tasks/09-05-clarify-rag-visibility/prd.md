# 深度研究澄清问题与知识库引用可见性修复

## Goal

修复深度模式三处体验 bug,来源为 2026-09-05 用户实测反馈:

1. **竞品对比题被生成为单选**——对比竞品本质是多选需求,LLM 生成时不受约束;
2. **单选/多选没有「其他」自由输入**——用户无法填写名录外的自主表述;
3. **简报/版本页看不到知识库引用明细**——`ragStatus` 只存四态枚举,注入 prompt 的命中块(【车型数据:xx】【通用知识:xx】)没有落库,用户无法核查 AI 引用了哪些知识。

## Requirements

### R1 竞品对比题强制多选

- `ClarifyService` system prompt 增加「对比竞品类问题必须 type=multi」的明确约束(与既有锚点车型题约束同格式);
- 后端确定性兜底(不依赖 LLM 遵守):`clarify()` 解析 questions 后,对问题文本含竞品信号词(对比/竞品/比较/竞对)的选项题归一化为 `type=multi`,并补「不对比」兜底选项(缺省时)。

### R2 澄清表单「其他(自行填写)」

- `ClarifyForm.vue` 对 single/multi 题渲染固定 options 之后追加「其他(自行填写)」入口;
- single:选「其他」出现文本框,提交值取文本框内容;multi:勾选「其他」出现文本框,勾选时其值并入答案(「、」拼接规则不变);
- 锁定回显兼容:若锁定答案不在原 options 中,视为「其他」并回填文本框。

### R3 知识库引用明细落库与展示

- `CarRagService.RagResult` 增加 `List<Citation> citations`(source=CAR|KB、modelName、chunkType、score、chunkText 摘要);
- brief/version 表新增 `rag_citations` JSON 列(`schema.sql` 幂等加列 + entity 字段),生成成功时写入(截断防爆列,上限 ~8000 字符);
- 深度链路:brief 侧展示「事实手册来源」(解析 fact_sheet 条目的 source.type=KB|WEB);version 侧展示 rag_citations;
- 前端简报页(StepBrief)/版本页(StepVersions)增加可折叠的「知识库引用」面板:`el-collapse` + 引用条目(来源标签 + 车型/文档名 + 相似度分数),空态显示 `NO_KNOWLEDGE` 文案。

## 约束

- 不改项目状态机;不改发布链路;`ragStatus` 四态语义与展示位延续。
- 表结构变更三处同步:`schema.sql`(幂等)+ entity + `docs/s0-spec.md` 字段表。
- 检索失败时 `rag_citations` 为空数组/空串,面板显示降级提示而非报错。

## Acceptance Criteria

- [ ] AC1: 深度澄清含竞品对比语义的问题,生成后 type=multi 且含「不对比」兜底项(接口实测或单测)。
- [ ] AC2: ClarifyForm 单选/多选题可见「其他(自行填写)」,填写后答案正确写入锁定结构,回显正确还原。
- [ ] AC3: FAST/DEEP 生成简报与版本后,对应页面可展开查看引用明细;NO_KNOWLEDGE/FAILED 时展示空态/降级文案。
- [ ] AC4: `mvn -q -DskipTests compile` 通过;`npm run build` 通过。

## Notes

- 上限防御:引用列表序列化前超限截断(与 lastBriefError 截断口径一致),不因异常文本写库失败。
- 深度版 brief 的 factRisks 数值回查产物(factRisks 列)已有展示位,本次不重复改。