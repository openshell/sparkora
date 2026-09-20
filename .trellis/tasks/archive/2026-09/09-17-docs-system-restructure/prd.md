# 系统说明文档重构

## Goal

把当前「单块 1188 行 `docs/s0-spec.md` + 零散模块文档」重构为**总览 → 模块**的系统说明文档结构：

- 一份总文档（架构 + 快速导航 + 概念地图），作为阅读入口；
- 每个功能模块一份独立文档，含职责、契约、关键实现路径；
- 总文档索引到各模块文档，模块文档回链总文档；
- 消除现有文档的过时内容与编号漂移。

用户价值：新成员/AI 助手能在一个入口快速定位「简报生成」「发布」「知识库」等任意模块的权威说明，而不必在巨石文档里翻找；模块变更只需改对应文档。

## Background（09-17 勘察结论）

### 现状文档

| 文档 | 行数 | 定位 | 问题 |
|---|---|---|---|
| `docs/s0-spec.md` | 1188 | 唯一权威规格（路由/权限/字段/接口/状态机 + 17 模块） | 巨石：总纲与细节混杂，无入口，检索困难；§ 编号已被代码注释引用 |
| `docs/article-generation-flow.md` | 88 | 创作链路流程图 | **已过时**（仍写 FAST/DEEP 双模式，实际 FAST 已封死、唯一深度链路） |
| `docs/knowledge-base.md` | 136 | 知识基座 + 多轮问答 | 单模块，无索引关联 |
| `docs/wenyan.md` | 105 | wenyan 主题与发布机制 | 单模块 |
| `docs/img.md` | 19 | 七牛图床接入 | 单模块 |

- `README.md` 仅有 `# sparkora` 一行；`AGENTS.md:11` 声明 `docs/s0-spec.md` 为唯一权威规格。
- `docs/s0-spec.md` 当前章节：§0 总览、§1 路由/权限、§2 登录、§3 工作台、§4 状态机、§5 新建、§6 项目详情、§6b/6b-s/6c/6d、§7 决策、§7b 配置、§8 验收、§9 交付物、§10 配图、§11 预览、§12 发布、§13 深度生成、§14 仿写、§15 新闻域、§16 知识中心、§17 问答。
- 代码注释以 `docs/s0-spec.md §N` 引用章节（如 `ImageController.java:22` §10、`ArticleProjectController.java:195` §14、`WenyanThemeCatalog.java:21` §11/§12），共约 20 处引用 `s0-spec`，其中 `src`/`frontend/src` 内 3 处 + 更多内联 `§N`。

### 系统模块（代码边界）

- 后端包：`ai` / `article` / `car` / `common` / `config` / `deep` / `domain` / `image` / `kb` / `news` / `qa` / `security` / `service` / `storage` / `web` / `wenyan`。
- 控制器：`ArticleProject` / `Auth` / `CarModel` / `Deep` / `Image` / `KbDoc` / `News` / `Qa` / `Setting` / `Style`。
- 服务：`BriefService` / `VersionService` / `PreviewService` / `PublishService` / `ImitationService` / `ImageService` / `IllustrationSuggestionService` / `QiniuService` / `SettingService` / `StyleService` / `WenyanServerService` / `WenyanThemeCatalog` 等。
- 前端视图：工作台/新建/项目四步（`StepBrief`/`StepVersions`/`StepPreview`/`StepPublish`）+ `project/deep/*`（`DeepPlanCard`/`ClarifyForm`/`ResearchProgress`/`FactSheetSummary`/`CitationList`）+ 知识中心（`CarLibrary`/`CarDetail`/`CarSync`/`KbLibrary`/`KnowledgeCenter`/`knowledge/*`）+ 图库（`ImageLibrary`）+ 问答（`QaChat`）+ 风格库（`StyleLibrary`）+ 设置（`SettingsView`）+ 登录。

### 核心流程事实（澄清用）

- **简报生成 = 深度模式唯一链路**（FAST 入口已封死 410）。六阶段：①clarify 异步落 PLANNING 占位 → ②澄清锁定 → ③并行子代理研究（KB/WEB）→ ④事实手册 → ⑤`BriefService.generateFromFactSheet` 自动生成简报（复用同条 DEEP brief 行）→ ⑥（跳过简报时）深度写作 + 数值回查。
- 权威契约当前仅在 `docs/s0-spec.md §13`。

