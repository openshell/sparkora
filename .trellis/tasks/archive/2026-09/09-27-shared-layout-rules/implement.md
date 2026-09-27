# 执行计划：提取共享分节档位 LayoutRules 并统一 VersionService

## 1. 新增共享类 `com.sparkora.service.LayoutRules`

```java
package com.sparkora.service;

import lombok.NoArgsConstructor;   // 或私有构造

/** 09-27-shared-layout-rules:正文排版「## 小标题分节」档位(深度写作与多版本/仿写共用)。 */
public final class LayoutRules {
    private LayoutRules() {}

    public static final int DEFAULT_WORD_COUNT_TARGET = 1500;

    /** 分节档位(小标题数区间 + 每节段数区间,纯字符串)。 */
    public record SectionSpec(String headings, String parasPerSection) {}

    /** 目标字数归一:null/≤0 → 1500(与两链路既有口径一致)。 */
    public static int normalizeTarget(Integer targetWords) {
        return (targetWords == null || targetWords <= 0) ? DEFAULT_WORD_COUNT_TARGET : targetWords;
    }

    /** 分档:≤800→2~3,801~1800→3~5,1801~3000→5~8,>3000→8~12(每节 2~3,顶档 2~4)。纯函数,不抛。 */
    public static SectionSpec sectionSpec(Integer targetWords) {
        int n = normalizeTarget(targetWords);
        if (n <= 800) return new SectionSpec("2~3", "2~3");
        if (n <= 1800) return new SectionSpec("3~5", "2~3");
        if (n <= 3000) return new SectionSpec("5~8", "2~3");
        return new SectionSpec("8~12", "2~4");
    }
}
```

## 2. `DeepWriterService` 改造（R2，行为零回归）

- 删除内部 `record SectionSpec`、`DEFAULT_WORD_COUNT_TARGET`、`sectionSpec`。
- `layoutRule(Integer)` 改为 `LayoutRules.SectionSpec s = LayoutRules.sectionSpec(...)`，**输出文案逐字不变**（单行分号串，含尾部 `\n`）。
- user prompt 的 `目标字数：` 与默认值改用 `LayoutRules.normalizeTarget`（输出值不变）。
- 确认 `layoutRule`/`normalizeTarget` 仍被 `write` 使用。

## 3. `VersionService` 改造（R3）

`generateOne`（`VersionService.java:184-187`）的 `layoutRules`：

```java
LayoutRules.SectionSpec sec = LayoutRules.sectionSpec(p.getWordCountTarget());
String layoutRules = "\n\n排版铁律(公众号正文可读性,必须遵守):"
        + "\n- 全文用 " + sec.headings() + " 个「## 小标题」分节,每节 " + sec.parasPerSection() + " 段,禁止整篇无分节;"
        + "\n- 关键数据、核心结论用 **加粗** 突出,每节至少一处;"
        + "\n- 单段不超过 5 行,长段拆分。";
```

- 两个 bullet（加粗 / 单段行数）逐字保留；仅首行数字动态化。
- `p` 已由 `generateOne` 持有，无新增查询。

## 4. 测试

- `DeepWriterServicePromptTest`：`assertSpec`（`:385`）改引用 `LayoutRules.SectionSpec`/`LayoutRules.sectionSpec`；其余断言不动。
- 新增 `LayoutRulesTest`：边界 `null/-1/0/800/801/1800/1801/3000/3001/10000/Integer.MAX_VALUE`。
- 新增 `VersionService` 分档断言（可并入 `VersionServiceAsyncTest`）：构造 IMITATION/TOPIC 项目 + 指定 `wordCountTarget`，捕获 `chatJson` system，断言含对应档位且不含写死 `2~4 个`；null→1500 档。

## 验证

```bash
mvn -q -DskipTests compile
mvn test
# 前端未改则免
```

## 风险 / 回滚点

- `DeepWriterService.layoutRule` 文案必须逐字不变（测试 `分节_*` 与「两行间无空行」断言守着）。
- `VersionService` 的 `layoutRules` 被 TOPIC 与 IMITATION 两分支共用（`:199`/`:206`）——两分支行为须一致。
- `VersionServiceAsyncTest` 现有 `VERSION_JSON` 桩不受影响；新增断言勿改既有用例语义。
- 回滚：还原 3 文件（LayoutRules 可保留或删除）。

## start 前检查

- [x] 用户已授权按推荐方案执行。
- [x] `implement.jsonl` / `check.jsonl` 已 curate。
