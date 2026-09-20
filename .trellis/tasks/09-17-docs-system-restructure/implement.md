# Implement — 系统说明文档重构

## 迁移映射（旧 §N → 新文档，逐节核对用）

来源：`docs/s0-spec.md`（1188 行）。**迁移后删除原文件**。

| 旧章节 | 行范围(约) | 新文档 |
|---|---|---|
| §0 自建 vs 复用总览 | 16-33 | `docs/spec/overview.md` |
| §1 路由/权限结构 | 34-92 | `docs/spec/overview.md` |
| §2 登录（Spring Security） | 93-101 | `docs/spec/overview.md` |
| §3 工作台（3.1/3.2/3.3） | 102-161 | `docs/spec/project-lifecycle.md`（3.3 中 settings/styles 行 → `settings.md`/`style-library` 归属见下） |
| §4 状态机 | 162-191 | `docs/spec/overview.md` |
| §5 新建创作任务 | 192-200 | `docs/spec/project-lifecycle.md` |
| §6 项目详情/生成入口 | 201-208 | `docs/spec/project-lifecycle.md` |
| §6b 知识库必查+降级可见 | 209-242 | `docs/spec/retrieval.md` |
| §6b-s 系统检索设置 | 243-303 | `docs/spec/settings.md` |
| §6c 通用 KB 双源检索 | 304-344 | `docs/spec/knowledge/kb.md` |
| §6d 车型库数据基座 | 345-357 | `docs/spec/knowledge/car.md` |
| §7 已定决策 | 358-368 | `docs/spec/overview.md`（摘要）+ `docs/README.md`（索引） |
| §7b 配置来源 | 369-390 | `docs/spec/overview.md` |
| §8 验收清单 | 391-416 | `docs/spec/acceptance.md`（标注历史快照） |
| §9 交付物 | 417-425 | `docs/spec/overview.md` |
| §10 配图模块（全部子节） | 426-754 | `docs/spec/image.md` |
| §11 排版预览 | 755-820 | `docs/spec/preview.md` |
| §12 公众号发布 | 821-857 | `docs/spec/publish.md` |
| §13 深度生成模式 | 858-944 | `docs/spec/brief-generation.md` |
| §14 文章仿写 | 945-1002 | `docs/spec/imitation.md` |
| §15 新闻知识域 | 1003-1075 | `docs/spec/knowledge/news.md` |
| §16 知识中心浏览页 | 1076-1112 | `docs/spec/knowledge/center.md` |
| §17 多轮问答 | 1113-1188 | `docs/spec/knowledge/qa.md` |

> 版本生成（`generate/versions` 契约、`VersionService`、`VersionService.generate` 语义）当前散落在 §3.3/§4/§6：集中进 `docs/spec/version-generation.md`。风格库（`/api/styles`、`StyleService`、`/styles/extract`）→ `docs/spec/style-library.md`。

## 执行顺序

### 1. 建目录与总文档骨架
- [ ] `mkdir -p docs/spec/knowledge`
- [ ] 写 `docs/README.md`：定位/架构图/概念地图/模块索引表/全局约定/快速上手/配置总览/已定决策摘要。
- [ ] 模块索引表每行含：模块名 | 文档相对链接 | 权威代码路径（后端包·关键类 / 前端视图）。

### 2. 迁移 overview + project-lifecycle
- [ ] `docs/spec/overview.md`：§0/§1/§2/§4/§7/§7b/§9 + 统一约定（`R<T>`、错误矩阵、权限角色）。§4 状态机保留 mermaid/文本图。
- [ ] `docs/spec/project-lifecycle.md`：§3（工作台字段级/接口）/§5/§6。§3.3 表中 settings/styles/images 行改为「见对应文档」并链接。

### 3. 迁移创作链路
- [ ] `docs/spec/brief-generation.md`：§13 全文（六阶段/接口/工具层/笔记手册/写作回查/前端/配置/验收）。**这是本问题的核心文档**，顶部加流程 mermaid（可从 `article-generation-flow.md` 复用并更新）。
- [ ] `docs/spec/version-generation.md`：`generate/versions` 契约 + `VersionService` 语义 + 版本字段（label/styleTag/wordCount/ragStatus/factRisks/similarity_*）+ 状态推进。
- [ ] `docs/spec/preview.md`：§11。
- [ ] `docs/spec/publish.md`：§12。
- [ ] `docs/spec/imitation.md`：§14。

