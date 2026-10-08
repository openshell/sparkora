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
     * Tavily <b>官方</b> API 端点(10-04-serper-provider A-R6;10-05-tavily-endpoint-priority 收窄为官方)：
     * 默认 {@code https://api.tavily.com}。生效值经 {@link #effectiveTavilyApiBase()} 的
     * property(裸名 TAVILY_API_BASE_URL)→字段兜底，末尾斜杠归一。
     * <b>注意</b>：中转端点见 {@link #tavilyRelayApiBase}（{@code DEEP_TAVILY_API_BASE_URL}），二者独立。
     */
    private String tavilyApiBase = "https://api.tavily.com";
    /**
     * Tavily <b>中转</b>端点(10-05-tavily-endpoint-priority T-R1)：默认空=未配置。
     * 配置后 Tavily 工具按 relay→official 顺序 failover；两者对外 {@code name()} 均为 {@code TAVILY}。
     * 生效值经 {@link #effectiveTavilyRelayBase()} property(DEEP_TAVILY_API_BASE_URL)→字段兜底；
     * 空值保持为空（<b>不回退官方</b>，避免未配置时把官方误当转端点）。
     */
    private String tavilyRelayApiBase = "";
    /** Tavily 中转端点密钥(.env: DEEP_TAVILY_API_KEY_HIKARI)；空则中转端不可用。 */
    private String tavilyRelayApiKey = "";
    /** 中转端点读超时(ms，默认 8000)：实测中转失败恒定约 16s，短超时快速失败后切官方(T-R3)。 */
    private long tavilyRelayReadTimeoutMs = 8000;
    /** 官方端点读超时(ms，默认 30000)：官方稳定但可能慢，<b>独立于</b>中转超时。 */
    private long tavilyOfficialReadTimeoutMs = 30000;
    /** extract 读超时(ms，默认 15000)：独立 RestClient，不被官方 search 的 30s 连带改变(T-R3)。 */
    private long tavilyExtractReadTimeoutMs = 15000;
    /** Tavily 端点级质量门噪声域黑名单(T-R5)：逗号分隔，默认剔除已知跳转/聚合域。 */
    private String tavilyDenyDomains = "weixin.sogou.com";
    /** Tavily 结果级最低 content 长度(T-R5)：默认 0=off(零回归)；&gt;0 时低于阈值的命中丢弃。 */
    private int tavilyMinContentChars = 0;
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
    /**
     * 覆盖驱动多轮补检索的目标数上限(10-04 C):0 = 关闭多轮(默认,单轮研究零回归,回滚点);
     * &gt;0(建议 2)= Round 1 汇总后按缺口/低置信定向补搜的目标数。运行时 sparkora_setting 不放开
     * 该开关(直接决定成本)。<b>默认 0 与父设计 §6「webFollowup=off」一致</b>,现有部署零行为变化。
     */
    private int webFollowupMax = 0;
    /** 整个简报的计量源(Tavily/Serper)调用总量上限(10-04 C,默认 20),Round 1 + Round 2 共享。 */
    private int webCallBudget = 20;
    /**
     * Round 1 阶段计量源调用次数封顶(10-04 C,默认 12)。生效值取
     * {@code max(配置值, maxAgents × |计量 primary 组|)}(保护性下限,避免预算掐死主流程)。
     */
    private int webCallBudgetPerRound = 12;
    /** Round 2 补检索独立超时(ms,默认 30000):超时/异常记 warning + 跳过该目标,降级不阻断。 */
    private long followupTimeoutMs = 30000;
    /** 批次内搜索缓存 TTL(ms,默认 600000=10min):作用域严格限定单次 research 批次,随批次释放。 */
    private long webCacheTtlMs = 600000;

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

    /** 生效补检索目标数上限:≤0 视为关闭多轮研究(回滚点)。 */
    public int effectiveWebFollowupMax() {
        return webFollowupMax > 0 ? webFollowupMax : 0;
    }

    /** 生效整个简报计量源调用总量上限:≤0 视为默认 20。 */
    public int effectiveWebCallBudget() {
        return webCallBudget > 0 ? webCallBudget : 20;
    }

    /** 生效 Round 1 计量源调用次数配置值(保护性下限在 {@code WebCallBudget} 内计算):≤0 视为默认 12。 */
    public int effectiveWebCallBudgetPerRound() {
        return webCallBudgetPerRound > 0 ? webCallBudgetPerRound : 12;
    }

    /** 生效 Round 2 补检索超时(ms):≤0 视为默认 30000。 */
    public long effectiveFollowupTimeoutMs() {
        return followupTimeoutMs > 0 ? followupTimeoutMs : 30000;
    }

    /** 生效批次内搜索缓存 TTL(ms):≤0 视为默认 600000(10min)。 */
    public long effectiveWebCacheTtlMs() {
        return webCacheTtlMs > 0 ? webCacheTtlMs : 600000;
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

    /**
     * 生效 Tavily <b>官方</b>端点:显式裸名 TAVILY_API_BASE_URL 优先,否则字段默认(官方端点)。
     *
     * <p>10-05-tavily-endpoint-priority:<b>不再</b>读 DEEP_TAVILY_API_BASE_URL——该键已改指中转端点
     * ({@link #effectiveTavilyRelayBase()});若此处仍读它,用户配置中转时会把官方地址误置为中转。
     */
    public String effectiveTavilyApiBase() {
        String v = firstNonBlank(System.getProperty("TAVILY_API_BASE_URL"),
                System.getenv("TAVILY_API_BASE_URL"));
        if (v == null) v = tavilyApiBase;
        String base = v == null ? "" : v.trim();
        if (base.isEmpty()) base = "https://api.tavily.com";
        // 末尾斜杠归一:避免拼出 //search
        while (base.endsWith("/") && base.length() > 1) base = base.substring(0, base.length() - 1);
        return base;
    }

    /**
     * 生效 Tavily <b>中转</b>端点(10-05 T-R1):property(DEEP_TAVILY_API_BASE_URL)→字段兜底。
     * 未配置时返回空串(<b>不回退官方</b>,否则无法区分「未配置中转」与「中转即官方」)。
     */
    public String effectiveTavilyRelayBase() {
        String v = firstNonBlank(System.getProperty("DEEP_TAVILY_API_BASE_URL"),
                System.getenv("DEEP_TAVILY_API_BASE_URL"), tavilyRelayApiBase);
        String base = v == null ? "" : v.trim();
        while (base.endsWith("/") && base.length() > 1) base = base.substring(0, base.length() - 1);
        return base;
    }

    /** 生效 Tavily 中转密钥:property(DEEP_TAVILY_API_KEY_HIKARI)→字段兜底;未配置返回空串。 */
    public String effectiveTavilyRelayKey() {
        String v = firstNonBlank(System.getProperty("DEEP_TAVILY_API_KEY_HIKARI"),
                System.getenv("DEEP_TAVILY_API_KEY_HIKARI"), tavilyRelayApiKey);
        return v == null ? "" : v;
    }

    /** 生效中转读超时(ms):≤0 视为默认 8000(快速失败后切官方,T-R3)。 */
    public long effectiveTavilyRelayReadTimeoutMs() {
        return tavilyRelayReadTimeoutMs > 0 ? tavilyRelayReadTimeoutMs : 8000;
    }

    /** 生效官方读超时(ms):≤0 视为默认 30000。 */
    public long effectiveTavilyOfficialReadTimeoutMs() {
        return tavilyOfficialReadTimeoutMs > 0 ? tavilyOfficialReadTimeoutMs : 30000;
    }

    /** 生效 extract 读超时(ms):≤0 视为默认 15000(独立于官方 search 的 30s,T-R3)。 */
    public long effectiveTavilyExtractReadTimeoutMs() {
        return tavilyExtractReadTimeoutMs > 0 ? tavilyExtractReadTimeoutMs : 15000;
    }

    /** 生效 Tavily 端点级质量门噪声域黑名单(逗号分隔,trim + 小写)。 */
    public java.util.List<String> effectiveTavilyDenyDomains() {
        return parseDomains(tavilyDenyDomains);
    }

    /** 生效结果级最低 content 长度:≤0 视为 off(零回归)。 */
    public int effectiveTavilyMinContentChars() {
        return tavilyMinContentChars > 0 ? tavilyMinContentChars : 0;
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