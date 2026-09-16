# Quality Guidelines

> Code quality standards for backend development.

---

## Overview

本项目质量约定聚焦两条最容易出事的边界：**验证/测试不得触碰生产数据**，以及**不可逆操作必须可回滚**。

---

## Forbidden Patterns

### Don't: 验证/测试阶段直接修改既有生产数据行

**Problem**（09-15 article-auto-illustrate 真实事故）：

```bash
# 为验证「正文为空 → 400」用例，直接改生产行
psql ... -c "UPDATE sparkora_article_version SET content_md='' WHERE id=25"
# 还原时语句写错（CREATE TEMP TABLE t_bak 跨 psql 会话无效）
# → version 25 的 content_md(785 字) 永久丢失，全库无备份/PITR/WAL 可恢复
```

**Why it's bad**：生产库无 PITR、无 WAL 归档、无转储时，一次 `UPDATE` 写错就是永久数据丢失。上例中「临时置空再还原」的写法跨进程/跨会话失效，且验证者往往不会逐行核对还原结果。**验证数据的成本远高于它带来的信心。**

**Instead**：

| 需求 | 正确做法 |
|---|---|
| 「正文为空 → 400」类边界 | **JUnit + Mock** 覆盖（`IllustrationSuggestionServiceTest` 先例），不碰 DB |
| 需要真实端到端路径 | 新建**自己的**临时数据（project/version），用完按 id 精确删除并复核总数 |
| 需要临时改状态 | `BEGIN` + 改 + `ROLLBACK`，且**仅针对自己新建的行** |
| 只读核对 | `SELECT` 不受限，鼓励使用 |

- 核对/验证结束时必须复核总量（如 `versions/projects/images/dismiss` 计数）恢复到基线，并**显式声明未修改既有生产行**。
- 结构变更只允许执行 `schema.sql` 里已有的幂等语句（`CREATE ... IF NOT EXISTS`），不手工 DDL。

---

## Required Patterns

### Convention: 不可逆副作用必须与主流程隔离，且失败可回滚

- **best-effort 写入**（向量/派生缓存/埋点）若在调用方事务内失败，会让 PostgreSQL 把整个事务置 aborted，`catch` 无法挽回 → 必须走 `REQUIRES_NEW` 独立事务（见 database-guidelines.md「向量/派生数据写入需与调用方事务隔离」）。
- **多步写入**（如「插正文 markdown + 登记 `body_image_ids`」）必须**定义失败顺序**：先做可失败的写、后做不可回滚的写；或对已完成部分显式回滚。09-15 先例：改为「先登记（可失败）→ 再插正文」，插入未成功则回滚登记，并跳过「该图此前已登记」的误删。
- **返回值语义必须可判定**：封装函数（如 `insertMdAtAnchor`）的返回值要能区分「成功（含降级路径）」与「未执行」——`false` 同时表示两种情况会让调用方无法安全回滚。

---

## Testing Requirements

- 纯函数/算法优先抽为**无 Spring 依赖的静态方法**并单测（先例：`NewsImageClassifier`、`AnchorExtractor`）。
- **契约级硬约束要有断言**：如「零副作用」用反射断言依赖不含写组件 + 逐方法 `never()`（`IllustrationSuggestionServiceTest`）。
- 前端字段名错误（record `imageId` vs 实体 `id`）**编译与后端单测都发现不了** → 采用类链路需真机点击或用 curl 打通 API 层（09-15 P0 先例）。

---

## Code Review Checklist

- [ ] 是否存在直接改生产数据的验证脚本/SQL？若有，改为单测或临时数据。
- [ ] 新增写入是否与调用方事务隔离（best-effort 场景）？
- [ ] 多步写入的失败顺序是否安全、可回滚？
- [ ] 前端消费的字段名是否与后端 DTO/record 完全一致（不靠类型系统兜不住的假设）？
- [ ] 涉及不可逆操作的改动，是否记录了回滚步骤？
