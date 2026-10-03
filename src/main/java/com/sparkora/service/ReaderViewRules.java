package com.sparkora.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 10-02-fix-meta-leak-in-article-body R1+R2:正文「读者视角」写作契约(两条正文生成链路共用)。
 *
 * <p><b>要解决的线上缺陷</b>:简报侧 {@code fact_risks[].suggestion} 是写给<b>作者</b>的第二人称祈使指令
 * (「此表述必须删除或改为 X」「建议正文以 Y 为主」),写作链路整块注入 user prompt 后,模型会把它
 * <b>改写成第三人称陈述</b>放进正文——祈使句变成「对读者的客观事实陈述」,于是「手册未披露…完成率无法计算」
 * 被当结论说出来(version 44 第 17/25 段实测)。
 *
 * <p><b>R1 停止泄漏源</b>:不投 {@code suggestion},只把陈述性的 {@code claim} 作为
 * 「禁止写入正文的断言」清单注入;解析失败/非数组<b>一律不注入</b>(绝不按原文兜底,否则祈使句随畸形数据回流)。
 *
 * <p><b>R2 读者视角铁律</b>:黑名单 + 行为指令双写(只禁词不禁行为,模型会换词绕开);
 * 「不存在资料/手册/简报这些概念」先行切断把内部工件当叙述对象的动机。
 *
 * <p><b>为什么放在 com.sparkora.service</b>:深度写作({@code com.sparkora.deep.service.DeepWriterService})
 * 与多版本主题分支({@code VersionService})两条链路共用同一份文本,按既有先例
 * {@link LayoutRules}(深度/多版本共用排版分档)放在<b>被复用方所在的基础层</b>,
 * 避免 {@code com.sparkora.service} 反向依赖 {@code com.sparkora.deep.service}(那是叠在基础层之上的子域模块,
 * 依赖方向只允许 deep → service)。
 *
 * <p>纯静态、无状态、无 Spring 依赖,可单测(同 {@link LayoutRules} 范式);
 * 落库前的确定性清洗兜底在 {@link MetaLeakCleaner}(R3)。
 */
@Slf4j
public final class ReaderViewRules {

    private ReaderViewRules() {}

    /** 静态解析 fact_risks 用(纯解析用途,不依赖 Spring 注入的 ObjectMapper)。 */
    private static final ObjectMapper PLAIN_JSON = new ObjectMapper();

    /** claim 归一:连续空白(含换行/制表符)压成单空格——避免多行 claim 破坏「- 」清单结构。 */
    private static final Pattern WS_RUN = Pattern.compile("\\s+");

    /** claim 长度上限:超长截断,防止单条断言撑爆 prompt(与 {@code title} 列 VARCHAR(200) 同量级)。 */
    private static final int MAX_CLAIM_CHARS = 200;

    /**
     * 读者视角铁律(两条正文生成链路共用同一文本常量)。
     *
     * <p>刻意<b>不改</b>铁律 2「手册未覆盖的参数,用定性表述」与 user prompt 块头「事实手册(数值唯一来源):」——
     * 那是给模型的约束语而非素材(10-02 R4,既有测试逐字锁定);抑制泄漏靠 R1(不投 suggestion)、
     * R2(本铁律)、R3({@link MetaLeakCleaner} 落库前清洗)三层。
     */
    public static final String READER_RULES =
            com.sparkora.ai.PromptTemplateLoader.load("shared/reader-rules.st");

    /** 禁写断言块头(R1:只投 claim,不再投写给作者的 suggestion 祈使句)。 */
    private static final String FORBIDDEN_CLAIMS_HEADER =
            "【禁止写入正文的断言】(以下断言无数据支撑,正文禁止出现或作为事实陈述)：\n";

    /**
     * R1:构造「禁止写入正文的断言」块(深度写作与多版本主题分支共用)。
     *
     * <p>与简报 JSON 字段通用注入(逐项/原文兜底)的差异(刻意):<b>不按原文兜底</b>——
     * fact_risks 里含写给作者的祈使句 {@code suggestion},按原文追加等于把泄漏源原样送回素材区;
     * 解析失败/非数组一律视为无可用断言(返回 null 不注入)。
     *
     * @param factRisksJson brief.fact_risks JSON 数组 {@code [{claim,riskLevel,suggestion}]}
     * @return 块文本(以换行结尾);null/空白/{@code "[]"}/解析失败/全部 claim 为空 → null(调用方不注入)
     */
    public static String forbiddenClaimsBlock(String factRisksJson) {
        List<String> claims = factRiskClaims(factRisksJson);
        if (claims == null) return null;
        StringBuilder sb = new StringBuilder(FORBIDDEN_CLAIMS_HEADER);
        for (String c : claims) sb.append("- ").append(c).append('\n');
        return sb.toString();
    }

    /**
     * 从 fact_risks 只抽 {@code claim}(陈述性断言)列表;{@code suggestion}(祈使指令)一律丢弃。
     *
     * <p>claim 归一:连续空白压成单空格 + 长度上限 {@value #MAX_CLAIM_CHARS}——多行 claim 会让
     * 「- 」清单出现无前缀的续行,超长 claim 也会挤占 prompt 预算。
     *
     * @return claim 列表(归一后按原序);不可用时返回 null(区别于「空列表」:null=不注入)
     */
    static List<String> factRiskClaims(String factRisksJson) {
        if (factRisksJson == null || factRisksJson.isBlank()) return null;
        String trimmed = factRisksJson.trim();
        if ("[]".equals(trimmed) || "{}".equals(trimmed)) return null;
        try {
            JsonNode node = PLAIN_JSON.readTree(trimmed);
            if (node == null || !node.isArray()) {
                log.warn("fact_risks 非数组,不注入禁写断言(避免祈使句随原文回流): {}", trimmed);
                return null;
            }
            List<String> claims = new ArrayList<>();
            for (JsonNode item : node) {
                // 非对象/缺失 claim 一律跳过,不让别的字段充当断言
                if (!item.path("claim").isTextual()) continue;
                String claim = normalizeClaim(item.path("claim").asText());
                if (!claim.isEmpty()) claims.add(claim);
            }
            return claims.isEmpty() ? null : claims;
        } catch (Exception e) {
            log.warn("fact_risks 解析失败,不注入禁写断言: {}", e.getMessage());
            return null;
        }
    }

    /** claim 归一:空白压成单空格 + 超长截断(不抛)。 */
    private static String normalizeClaim(String raw) {
        String s = WS_RUN.matcher(raw).replaceAll(" ").trim();
        if (s.length() > MAX_CLAIM_CHARS) s = s.substring(0, MAX_CLAIM_CHARS);
        return s;
    }
}
