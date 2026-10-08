package com.sparkora.source.service;

import com.sparkora.domain.entity.SourceChannelEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用信源来源类型目录（10-05-source-domain-retrieval E，父 design §2.6）。
 *
 * <p>NEWS 域内用 {@code sourceType} 区分来源：BYD 官方新闻 = {@code byd-news}（存量/固定），
 * 通用信源 = {@code user-source}。**粒度 = 栏目（channel）**：同一站点的「官宣销量」与「销量排行」
 * 来源基础不同（官宣为车企自报、排行可能派生自乘联会/上险），须可区分供 F 判独立交叉。
 *
 * <p>纯静态、无 Spring 依赖、可单测。当前 B 的 {@code sparkora_source_channel} 尚无 per-channel
 * {@code source_type} 列，故先按「源名 + 栏目名」做确定性兜底映射（盖世官宣/排行两类），
 * 其余一律 {@code user-source}。**B 侧补列后可无缝升级**：本目录收敛在一处，不改检索/入库链路。
 */
public final class SourceCatalog {

    /** BYD 官方新闻来源类型（存量/固定，与检索层兜底一致）。 */
    public static final String BYD_NEWS = "byd-news";
    /** 通用信源默认来源类型。 */
    public static final String USER_SOURCE = "user-source";

    /** BYD 官方新闻默认分类。 */
    public static final String CATEGORY_OFFICIAL = "官方新闻";

    /** 盖世来源类型：官宣（车企自报，可与乘联会独立交叉）。 */
    public static final String GASGOO_ANNOUNCE = "gasgoo-announce";
    /** 盖世来源类型：排行（可能派生自乘联会/上险，不计独立交叉）。 */
    public static final String GASGOO_RANKING = "gasgoo-ranking";

    /** 保留换行切块（结构化表格内容，父 design §6）用的分类集合。 */
    private static final java.util.Set<String> STRUCTURED_CATEGORIES =
            java.util.Set.of("销量数据", "投诉榜", "政策公示");

    private SourceCatalog() {
    }

    /**
     * 推导某栏目内容的 {@code sourceType}。
     *
     * @param sourceName  源名（如「盖世汽车」；可空）
     * @param channelName 栏目名（如「车企官宣销量」/「销量排行」；可空）
     * @return 来源类型串（永不为空）
     */
    public static String sourceTypeOf(String sourceName, String channelName) {
        String src = sourceName == null ? "" : sourceName.trim();
        String ch = channelName == null ? "" : channelName.trim();
        // 盖世：官宣 vs 排行（确定性关键词，大小写不敏感）
        String joined = (src + " " + ch).toLowerCase(java.util.Locale.ROOT);
        if (joined.contains("盖世") || joined.contains("gasgoo")) {
            if (ch.contains("排行") || ch.contains("榜单") || ch.contains("rank")) {
                return GASGOO_RANKING;
            }
            if (ch.contains("官宣") || ch.contains("销量") || ch.contains("announce")) {
                return GASGOO_ANNOUNCE;
            }
        }
        return USER_SOURCE;
    }

    /**
     * 该来源是否计入「独立交叉」（供 F；B 侧补列前先按 sourceType 粗判）。
     * 排行类派生自其他来源，不计独立；其余计入。
     */
    public static boolean crossCounted(String sourceType) {
        return !GASGOO_RANKING.equals(sourceType);
    }

    /**
     * 该分类是否为结构化（表格）内容——须用 {@code TextChunker} 的 {@code preserveNewlines=true}
     * 切块以保证行/列数值不丢（父 design §6）。
     */
    public static boolean isStructured(String category) {
        return category != null && STRUCTURED_CATEGORIES.contains(category.trim());
    }
}
