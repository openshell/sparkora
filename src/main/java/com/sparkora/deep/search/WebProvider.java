package com.sparkora.deep.search;

/**
 * 外部搜索 provider 枚举(09-25-brief-web-search)。
 * 值域仅 TAVILY / SEARXNG;MVP 不支持 BOTH 双源聚合(不让付费 provider 无条件重复调用)。
 */
public enum WebProvider {

    /** Tavily(付费/密钥制,默认首选)。 */
    TAVILY,
    /** SearxNG(本机聚合搜索,默认兜底)。 */
    SEARXNG;

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
