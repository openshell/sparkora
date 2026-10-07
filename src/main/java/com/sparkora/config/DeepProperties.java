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
    /**
     * WEB 搜索策略(10-04-web-fanout-merge B-R1):{@code first_hit}(默认,单源短路,现有部署零回归)
     * 或 {@code primary_fanout}(primary 组并行聚合)。运行时 {@code sparkora_setting} 不放开该开关。
     */
    private String webFanout = "first_hit";
    /**
     * PRIMARY_FANOUT 的 primary 组 provider 集合(10-04-web-fanout-merge B-R2):逗号分隔,默认
     * {@code TAVILY,SERPER,SEARXNG}——付费/托管源(Tavily、Serper)互补交叉为主,免费高召回的 SearXNG
     * 亦参与召回(配质量门后进合并池)。解析取 {@code order ∩ 该集合} 且保持 order 顺序;
     * 为空或全部不在 order 中 → 整体回落 FIRST_HIT(SearxNG-only 部署逐位不变)。
     */
    private String webPrimaryProviders = "TAVILY,SERPER,SEARXNG";
    /**
     * SearXNG 结果质量门域名黑名单(10-04-web-fanout-merge B-R2a):逗号分隔,默认剔除跳转聚合/视频噪声域。
     * 仅对进 primary 组的 SearXNG 结果生效;域名在 {@link #webAllowDomains} 中者优先生效(覆盖黑名单)。
     */
    private String webDenyDomains = "bilibili.com,weixin.sogou.com";
    /** SearXNG 结果质量门域名白名单(可空):命中者跳过黑名单与 URL 类型过滤(人工放行)。 */
    private String webAllowDomains = "";

    /** 生效正文上限(≤0 视为不截断/使用默认；防御异常配置)。 */
    public int effectiveWebContentMaxChars() {
        return webContentMaxChars > 0 ? webContentMaxChars : 2000;
    }

    /**
     * 生效搜索策略(10-04-web-fanout-merge B-R1):解析 {@link #webFanout};空白回退 FIRST_HIT,
     * 未知值抛 {@link IllegalArgumentException}(配置错误明确暴露)。
     */
    public com.sparkora.deep.search.SearchStrategy effectiveSearchStrategy() {
        return com.sparkora.deep.search.SearchStrategy.parse(webFanout);
    }

    /** 生效 primary 组 provider 集合(B-R2):解析逗号分隔;去空白/去重/大小写不敏感,未知值抛异常。 */
    public java.util.List<com.sparkora.deep.search.WebProvider> effectivePrimaryProviders() {
        java.util.List<com.sparkora.deep.search.WebProvider> out = new java.util.ArrayList<>();
        if (webPrimaryProviders == null) return out;
        for (String part : webPrimaryProviders.split(",")) {
            if (part.isBlank()) continue;
            com.sparkora.deep.search.WebProvider p = com.sparkora.deep.search.WebProvider.from(part);
            if (!out.contains(p)) out.add(p);
        }
        return out;
    }

    /** 生效 SearXNG 质量门域名黑名单(B-R2a):逗号分隔,trim + 小写。 */
    public java.util.List<String> effectiveWebDenyDomains() {
        return parseDomains(webDenyDomains);
    }

    /** 生效 SearXNG 质量门域名白名单(B-R2a,可空):命中者跳过黑名单/URL 类型过滤。 */
    public java.util.List<String> effectiveWebAllowDomains() {
        return parseDomains(webAllowDomains);
    }

    private static java.util.List<String> parseDomains(String csv) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (csv == null) return out;
        for (String part : csv.split(",")) {
            String d = part.trim().toLowerCase();
            if (!d.isEmpty() && !out.contains(d)) out.add(d);
        }
        return out;
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