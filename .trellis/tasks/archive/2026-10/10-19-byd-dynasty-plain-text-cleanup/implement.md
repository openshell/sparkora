# Implementation Plan - BYD Dynasty Miniapp Draft Plain Text Cleanup

## Execution Checklist

### 1. Dependency and test scaffolding

- [x] Add pinned commonmark core, tables, strikethrough, task-list and autolink dependencies to `pom.xml`.
- [x] Confirm the project compiles with Java 21 and the selected commonmark version.
- [x] Create `BydDynastyPlainTextRendererTest` before wiring the service so the conversion contract is executable.

### 2. Implement the pure renderer

- [x] Add `src/main/java/com/sparkora/service/BydDynastyPlainTextRenderer.java`.
- [x] Define package-private `TextBlock` and `ImageBlock` result variants.
- [x] Configure a single reusable commonmark parser and renderer.
- [x] Use `TextContentRenderer` with `LineBreakRendering.SEPARATE_BLOCKS`.
- [x] Add custom node renderers only for Link, Image, Strikethrough, BlockQuote, Code, ThematicBreak, HTML, tables and task-list markers.
- [x] Implement image-order sentinels and split them into ordered `TextBlock` / `ImageBlock` results.
- [x] Implement `renderInline` for titles.

### 3. Add renderer unit tests

- [x] Cover all rules listed in `design.md` with table-driven or focused JUnit tests.
- [x] Assert literal `*`, `_` and escaped Markdown are not harmed.
- [x] Assert emoji and compound emoji sequences survive cleanup.
- [x] Assert empty and image-only inputs have the expected result.

### 4. Integrate into `BydDynastyPushService`

- [x] Remove `MD_IMAGE` and the old `normalizeText` policy.
- [x] Replace `parseMessages` with the renderer's ordered blocks.
- [x] Assign `BODY-n` refs in the same order as `ImageBlock`s.
- [x] Clean the payload title with `renderInline`.
- [x] Preserve `COVER-{imageId}` insertion, payload version, image source descriptors and task state flow.
- [x] Preserve the existing Chinese empty-content error.

### 5. Update service contract tests

- [x] Extend `BydDynastyPushServiceTest` with Markdown/HTML/link/title assertions.
- [x] Assert cover and body image ordering.
- [x] Assert payload version remains 1 and Agent-facing message shapes are unchanged.
- [x] Assert the source `ArticleVersionEntity` is not mutated.
- [x] Add or adjust an empty-cleaned-content test without touching production data.

### 6. Validation

- [x] Run focused tests:

```powershell
mvn -Dtest=BydDynastyPlainTextRendererTest,BydDynastyPushServiceTest test
```

- [x] Run the full backend suite:

```powershell
mvn test
```

- [x] Run:

```powershell
git diff --check
```

- [x] Inspect the final diff for accidental edits to the Windows Agent or unrelated modules.
- [x] Confirm no real MMKV file or BYD draft was touched during tests.


## Implementation Result (2026-10-19)

- 新增 `commonmark-java 0.30.1` 与 tables / strikethrough / task-list / autolink 四个扩展，复用既有 Jsoup。
- 新增纯组件 `BydDynastyPlainTextRenderer`（有序 `TextBlock` / `ImageBlock`，标题 `renderInline`）。
- `BydDynastyPushService` 移除 `MD_IMAGE` 正则与 `normalizeText`，统一走渲染器；payload v1、`BODY-n`、`COVER-{imageId}` 保持不变。
- `BydDynastyPlainTextRendererTest`：15 个用例；`BydDynastyPushServiceTest`：12 个用例。
- 聚焦测试 27/27 通过；全量后端测试 1144/1144 通过；`git diff --check` 通过。
- 原始文章实体、真实 MMKV 与真实 BYD 草稿均未被触碰。

### 7. Trellis finish review

- [ ] Run the Trellis check workflow and review findings.
- [ ] Update backend spec if a reusable Markdown-to-text contract or dependency convention was established.
- [ ] Commit only the task files, `pom.xml`, renderer and related tests.
- [ ] Archive the task after commit and report the exact commit id.

## Risky Files and Rollback Points

| File | Risk | Rollback point |
| --- | --- | --- |
| `pom.xml` | CommonMark extension version or module mismatch | Remove the added dependencies and revert the service integration. |
| `BydDynastyPlainTextRenderer.java` | Renderer override changes paragraph/list ordering | Keep the renderer isolated behind one compile method; revert this file without DB changes. |
| `BydDynastyPushService.java` | Image order or cover behavior regression | Revert the service call site and restore the previous parser path. |
| `BydDynastyPushServiceTest.java` | Test changed production semantics unintentionally | Use the previous test assertions as compatibility baseline. |

## Pre-Start Gate

- [ ] `prd.md`, this file and `design.md` are present.
- [ ] The user approves the final planning summary.
- [ ] `implement.jsonl` and `check.jsonl` contain real spec entries.
- [ ] Task status is still `planning` until the approval is received.