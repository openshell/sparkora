# Design — 系统说明文档重构

## 决策：`s0-spec.md` 处置（用户选定 B）

**彻底迁移移除**：`docs/s0-spec.md` 的全部有效内容拆分进新模块文档，删除原文件；同步更新所有引用（代码注释、`AGENTS.md`、Trellis spec、skills、其他 docs）。

- 依据：用户明确选择 B（单一权威来源，最干净）。
- 代价：需改代码注释（约 9 处 Java `§N`/`s0-spec` 引用）+ `AGENTS.md` + `.trellis/spec/backend/database-guidelines.md` + `.opencode/skills/sparkora-spec-check/SKILL.md` + 3 份 docs 引用；`.reasonix/skills/**` 为历史保留（AGENTS 声明不再维护），不改。
- 无外部链接可考（仓库内引用已全部枚举，见 implement.md）。

## 目标文档结构

```
docs/
  README.md                      ← 总文档:架构/概念地图/模块索引表/快速上手/配置/已定决策
  spec/
    overview.md                  ← §0 自建vs复用、§1 路由/权限、§4 状态机、§7 决策、§7b 配置、§9 交付物、统一约定(R<T>/错误矩阵)
    project-lifecycle.md         ← §3 工作台、§5 新建、§6 项目详情
    brief-generation.md          ← §13 深度生成六阶段(简报生成核心)
    version-generation.md        ← §4 流程 + §3.3 generate/versions 契约
    preview.md                   ← §11 排版预览
    publish.md                   ← §12 公众号发布
    imitation.md                 ← §14 文章仿写
    image.md                     ← §10 配图(数据模型/标签/分类/语义检索/版本关联/API/建议/页面)
    retrieval.md                 ← §6b 知识库必查+降级可见+门槛+引用明细(跨生成链路)
    settings.md                  ← §6b-s 系统检索设置
    knowledge/
      car.md                     ← §6d 车型库数据基座
      kb.md                      ← §6c 通用汽车知识库 KB 双源检索
      news.md                    ← §15 新闻知识域
      center.md                  ← §16 知识中心浏览页
      qa.md                      ← §17 多轮对话式问答
    acceptance.md                ← §8 历史验收清单(标注为 2026-08-18 快照,不再维护)
  knowledge-base.md              ← 保留(知识基座深潜),顶部回链 README + 指向 spec/knowledge/*
  wenyan.md                      ← 保留,§引用改指 spec/preview.md § spec/publish.md
  img.md                         ← 保留(七牛图床)
  article-generation-flow.md     ← 保留并重写为「当前唯一深度链路」流程图,去 FAST 双模式
```

> `prototypes/` 保持不动（AGENTS 声明为 HTML 原型）。

## 总文档（`docs/README.md`）契约

必含：
1. 一句话定位 + 双模块（`src/` 后端 / `frontend/` 前端）架构图（mermaid）。
2. **概念地图**：创作项目 → 简报 → 多版本正文 → 配图 → 预览 → 发布；三域知识（CAR/KB/NEWS）+ 问答。
3. **模块索引表**（核心交付）：`模块 | 文档 | 权威代码路径`（后端包/关键类 + 前端视图）。
4. 全局约定：`R<T>`、错误映射矩阵、状态机总览、权限角色（ADMIN/EDITOR/VIEWER）。
5. 快速上手：指向 `AGENTS.md` 的命令（`./dev.sh`、mvn/npm）。
6. 配置总览：`.env` 变量表（汇总各模块，指向各自文档）。
7. 已定决策摘要。

## 引用迁移映射（旧 → 新）

| 旧引用 | 位置 | 新目标 |
|---|---|---|
| `s0-spec.md §10` | `ImageController.java:22`、`NewsImageClassifier.java:16`、`ImageEmbeddingTextBuilder.java:15`、`ArticleProjectController.java:318`、`ImageService.java:256` | `docs/spec/image.md` |
| `spec §11`（配图建议） | `ArticleProjectController.java:356` | `docs/spec/image.md`（**修正**：配图建议在 §10 而非 §11） |
| `spec §14` | `ArticleProjectController.java:195,228,236` | `docs/spec/imitation.md` |
| `§4` | `ArticleProjectController.java:229` | `docs/spec/overview.md`（状态机） |
| `spec §11/§12` | `WenyanThemeCatalog.java:21` | `docs/spec/preview.md` / `docs/spec/publish.md` |
| `s0-spec.md` | `AGENTS.md:11,50` | `docs/README.md` + `docs/spec/**` |
| `docs/s0-spec.md` | `.trellis/spec/backend/database-guidelines.md:215` | `docs/README.md`（规格入口） |
| `docs/s0-spec.md §N` | `docs/wenyan.md:3,81` | `docs/spec/preview.md`/`publish.md` |
| `docs/s0-spec.md` | `docs/article-generation-flow.md:5` | `docs/README.md` |
| `docs/s0-spec.md §15/16/17` | `docs/knowledge-base.md:4` | `docs/spec/knowledge/news.md`/`center.md`/`qa.md` |
| `docs/s0-spec.md` | `.opencode/skills/sparkora-spec-check/SKILL.md:3,8,12,15` | `docs/README.md` + `docs/spec/**`（索引到模块） |
| `.reasonix/skills/**` | 历史保留 | **不改** |

- `SettingEntity.java:13` 的 `§2.1` 指任务设计文档（非 s0-spec），保留或改写为中性描述（见 implement.md 判定）。

## 兼容性 / 风险

- **信息丢失风险**（最高）：1188 行迁移必须逐条核对。缓解：implement.md 建立「旧 §N → 新文档」逐节映射，check 阶段做反向完整性核对（新文档集合能覆盖旧 §0-§17 全部条目）。
- **代码注释改动**：仅注释，不影响编译；但仍跑 `mvn -q -DskipTests compile` 兜底。
- **引用漂移**：`ArticleProjectController.java:356` 的 `§11` 本就是错的（配图建议在 §10），迁移时一并修正。
- **回滚**：纯文档/注释改动，`git revert` 即可；无 DB/接口变更。
- **过渡期**：删除 `s0-spec.md` 后，任何未列入本清单的外部引用会失效；仓库内已全量枚举，无遗漏。

## 任务组织

**单一任务**（不拆父/子）：
- 16 份新文档共享同一术语体系与索引结构，拆子任务会导致跨文档一致性（术语、交叉引用、索引表）反复返工。
- 交付物可在一份 `implement.md` 里分阶段检查；check 阶段整体核对完整性。
- 若用户后续希望并行，可再拆。

## Open Questions

（无阻塞项；B 决策已定，结构已定。）
