# 实施计划：正文元话语泄漏修复

> 顺序：清洗器（纯函数，先做可测）→ 深度链路接入 → 多版本链路对齐 → 测试 → 验证 → 文档。
> 每步独立可验证；任一步失败可单独回滚（见 design.md §6）。

## 步骤清单

### S1. 新增 `MetaLeakCleaner`（纯静态，零依赖）

- [ ] 新建 `src/main/java/com/sparkora/service/MetaLeakCleaner.java`
  - `CleanResult(String content, List<String> removed)` record
  - `static CleanResult clean(String markdown)`：null/空 → 原样返回
  - `META_PATTERNS`：design.md §5.3 的保守模式表
  - 段落切分 → 句读切分（保留 `。！？!?` 与闭合引号/括号）→ 逐句匹配删除 → 段空则删段 → 连续空行压缩
- [ ] 新建 `src/test/java/com/sparkora/service/MetaLeakCleanerTest.java`
  - 正例：version 44 第 17 段原文、第 25 段原文 → 零残留（AC3）
  - 反例：正常数值句 /「详细参数以官方发布为准」/「据媒体报道」/ Markdown 标题与列表 → 逐字保留（AC4）
  - 幂等：`clean(clean(x)) == clean(x)`（AC5）
  - 边界：null / 空串 / 仅标题 / 无泄漏正文 / 全段泄漏后段落塌陷不留空标题段

### S2. 深度写作链路（R1 + R2 + R3）

- [ ] `DeepWriterService`
  - 新增常量 `READER_RULES`（design.md §4 文本）
  - system prompt：排版铁律后追加 `READER_RULES`
  - 新增 `static String factRiskClaims(String factRisksJson)`（只抽 `claim`；异常/非数组/空 → null + warn）
  - 新增 `appendForbiddenClaims(StringBuilder, String factRisksJson)`：注入「【禁止写入正文的断言】…」块
  - `:266` 的 `appendBriefSection(user, "事实风险", b.getFactRisks(), true)` → `appendForbiddenClaims(user, b.getFactRisks())`
  - `content = cr.content()` 后立即 `MetaLeakCleaner.clean(content)`，`removed` 非空时 `log.warn` 记录；清洗结果用于后续 `verifyNumbers` / `resolveTitle` / `wordCount`

### S3. 多版本链路对齐（R1 死路径防御 + R3）

- [ ] `VersionService`
  - system（主题分支）layoutRules 后追加 `READER_RULES`（与深度链路同文案）
  - `buildUserPrompt`：删除 `:296`「事实风险点…%s」行与 `:303` 的 `nv(b.getFactRisks())`，改为 `factRiskClaims` 生成的禁写断言块（空则不追加）
  - `generateOne`：`contentMd` 非空校验**之后**、`v.setContentMd` 之前清洗（仿写分支跳过）

### S4. 测试更新

- [ ] `DeepWriterServicePromptTest`
  - `简报四字段_实质注入userPrompt`：`事实风险:` → 禁写断言块断言（claim 出现、suggestion 原文不出现）（AC1）
  - 其余 3 处「事实风险:」不再出现的断言 → 改为「禁写断言块不出现」
  - 新增：system prompt 含读者视角铁律（AC2）
  - 新增：brief 76 真实第 3 条 suggestion 文本不进 prompt（AC1 硬断言）
  - 新增：落库 content = 清洗后内容；`verifyNumbers` 收到清洗后文本（AC6）
  - 保持：块头 / 铁律 1~3 / 排版铁律 逐字断言不动（AC7）
- [ ] `VersionServiceTest`（或既有等价测试）：断言 `buildUserPrompt` 不含「事实风险点」与 factRisks 原文（AC8）

### S5. 验证

- [ ] `mvn -q -DskipTests compile`
- [ ] `mvn test`（全绿）
- [ ] `npm run build`（确认无前端受影响，顺带跑）

### S6. 文档同步

- [ ] `docs/spec/brief-generation.md:124`：`DeepWriterService.write` prompt 契约改为「事实风险 → 禁写断言（只取 claim）+ 读者视角铁律 + 落库前清洗」
- [ ] `docs/spec/version-generation.md`：`buildUserPrompt` 事实风险点 → 禁写断言（死路径标注）
- [ ] `.trellis/spec/backend/ai-rag-guidelines.md`：新增「AI 生成内容不得含内部元话语」契约（三层：素材区分/读者视角铁律/确定性清洗）

## 评审门（review gates）

| 门 | 判据 |
|---|---|
| G1（S1 后） | 清洗器单测全绿，反例清单零误删 |
| G2（S2/S3 后） | 编译通过；prompt 捕获断言满足 AC1/AC2/AC8 |
| G3（S4 后） | `mvn test` 全绿（510+ 用例，无既有用例回归） |
| G4（S6 后） | spec 三处与实现逐字对应 |

## 回滚点

- S1：新文件，删除即回滚。
- S2：`git checkout src/main/java/com/sparkora/deep/service/DeepWriterService.java`（但与 10-02 任务的改动同文件，需按 hunk 回滚）。
- S3：同 S2。
- 清洗误伤时：仅调 `META_PATTERNS`（单文件单常量），无需回滚 prompt。