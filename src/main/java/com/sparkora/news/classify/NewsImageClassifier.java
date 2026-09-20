package com.sparkora.news.classify;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 比亚迪新闻图片主题分类器（09-15 img-classify，子A）。
 *
 * 纯静态、无 Spring 依赖、零 AI 调用：输入新闻标题 → 命中主题集合（保序，0~n，允许重叠）。
 * 词表是受控产品定义（低频变更），以代码常量固化（可版本化、可单测，避免运行时配置漂移）；
 * 改词表 = 改代码发版（见 docs/spec/image.md）。
 *
 * 标签命名空间（与用户自由标签隔离，筛选下拉可按前缀分组）：
 *  - 主题标签 {@code 主题/<主题名>}
 *  - 年份标签 {@code 年份/<年>}（由来源新闻 publish_date 派生）
 * 无命中主题时只出年份标签；取不到年份则不出年份标签（不强制归「其他」，避免噪音标签）。
 */
public final class NewsImageClassifier {

    /** 主题标签命名空间前缀。 */
    public static final String THEME_PREFIX = "主题/";
    /** 年份标签命名空间前缀。 */
    public static final String YEAR_PREFIX = "年份/";

    /**
     * 受控主题词表（保序 = 展示序）：主题名 → 关键词正则。
     * 词表经真实 167 条标题回归校准（实测结果见任务 implement.md「实测词表调整」）。
     */
    private static final Map<String, Pattern> THEMES = new LinkedHashMap<>();

    static {
        THEMES.put("销量", Pattern.compile("销售"));
        // 出海：区域词 + 国别/城市词。实测 167 条回归时补入「进入/登陆 <国> 市场」类标题缺失的国别词
        // （智利/罗马尼亚/瑞士/尼日利亚/柬埔寨/贝宁/加蓬/韩国/英国），见任务 implement.md「实测词表调整」
        THEMES.put("出海", Pattern.compile("海外|出海|出口|全球化|国际化|欧洲|拉美|东南亚|巴西|泰国|印尼|印度|日本"
                + "|澳洲|澳大|乌兹|匈牙利|墨西哥|文莱|哥伦比亚|香港|德国|慕尼黑|东京|曼谷|首尔|韩国|英国"
                + "|智利|罗马尼亚|瑞士|尼日利亚|柬埔寨|贝宁|加蓬"));
        THEMES.put("合作签约", Pattern.compile("合作|签约|携手|战略"));
        THEMES.put("技术发布", Pattern.compile("发布|技术|刀片|云辇|智驾|平台|闪充|芯片|系统"));
        // 「万辆」「纪录」之外的「下线」也是产量里程碑核心词；「万」字不单列（会误命中「万」相关数量词）
        THEMES.put("里程碑", Pattern.compile("下线|里程碑|万辆|纪录"));
        // 「荣」「获」「榜」为宽泛词，语义仍属荣誉（回归确认无「获客」类误命中）；「冠军」独立于「获」
        THEMES.put("荣誉", Pattern.compile("荣|获|奖|榜|500强|冠军"));
        THEMES.put("财报ESG", Pattern.compile("财报|业绩|ESG"));
        THEMES.put("车展上市", Pattern.compile("上市|首发|车展|亮相"));
        THEMES.put("社会责任", Pattern.compile("捐赠|慈善|公益|驰援|救灾|基金"));
    }

    private NewsImageClassifier() {
    }

    /** 主题词表（有序，只读视图；供回归验证与展示分组）。 */
    public static Map<String, Pattern> themes() {
        return java.util.Collections.unmodifiableMap(THEMES);
    }

    /**
     * 标题 → 命中主题集合（按词表顺序保序，0~n 个，允许一词多主题）。
     * title 为 null/空白 → 空列表（无命中不打主题标签）。
     */
    public static List<String> classifyThemes(String title) {
        List<String> hits = new ArrayList<>();
        if (title == null || title.isBlank()) return hits;
        // 归一化：全角括号/空格不影响关键词匹配，仅做 trim（正则均为中文/ASCII 词，无需大小写处理）
        String t = title.trim();
        for (Map.Entry<String, Pattern> e : THEMES.entrySet()) {
            if (e.getValue().matcher(t).find()) hits.add(e.getKey());
        }
        return hits;
    }

    /**
     * 标题 + 发布日期 → 标签列表（{@code 主题/<名>…} + {@code 年份/<年>}）。
     * publishDate 为 null → 只出主题标签；标题无命中主题且无日期 → 空列表。
     */
    public static List<String> toTags(String title, LocalDate publishDate) {
        List<String> tags = new ArrayList<>();
        for (String theme : classifyThemes(title)) tags.add(THEME_PREFIX + theme);
        if (publishDate != null) tags.add(YEAR_PREFIX + publishDate.getYear());
        return tags;
    }

    /** 便捷重载：新闻表 publish_date 为 LocalDateTime，仅取日期部分派生年份。 */
    public static List<String> toTagsFrom(String title, LocalDateTime publishDate) {
        return toTags(title, publishDate == null ? null : publishDate.toLocalDate());
    }
}
