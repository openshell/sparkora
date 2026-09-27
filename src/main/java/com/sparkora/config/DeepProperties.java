package com.sparkora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 深度生成模式配置(S9):外部搜索/研究子代理。
 */
@Data
@Component
@ConfigurationProperties(prefix = "sparkora.deep")
public class DeepProperties {

    /** 研究子代理外部搜索总开关(SEARXNG+Tavily);false 时仅查本地统一知识库。 */
    private boolean searchWebEnabled = true;
    /** Tavily API 密钥(.env: DEEP_TAVILY_API_KEY 或 TAVILY_API_KEY);空则 Tavily 不可用。 */
    private String tavilyApiKey = "";   // 支持环境变量直读兜底
    /** 单子代理研究超时(ms)。 */
    private long researchTimeoutMs = 120000;
    /** 子代理数量上限(研究计划问题数超过时截断);R5(09-26)由 4 放宽为 6,检索广度来自更多独立子代理。 */
    private int maxAgents = 6;
    /**
     * 外部搜索 provider 顺序(部署级默认;运行时由 ADMIN 在系统设置覆盖)。
     * 逗号分隔,默认 {@code TAVILY,SEARXNG}(即 TAVILY_FIRST:Tavily 优先、SearxNG 兜底)。
     * 2026-09-25 显式反转旧的「SEARXNG 优先」决策:Tavily 已配置却从未被调用,且 SearxNG 上游曾全部不可用。
     */
    private String webProviderOrder = "TAVILY,SEARXNG";
    /**
     * WEB 正文补抓单条上限(09-27-tavily-extract-kind-hypotheses R1):背景题对 top URL 调 Tavily
     * {@code /extract} 取正文后，在**工具层**截断到该字符数(唯一上限，避免「工具截一次、注入再截一次」
     * 的隐形双重限制)。默认 2000。
     */
    private int webContentMaxChars = 2000;

    /** 生效正文上限(≤0 视为不截断/使用默认；防御异常配置)。 */
    public int effectiveWebContentMaxChars() {
        return webContentMaxChars > 0 ? webContentMaxChars : 2000;
    }

    /** 生效密钥:显式 DEEP_TAVILY_API_KEY 优先,否则读环境变量 TAVILY_API_KEY(.env)。 */
    public String effectiveTavilyKey() {
        // dotenv 将 .env 注入为 System property;兼容 env var 直读
        String v = System.getProperty("DEEP_TAVILY_API_KEY");
        if (v == null || v.isBlank()) v = System.getenv("DEEP_TAVILY_API_KEY");
        if (v != null && !v.isBlank()) return v;
        v = System.getProperty("TAVILY_API_KEY");
        if (v == null || v.isBlank()) v = System.getenv("TAVILY_API_KEY");
        if (v != null && !v.isBlank()) return v;
        return tavilyApiKey;
    }
}