## Requirements

### R1 总览文档

- 新增一份系统说明总文档（`docs/README.md` 或等价入口），含：
  - 系统定位与双模块（后端 `src/` / 前端 `frontend/`）架构概览；
  - 概念地图（创作项目 / 简报 / 版本 / 配图 / 预览 / 发布 / 三域知识 / 问答）；
  - **模块索引表**：模块 → 文档链接 → 权威代码路径（包/关键类/前端视图）；
  - 快速上手（dev.sh、编译/构建命令链回 `AGENTS.md`）。

### R2 模块文档拆分

- 将 `docs/s0-spec.md` 按模块拆为独立文档（粒度见 design.md：如 `brief-generation` / `version-generation` / `preview` / `publish` / `knowledge/` / `image` / `qa` / `style` / `settings` / `imitation` / `project-lifecycle`）。
- 每份模块文档含：职责、字段级/接口契约、状态机影响、关键实现路径、已知限制。
- 所有原有契约信息**不丢失**（字段、接口、状态、枚举、验收项逐条可追溯）。

### R3 模块文档 ↔ 总览互链

- 总览索引到模块；每个模块文档顶部回链总览。
- 代码/文档中现有 `§N` 引用需可继续定位（见 R5）。

### R4 过时内容修正

- `docs/article-generation-flow.md` 更新为**当前唯一深度链路**（去除 FAST 双模式叙述），或并入模块文档并保留链接。
- 修正现有编号/契约漂移（勘察发现：`ArticleProjectController.java:356` 注释称配图建议在 spec §11，实际 §11 为排版预览；spec 内部 `§N` 交叉引用需在拆分后重新指向）。

### R5 引用迁移（决策 B：彻底迁移移除）

- **删除 `docs/s0-spec.md`**：全部有效内容拆分进新模块文档。
- 同步更新全部引用（代码注释 / `AGENTS.md` / `.trellis/spec/backend/database-guidelines.md` / `.opencode/skills/sparkora-spec-check/SKILL.md` / 既有 docs）指向新文档。
- `.reasonix/skills/**` 为历史保留（AGENTS 声明不再维护），不改。
- 修正既有引用漂移（`ArticleProjectController.java:356` 的 §11 应为配图建议，属 §10）。

### R6 既有单模块文档归位

- `docs/knowledge-base.md` / `docs/wenyan.md` / `docs/img.md` 纳入新索引结构（可保留原文件 + 从总览与模块文档链接，或迁移；见 design.md）。

## Acceptance Criteria

- [ ] 存在一份总览文档，含模块索引表（模块 → 文档 → 权威代码路径），从仓库根可发现。
- [ ] 每个功能模块有独立文档，顶部回链总览。
- [ ] `docs/s0-spec.md` 中的全部字段级/接口/状态机/枚举信息在新结构中可追溯，无信息丢失（逐条反向核对 §0-§17）。
- [ ] `docs/s0-spec.md` 已删除；全仓 grep 后仅 `.reasonix/**`（历史）与 `.trellis/tasks/**`（任务工件）仍含 `s0-spec`。
- [ ] `docs/article-generation-flow.md` 与当前实现一致（唯一深度链路，无 FAST 双模式误导）。
- [ ] 所有原 `s0-spec`/`§N` 引用已更新为指向新模块文档。
- [ ] `AGENTS.md` 的「唯一权威规格」表述与文档结构一致（指向 `docs/README.md` + `docs/spec/**`）。
- [ ] 总文档模块索引表无死链；每个模块文档回链总览。
- [ ] 触碰 Java 注释时 `mvn -q -DskipTests compile` 通过；无代码行为变更。
- [ ] 所有文档中文正文，符合仓库习惯。

## Out of Scope

- 不重构代码；不改接口/字段/状态机行为。
- 不重写业务逻辑文档之外的教程/营销文案。
- 不做多语言/静态站点生成（除非后续单独提出）。
