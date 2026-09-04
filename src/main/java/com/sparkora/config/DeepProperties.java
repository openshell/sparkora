package com.sparkora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 深度生成模式配置(S9):外部搜索/研究子代理。
 */
@Data
@Component
@ConfigurationProperties(prefix = "sparkora.ai.deep")
public class DeepProperties {

    /** 研究子代理外部搜索总开关(SEARXNG+Tavily);false 时仅查本地统一知识库。 */
    private boolean searchWebEnabled = true;
    /** Tavily API 密钥(.env: DEEP_TAVILY_API_KEY 或 TAVILY_API_KEY);空则 Tavily 不可用。 */
    private String tavilyApiKey = "";   // 支持环境变量直读兜底
    /** 单子代理研究超时(ms)。 */
    private long researchTimeoutMs = 120000;
    /** 子代理数量上限(研究计划问题数超过时截断)。 */
    private int maxAgents = 4;

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