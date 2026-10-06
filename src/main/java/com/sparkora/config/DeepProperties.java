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
    /**
     * Tavily API 端点(10-04-serper-provider A-R6):默认官方 {@code https://api.tavily.com}。
     * 可配置以便切换中转端点，无需改代码重编。生效值经 {@link #effectiveTavilyApiBase()}
     * property→env→字段兜底。末尾斜杠在 effective 方法内归一(避免拼出 {@code //search})。
     */
    private String tavilyApiBase = "https://api.tavily.com";
    /** Serper API 密钥(.env: DEEP_SERPER_API_KEY 或 SERPER_API_KEY);空则 Serper 不可用。 */
    private String serperApiKey = "";
    /**
     * Serper API 端点(10-04-serper-provider A-R2):默认官方 {@code https://google.serper.dev}。
     * 中转端点为 {@code https://search.604020.xyz/serper}(路径前缀不同)——故本值必须能携带路径段，
     * 严禁用 URI 解析后丢弃 path。生效值经 {@link #effectiveSerperApiBase()} 兜底并归一末尾斜杠。
     */
    private String serperApiBase = "https://google.serper.dev";
    /** Serper 地域参数 gl(默认 {@code cn})；空白表示不下发该键，交由 provider 自身默认。 */
    private String serperGl = "cn";
    /** Serper 语言参数 hl(默认 {@code zh-cn})；空白表示不下发该键。 */
    private String serperHl = "zh-cn";
    /**
     * 是否允许时效题走 Serper {@code /news} 垂直(10-04-serper-provider A-R3):默认 {@code true}。
     * 关闭时所有问题强制走 {@code /search}(web 垂直)——部署级零回归开关。
     * 注意:仅 Serper 支持垂直；Tavily/SearxNG 的 {@code searchVertical} 默认委托 {@code search}。
     */
    private boolean webVerticalNewsEnabled = true;

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

    /** 生效 Tavily 端点:显式 DEEP_TAVILY_API_BASE_URL 优先,否则字段默认(官方端点)。 */
    public String effectiveTavilyApiBase() {
        String v = firstNonBlank(System.getProperty("DEEP_TAVILY_API_BASE_URL"),
                System.getenv("DEEP_TAVILY_API_BASE_URL"),
                System.getProperty("TAVILY_API_BASE_URL"),
                System.getenv("TAVILY_API_BASE_URL"));
        if (v == null) v = tavilyApiBase;
        String base = v == null ? "" : v.trim();
        if (base.isEmpty()) base = "https://api.tavily.com";
        // 末尾斜杠归一:避免拼出 //search(中转端点可能带/不带尾斜杠)
        while (base.endsWith("/") && base.length() > 1) base = base.substring(0, base.length() - 1);
        return base;
    }

    /** 生效 Serper 密钥:显式 DEEP_SERPER_API_KEY 优先,否则读环境变量 SERPER_API_KEY(.env)。 */
    public String effectiveSerperKey() {
        // dotenv 将 .env 注入为 System property;兼容 env var 直读
        String v = firstNonBlank(System.getProperty("DEEP_SERPER_API_KEY"),
                System.getenv("DEEP_SERPER_API_KEY"),
                System.getProperty("SERPER_API_KEY"),
                System.getenv("SERPER_API_KEY"));
        return v != null ? v : serperApiKey;
    }

    /** 生效 Serper 端点:显式 DEEP_SERPER_API_BASE_URL 优先,否则字段默认(官方端点)。 */
    public String effectiveSerperApiBase() {
        String v = firstNonBlank(System.getProperty("DEEP_SERPER_API_BASE_URL"),
                System.getenv("DEEP_SERPER_API_BASE_URL"),
                System.getProperty("SERPER_API_BASE_URL"),
                System.getenv("SERPER_API_BASE_URL"));
        if (v == null) v = serperApiBase;
        String base = v == null ? "" : v.trim();
        if (base.isEmpty()) base = "https://google.serper.dev";
        // 末尾斜杠归一;路径段(/serper)必须保留
        while (base.endsWith("/") && base.length() > 1) base = base.substring(0, base.length() - 1);
        return base;
    }

    /** 生效 gl(空白归一为空串,调用方据此不下发该键)。 */
    public String effectiveSerperGl() {
        String v = firstNonBlank(System.getProperty("DEEP_SERPER_GL"),
                System.getenv("DEEP_SERPER_GL"), serperGl);
        return v == null ? "" : v.trim();
    }

    /** 生效 hl(空白归一为空串,调用方据此不下发该键)。 */
    public String effectiveSerperHl() {
        String v = firstNonBlank(System.getProperty("DEEP_SERPER_HL"),
                System.getenv("DEEP_SERPER_HL"), serperHl);
        return v == null ? "" : v.trim();
    }

    /** 返回首个非空白值;全空返回 null(不做 trim,由调用方归一)。 */
    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}