### 4. 迁移配图 + 检索 + 设置
- [ ] `docs/spec/image.md`：§10 全部子节（数据模型/标签/新闻分类/语义检索/版本关联/API/建议/页面职责）。
- [ ] `docs/spec/retrieval.md`：§6b（ragStatus 枚举/门槛/引用明细/诚实边界）。
- [ ] `docs/spec/settings.md`：§6b-s。
- [ ] `docs/spec/style-library.md`：风格库（§3.3 相关行 + `StyleController`/`StyleService` 契约）。

### 5. 迁移知识域
- [ ] `docs/spec/knowledge/car.md`（§6d）、`kb.md`（§6c）、`news.md`（§15）、`center.md`（§16）、`qa.md`（§17）。
- [ ] `docs/spec/acceptance.md`（§8，首页标注「2026-08-18 历史快照，不再维护」）。

### 6. 更新既有文档与引用（删除 s0-spec 前完成）
- [ ] `docs/article-generation-flow.md`：重写为当前唯一深度链路（去 FAST 双模式），`s0-spec` 引用 → `docs/README.md`。
- [ ] `docs/knowledge-base.md:4`：§15/16/17 引用 → `docs/spec/knowledge/*.md`；顶部加回链 README。
- [ ] `docs/wenyan.md:3,81`：§11/§12 → `docs/spec/preview.md`/`publish.md`。
- [ ] `AGENTS.md:11,50`：`docs/s0-spec.md` → `docs/README.md` + `docs/spec/**`。
- [ ] `.trellis/spec/backend/database-guidelines.md:215`：同上。
- [ ] `.opencode/skills/sparkora-spec-check/SKILL.md:3,8,12,15`：`docs/s0-spec.md` → `docs/README.md` + 模块索引。
- [ ] 代码注释更新：`ImageController.java:22`、`NewsImageClassifier.java:16`、`ImageEmbeddingTextBuilder.java:15`、`ImageService.java:256` → `docs/spec/image.md`；`ArticleProjectController.java:195,228,236` → `docs/spec/imitation.md`；`:229` §4 → `docs/spec/overview.md`；`:318` → `docs/spec/image.md`；`:356` §11 → `docs/spec/image.md`（修正编号）；`WenyanThemeCatalog.java:21` §11/§12 → `docs/spec/preview.md`/`publish.md`；`SettingEntity.java:13` 若确指任务设计文档则保留/中性化。
- [ ] `.reasonix/skills/**`：历史保留，不改。

### 7. 删除 s0-spec
- [ ] `git rm docs/s0-spec.md`（仅在所有引用更新后）。
- [ ] 全仓最终 grep `s0-spec` 确认仅存 `.reasonix/**`（历史）与 `.trellis/tasks/**`（任务工件）。

## 验证命令

```bash
# 引用完整性:除历史/任务工件外不应再有 s0-spec 引用
grep -rn "s0-spec" --include=*.md --include=*.java --include=*.vue --include=*.js . \
  | grep -v node_modules | grep -v ".trellis/tasks" | grep -v ".reasonix" | grep -v ".trellis/workspace"
# 死链检查:索引表内每个链接目标存在
grep -oE '\]\(([^)]+\.md[^)]*)\)' docs/README.md
# 仅注释改动时兜底编译
mvn -q -DskipTests compile          # 若触碰 Java 注释
```

信息完整性反向核对：对 §0-§17 每个旧章节，确认其关键表/枚举/接口行都能在新文档找到（check 阶段逐节）。

## 风险点 / 回滚

- 信息丢失：靠上表逐节迁移 + check 反向核对。
- 引用遗漏：第 6 步完成后跑最终 grep。
- 回滚：纯文档/注释，`git revert` 即可。

## 提交前检查

- [ ] 无 `s0-spec` 悬空引用（除 `.reasonix` 历史）。
- [ ] 总文档模块索引表链接全部有效（无死链）。
- [ ] 新文档全部含回链 `docs/README.md`。
- [ ] 中文正文；无代码行为变更。
