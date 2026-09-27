# 执行计划：深度写作注入目标字数 + 自适应小标题分节

## 实施顺序（每步可独立编译 + 单测）

1. **分档纯函数（无行为变化可先测）**
   - `DeepWriterService` 增 `record SectionSpec(String headings, String parasPerSection)`、`sectionSpec(Integer)`、`layoutRule(Integer)`（`design.md §2`）。
   - 新增分档边界单测：`null/-1/0/800/801/1800/1801/3000/3001/10000`，断言区间串符合表且不抛。
   - 校验：`mvn -q -DskipTests compile`。

2. **write 接入（R1/R2）**
   - `write` 取一次 `ArticleProjectEntity p`（判空回退，复用给 `extractH1`，不新增多余查询）。
   - user prompt 注入 `目标字数:N`（null/≤0 → 1500，口径对齐 `VersionService`）。
   - system prompt 排版铁律第一行改用 `layoutRule(p.getWordCountTarget())`；其余铁律逐字保留。
   - 测试：`DeepWriterServicePromptTest` 增——user prompt 含 `目标字数：1500`（null）与指定值；system prompt 默认档含 `3~5`；目标 5000 时含 `8~12` 与 `每节 2~4 段`。

3. **既有断言重构（R4）**
   - `DeepWriterServicePromptTest.全无kind_systemPrompt与旧行为等价` 改为 `全无kind_systemPrompt不含分组铁律`（断言不含「参数事实/背景素材」），不再逐字锁定含节数的旧串。
   - 复跑该测试全绿；确认 kind 分组、简报四字段、平铺、畸形 JSON 等既有用例未回归。

4. **文档同步（Phase 3.3）**
   - `docs/spec/brief-generation.md` §6：补「深度写作注入目标字数、按目标字数自适应 `##` 分节（分档表）」契约，标注 `wordCountTarget` 默认 1500、来源项目字段。

## 验证命令

```bash
mvn -q -DskipTests compile
mvn test
# 前端未改则免（本任务无前端改动）
```

## 风险文件 / 回滚点

- `DeepWriterService.java:200-212`（system prompt 拼接）：改动须保证 `hasKind` 分支与其余铁律逐字不变，避免波及其它断言。
- `DeepWriterServicePromptTest.java:203-212`：**唯一**逐字锁定 system prompt 的断言，必须成对重构，否则改 prompt 必红。
- `write` 中 project 查询：勿为取 `wordCountTarget` 新增第三次查询（`extractH1` 已有一次）。
- 回滚：纯代码还原。

## task.py start 前检查

- [ ] PRD/design/实现范围已评审（用户确认）。
- [ ] `implement.jsonl` / `check.jsonl` 已 curate（非空）。
- [ ] 用户已显式批准最终规划摘要。
