# 提取共享分节档位 LayoutRules 并统一 VersionService

## Goal

消除两处正文生成链路在「`##` 小标题分节数」上的不一致：把 `DeepWriterService` 内的分节档位（`SectionSpec`/`sectionSpec`）提取为共享类，`VersionService`（仿写/多版本）从写死「2~4 个」改为按项目 `wordCountTarget` 自适应，与深度写作同档。

## Background（已确认事实，含证据锚点）

- **B1 · 深度写作已自适应**：`DeepWriterService` 有 `record SectionSpec`、`DEFAULT_WORD_COUNT_TARGET=1500`、`static sectionSpec(Integer)`/`layoutRule(Integer)`（`DeepWriterService.java:93-120`），分档 ≤800→2~3 / 801~1800→3~5 / 1801~3000→5~8 / >3000→8~12（每节 2~3、顶档 2~4）。
- **B2 · 仿写链路写死**：`VersionService.generateOne` 的 `layoutRules` 硬编码「全文用 **2~4 个**「## 小标题」分节,每节 2~3 段」（`VersionService.java:184-187`），与长度无关；TOPIC 与 IMITATION 两分支共用该串（`:199`/`:206`）。
- **B3 · 两链路分节格式不同**：深度链路是**单行分号串**（`layoutRule`），而 `VersionService` 是**三段 bullet 列表**（`\n- …；\n- …；\n- …。`）。故只共享**分类结果**，不强行统一文案格式（避免无关 diff 与既有断言回归）。
- **B4 · VersionService 已有目标字数**：`VersionService` 在 `buildUserPrompt`/`buildImitationPrompt` 已用 `p.getWordCountTarget()`（`:266`/`:298`，null→1500），`generateOne(p, …)` 持有项目快照 `p`，可直接取用。
- **B5 · 深度侧测试引用内部类型**：`DeepWriterServicePromptTest` 直接引用 `DeepWriterService.SectionSpec`/`DeepWriterService.sectionSpec`（`:385`），迁移后须改引用共享类。

## Requirements

- **R1 · 提取共享类**：新增 `com.sparkora.service.LayoutRules`（`final`、私有构造、纯静态无依赖），承载：`DEFAULT_WORD_COUNT_TARGET`、`SectionSpec` record、`sectionSpec(Integer)`（分档，null/≤0→1500，不抛）、`normalizeTarget(Integer)`（null/≤0→1500）。分档表与 `DeepWriterService` 现状**逐值等价**。
- **R2 · DeepWriterService 复用**：删除其内部 `SectionSpec`/`sectionSpec`/`DEFAULT_WORD_COUNT_TARGET`，改为委托 `LayoutRules`；`layoutRule` 输出文案**逐字不变**（单行分号串），行为零回归。
- **R3 · VersionService 自适应**：`generateOne` 的 `layoutRules` 中「全文用 2~4 个…每节 2~3 段」改为按 `p.getWordCountTarget()` 经 `LayoutRules.sectionSpec` 生成；其余两行 bullet（加粗、单段行数）逐字保留；格式仍为 bullet 列表。
- **R4 · 测试迁移与新增**：
  - `DeepWriterServicePromptTest` 的 `assertSpec` 改引用 `LayoutRules`；深度侧行为断言不变。
  - 新增 `LayoutRulesTest`：纯函数边界 `null/-1/0/800/801/1800/1801/3000/3001/10000/Integer.MAX_VALUE`，不抛、逐值对齐。
  - 新增 `VersionService` 断言：给定 `wordCountTarget` 时，`chatJson` 的 system prompt 含对应档位（如 1500→`3~5`、5000→`8~12`；且不再出现写死的 `2~4 个`）；null→1500 档。

## Acceptance Criteria

- [ ] **AC-01（R1）**：`LayoutRules.sectionSpec` 边界 `null/-1/0/800/801/1800/1801/3000/3001/10000` 与 `Integer.MAX_VALUE` 返回值与既定分档表一致，且不抛。
- [ ] **AC-02（R2）**：`DeepWriterService` 输出的 system prompt 排版「节数行」与迁移前**逐字相同**（1500→`3~5`、≤800→`2~3`、>3000→`8~12`/`2~4`）；`DeepWriterServicePromptTest` 全绿。
- [ ] **AC-03（R3）**：`VersionService` system prompt 的小标题档位随 `p.getWordCountTarget()` 变化（1500→`3~5` 个 / `2~3` 段；5000→`8~12` 个 / `2~4` 段；null→1500 档）；其余排版 bullet 文案逐字保留。
- [ ] **AC-04（R4）**：新增 `LayoutRulesTest` 与 `VersionService` 分档断言全绿；既有 `VersionServiceAsyncTest` 不回归。
- [ ] **AC-05**：`mvn -q -DskipTests compile` 通过；`mvn test` 全绿（基线 488 + 新增）。
- [ ] **AC-06（红线）**：不改数据库 schema / 无 Flyway 迁移；不改 `/deep/*`、`/generate/versions` 响应主结构；不改项目状态值域；不新增依赖/密钥；不改变 `DeepWriterService` 与 `VersionService` 的对外契约（仅内部布局文案生成方式）。

## Out of Scope

- 统一两链路的排版**文案格式**（单行 vs bullet）——只共享分类结果。
- `audienceRefine`/`selectedTitle`/`extraInfo` 注入、大纲可读化、生成后覆盖自检等其它写作缺口。
- 深度链路既有分档表的取值调整（保持现状）。

## Open Questions

- （无。范围与共享边界已定。）
