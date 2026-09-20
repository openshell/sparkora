# 文档结构与引用约定

> 本项目的「规格文档」不是单块巨石，而是 `docs/README.md`（总览 + 模块索引表）+ `docs/spec/**`（逐模块权威契约）。改动文档结构或新增引用时先读本指南。

---

## 权威文档层级（2026-09-17 重构后）

| 层 | 位置 | 角色 |
|---|---|---|
| 总览 | `docs/README.md` | 架构/概念地图/**模块索引表**/全局约定/配置总览；任何模块的入口 |
| 模块契约 | `docs/spec/<module>.md`、`docs/spec/knowledge/<domain>.md` | 字段级/接口/状态机/枚举的**唯一权威来源** |
| 深潜文档 | `docs/wenyan.md`、`docs/knowledge-base.md`、`docs/img.md`、`docs/article-generation-flow.md` | 单主题机制/流程讲解，回链总览与对应模块文档 |
| 历史快照 | `docs/spec/acceptance.md` | 过期验收记录，**不再维护**，不作契约依据 |

- 每个 `docs/spec/**.md` 顶部必须有回链 `> 回链：[系统说明总览](../README.md)`（`knowledge/` 层为 `../../README.md`）。
- 旧的 `docs/s0-spec.md` 已删除（其 §N 编号不再有效）；模块文档中形如「（原 §13）」的括注仅为迁移溯源，**不要**再据此定位。

---

## 引用规则：指向文件，不指向编号

**约定**：代码注释、spec、skill、AGENTS 中引用规格时，写**模块文档路径**，不写章节编号。

```java
// Correct
/** 配图接口（字段级契约见 docs/spec/image.md）。 */
/** 文章仿写（字段级契约见 docs/spec/imitation.md）。 */

// Wrong —— 编号在重构/再拆分后必然漂移，且已删除的 s0-spec 编号无从查证
/** 配图接口（字段级契约见 docs/s0-spec.md §10）。 */
```

- 理由：§ 编号与文档边界耦合，任何一次拆分/重排都会让全仓编号引用失效；文件路径是稳定锚点。
- 跨模块引用用相对链接（如 `[知识库必查](../retrieval.md)`）；同文件内不引用编号。

---

## 文档结构变更时必须枚举全部引用（09-17 先例）

删改/移动规格文档前，**先全仓枚举引用再动手**，否则会留下悬空指向：

```bash
# 枚举所有规格引用（排除依赖与任务工件）
grep -rn "s0-spec\|docs/spec\|docs/README" --include=*.md --include=*.java --include=*.vue --include=*.js . \
  | grep -v node_modules | grep -v ".trellis/tasks" | grep -v ".reasonix" | grep -v ".trellis/workspace"
```

引用来源（比想象中多，务必逐一核对）：

| 来源 | 说明 |
|---|---|
| Java 注释 | Controller/Service/Entity 的类注释、方法注释 |
| `AGENTS.md` / 根 `README.md` | 项目入口说明（`.gitignore` 内，但仍需本地同步） |
| `.trellis/spec/**` | 后端/前端规范中「三处同步」等引用 |
| `.opencode/skills/**` | 如 `sparkora-spec-check` 的核对流程指向 |
| `docs/*.md` | 深潜文档的「权威契约见 …」句 |
| 其他 spec 的交叉引用 | 模块文档之间的相对链接 |

- **删除顺序**：先更新全部引用 → 再删除旧文件 → 最后跑一次 grep 确认仅剩历史/任务工件。
- **迁移完整性**：内容搬移要做反向核对（从旧文件提取全部表名/接口路径/env 变量/枚举值，逐一确认在新文档存在），不能只看新文档「看起来完整」。
- **死链检查**：总览索引表与模块互链的每个相对路径都要验证目标存在（`docs/spec/knowledge/*` 层级容易写错深度）。

---

## 新增模块文档时

1. 在 `docs/README.md` 模块索引表补一行：`模块 | 文档链接 | 权威代码路径`（后端包·关键类 / 前端视图）。
2. 文档顶部加回链总览。
3. 若从既有文档拆出，更新原文档的交叉引用与代码注释中的旧指向。
4. 表结构变更仍「三处同步」：`schema.sql` + entity/mapper + `docs/spec/**` 对应模块字段级表格。
