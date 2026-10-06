package com.sparkora.deep.search;

/**
 * 外部搜索 provider 枚举(09-25-brief-web-search;10-04-serper-provider 追加 SERPER)。
 * 值域 TAVILY / SEARXNG / SERPER;不支持 BOTH 双源聚合(不让付费 provider 无条件重复调用)。
 *
 * <p>10-04 A-R7:{@code SERPER} <b>追加在枚举末尾</b>(不插入中间)——现有
 * {@code WebProviderOrder.DEFAULT_RAW="TAVILY,SEARXNG"} 不变;且 {@code extract} 曾按枚举声明序
 * 遍历,追加末尾对既有行为影响面最小。
 */
public enum WebProvider {

    /** Tavily(付费/密钥制,默认首选)。 */
    TAVILY,
    /** SearxNG(本机聚合搜索,默认兜底)。 */
    SEARXNG,
    /** Serper(付费/密钥制,Google 结果代理;Header {@code X-API-KEY} 认证)。 */
    SERPER;

    /**
     * 大小写不敏感解析;未知值抛 {@link IllegalArgumentException}(不静默吞掉配置错误)。
     */
    public static WebProvider from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("搜索 provider 不能为空");
        }
        try {
            return WebProvider.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的搜索 provider: " + raw);
        }
    }
}
