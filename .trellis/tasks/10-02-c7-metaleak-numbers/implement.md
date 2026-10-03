# Implement — C7 元话语根因治理 + verifyNumbers 归一

## 0. 现状（勘察结论，决定实际范围）

- **AC1（`fact_risks[].suggestion` 不进正文素材上下文）已由前置任务
  `10-02-fix-meta-leak-in-article-body` 落地**：`DeepWriterService.buildUserPrompt` 用
  `ReaderViewRules.forbiddenClaimsBlock(...)`（只抽陈述性 `claim`，丢弃祈使句 `suggestion`）替代了
  旧的整块 `事实风险:` 注入；`DeepWriterServicePromptTest` 已有逐字断言（`suggestion` 原文不得进 prompt）。
- **AC2（`MetaLeakCleaner` 降为最后防线）已落地**：`MetaLeakCleaner.cleanForPersist` 作为落库前
  最后防线（清洗致空回退原文），有 `MetaLeakCleanerTest` 覆盖。
- **AC3（`verifyNumbers` 数值归一化）未完成**：`DeepWriterService.verifyNumbers`（:496-512）仍用
  `haystack.contains(raw) && haystack.contains(num)` **子串匹配**，存在 `"1200"` 命中 `"12000"` 的
  **漏报**；且 `20万` vs `200000`、`33.21` vs `33.21%` 这类同值不同写法会**误报**。

→ **C7 的唯一代码交付 = AC3**；AC1/AC2 以「验证 + 回归锁定」方式确认，不重复改。

## 1. 交付项：`verifyNumbers` 改数值归一化比对

### 1.1 目标

内容中的每个数值 token，与「事实手册全文的数值签名集合」按 **归一化后的数值**（去千分位、`万/亿`
换算、`BigDecimal.stripTrailingZeros`）比对；单位（`km/kWh/%/辆` 等）在两侧都视为不参与比较。

- 修复漏报：内容 `1200`，手册只有 `12000` → 归一后 `1200 ∉ {12000}` → **报 high 风险**（旧实现漏）。
- 修复误报：内容 `200000`，手册写 `20万` → 归一后两侧均 `200000` → **不报**（旧实现报）。
- 单位等价：内容 `33.21%`，手册 `33.21%` → 归一 `33.21` → 不报。

### 1.2 实现（同包复用，最小改动）

`DeepWriterService` 与 `ClaimSimilarity` 同在 `com.sparkora.deep.service`，直接复用其既有的
**包级** `static List<String> numberValues(String...)`（= 归并链路已在用的同一套数值签名，
保证「归一」口径全局一致，不新造第二套）。

```java
List<String> verifyNumbers(String content, String factSheetJson) throws Exception {
    JsonNode sheet = json.readTree(factSheetJson == null ? "{}" : factSheetJson);
    // 手册全文数值签名集合(与 claim 归并同一套归一口径)
    Set<String> known = new HashSet<>(ClaimSimilarity.numberValues(sheet.toString()));
    List<String> unknown = new ArrayList<>();
    Matcher m = NUMBER_IN_TEXT.matcher(content);   // 保留既有抽取正则
    while (m.find()) {
        String raw = m.group().trim();
        String num = raw.replaceAll("[ ,万]", "");   // 保留既有粗筛(过滤孤立 0-9)
        if (num.length() < 2 || "0".equals(num)) continue;
        String canon = canonicalNumber(raw);        // 归一:万/亿/千分位/小数
        if (canon == null || !known.contains(canon)) {
            if (!unknown.contains(raw)) unknown.add(raw);
        }
    }
    return unknown;
}

/** 复用 ClaimSimilarity 归一:返回首个数值签名;无数字→null。 */
static String canonicalNumber(String raw) {
    List<String> v = ClaimSimilarity.numberValues(raw);
    return v.isEmpty() ? null : v.get(0);
}
```

- **保留** 既有 `NUMBER_IN_TEXT` 正则与 `num.length()<2 || "0"` 粗筛（行为面尽量小）。
- **替换** 的仅是「是否收录」判定：子串 `contains` → 归一化集合 `contains`。
- `canonicalNumber` 抽为包级 `static` 纯函数，便于直接单测。

### 1.3 边界与语义

- 手册为 `null/{}`（无手册）→ `known` 为空 → 所有数值报 high（与既有「无手册时全报」一致）。
- 归一解析失败 → `ClaimSimilarity.normalizeNumber` 内部回退原 token，签名仍可比，**不抛**。
- 数值去重：`unknown` 保留原始 token 形态（与既有 `factRisks.claim` 文案一致）。

## 2. 测试

- `DeepWriterServicePromptTest`（新增/补充）：
  - `verifyNumbers_1200与12000不再漏报`：content 含 `1200`、sheet 含 `12000` → 返回含 `1200`。
  - `verifyNumbers_万与千分位等价不误报`：content `200000`、sheet `20万` → 空；content `33.21%`、sheet `33.21%` → 空。
  - `verifyNumbers_收录值正常通过`：content `2000`、sheet `第2000座` → 空。
- 既有 `ReaderViewRulesTest`/`MetaLeakCleanerTest`/`DeepWriterServicePromptTest` 的 AC1/AC2 断言保持全绿（回归锁定）。

## 3. 验证命令

```bash
mvn -q -DskipTests compile
mvn test
```

## 4. 回退点

- 单文件 `DeepWriterService.java` + 测试；`git revert` 该 `feat(C7)` 提交即回到子串匹配。

## 5. 范围外

- FactSheet 归并算法本身、`ClaimSimilarity` 的相似度/阈值、禁词表内容策略、`MetaLeakCleaner` 正则内容。
