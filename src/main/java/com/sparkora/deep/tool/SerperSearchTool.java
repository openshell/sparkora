package com.sparkora.deep.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Serper 搜索(10-04-serper-provider A):Google 结果代理,<b>Header {@code X-API-KEY}</b> 认证
 * (与 Tavily 的 body {@code api_key} 不同,勿混用)。
 *
 * <p>端点 {@code apiBase} 必须可配置(A-R2):官方 {@code https://google.serper.dev},中转
 * {@code https://search.604020.xyz/serper}(路径前缀 {@code /serper})——故 {@code apiBase} 允许携带
 * 路径段,严禁 URI 解析后丢弃 path。默认值经 {@code DeepProperties.effectiveSerperApiBase()} 兜底。
 *
 * <p>垂直(A-R3):{@code web} → {@code POST {apiBase}/search}(解析 {@code organic[]});
 * {@code news} → {@code POST {apiBase}/news}(解析 {@code news[]},额外保留 {@code date}/{@code source}
 * 到 {@link SearchHit#content()} 供 R4b 时效能力消费,本任务不做新鲜度计算)。
 *
 * <p>地域(A-R4):默认 {@code gl=cn}/{@code hl=zh-cn},可配;空白值不下发该键。
 * 条数(A-R5):Serper 单次实际硬上限 10,请求 {@code num} clamp 到 {@code min(maxResults, 10)}。
 *
 * <p>降级语义照 {@code TavilySearchTool}:异常/空响应/非法 JSON → 空列表 + {@code lastOk=false},
 * <b>绝不抛出</b>;异常只记类型化类名,不记 {@code e.getMessage()}(防密钥/URL 泄漏)。
 * {@code available()} 仅判配置就绪,失败不闩锁(下次研究自动重试)。
 */
@Slf4j
@Component
public class SerperSearchTool implements SearchTool {

    /** 官方端点(默认;中转端点须显式配置)。 */
    static final String DEFAULT_API_BASE = "https://google.serper.dev";
    /** Serper 单次返回硬上限(实测 {@code num=20} 只回 10 条)。 */
    static final int PROVIDER_MAX_RESULTS = 10;
    /** web 垂直值。 */
    private static final String VERTICAL_WEB = "web";
    /** news 垂直值。 */
    private static final String VERTICAL_NEWS = "news";

    private final String apiKey;
    private final String gl;
    private final String hl;
    private final RestClient rest;
    private final ObjectMapper json;
    /** API base(测试可注入;生产默认官方端点)。可含路径段,末尾斜杠已归一。 */
    private final String apiBase;
    /** 最近一次调用是否成功(仅供健康展示;不参与 available 门控,失败可自恢复)。 */
    private volatile boolean lastOk = true;

    @org.springframework.beans.factory.annotation.Autowired
    public SerperSearchTool(com.sparkora.config.DeepProperties deepProps, ObjectMapper mapper) {
        this(deepProps, mapper, deepProps == null ? null : deepProps.effectiveSerperApiBase());
    }

    /** 测试可注入 API base(仅包级可见,不改变生产默认端点)。 */
    SerperSearchTool(com.sparkora.config.DeepProperties deepProps, ObjectMapper mapper, String apiBase) {
        this.apiKey = deepProps == null ? "" : deepProps.effectiveSerperKey();
        this.gl = deepProps == null ? "" : deepProps.effectiveSerperGl();
        this.hl = deepProps == null ? "" : deepProps.effectiveSerperHl();
        this.apiBase = apiBase == null || apiBase.isBlank() ? DEFAULT_API_BASE : stripTrailingSlash(apiBase);
        this.rest = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(
                        HttpClientSettings.defaults().withConnectTimeout(Duration.ofSeconds(5))
                                .withReadTimeout(Duration.ofSeconds(15))))
                .build();
        this.json = mapper;
    }

    @Override
    public String name() { return "SERPER"; }

    @Override
    public boolean available() {
        return configured();
    }

    @Override
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public boolean lastCallOk() {
        return lastOk;
    }

    @Override
    public List<SearchHit> search(String query, int maxResults) {
        return searchVertical(query, VERTICAL_WEB, maxResults);
    }

    @Override
    public List<SearchHit> searchVertical(String query, String vertical, int maxResults) {
        if (!configured()) return List.of();   // 未配置密钥不发请求(避免无谓付费调用)
        String v = normalizeVertical(vertical);
        // 路径与垂直名不同:web → /search,news → /news(design §3)
        String endpoint = VERTICAL_NEWS.equals(v) ? "news" : "search";
        int num = maxResults <= 0 ? PROVIDER_MAX_RESULTS : Math.min(maxResults, PROVIDER_MAX_RESULTS);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("q", query);
            body.put("num", num);
            // A-R4:空值不下发该键(让 provider 走自身默认),而非发空串
            if (gl != null && !gl.isBlank()) body.put("gl", gl);
            if (hl != null && !hl.isBlank()) body.put("hl", hl);
            String resp = rest.post()
                    .uri(apiBase + "/" + endpoint)
                    .header("Content-Type", "application/json")
                    .header("X-API-KEY", apiKey)
                    .body(body)
                    .retrieve().body(String.class);
            JsonNode root = json.readTree(resp);
            List<SearchHit> out = VERTICAL_NEWS.equals(v) ? parseNews(root, maxResults) : parseOrganic(root, maxResults);
            lastOk = true;
            return out;
        } catch (Exception e) {
            // 异常文本可能回显含 X-API-KEY 的请求上下文,仅记类型化原因,不写 e.getMessage()
            log.warn("Serper 搜索失败(降级): {}", e.getClass().getSimpleName());
            lastOk = false;
            return List.of();
        }
    }

    /** web 垂直:解析 {@code organic[]}(link/title/snippet;忽略 position)。 */
    private List<SearchHit> parseOrganic(JsonNode root, int maxResults) {
        List<SearchHit> out = new ArrayList<>();
        JsonNode results = root.path("organic");
        for (int i = 0; i < results.size() && out.size() < maxResults; i++) {
            JsonNode r = results.get(i);
            String title = r.path("title").asText("");
            String url = r.path("link").asText("");
            String snippet = r.path("snippet").asText("");
            if (title.isBlank() && url.isBlank()) continue;
            out.add(SearchHit.web("SERPER", title, url, snippet));
        }
        return out;
    }

    /**
     * news 垂直:解析 {@code news[]}(link/title/snippet),并把时效字段 {@code date}/{@code source}
     * 保留进 {@link SearchHit#content()}(R4b 消费;本任务只取到字段,不做新鲜度计算)。
     * 两者皆空时 content=null(与无正文的既有命中一致,不影响注入)。
     */
    private List<SearchHit> parseNews(JsonNode root, int maxResults) {
        List<SearchHit> out = new ArrayList<>();
        JsonNode results = root.path("news");
        for (int i = 0; i < results.size() && out.size() < maxResults; i++) {
            JsonNode r = results.get(i);
            String title = r.path("title").asText("");
            String url = r.path("link").asText("");
            String snippet = r.path("snippet").asText("");
            if (title.isBlank() && url.isBlank()) continue;
            String date = r.path("date").asText("");
            String source = r.path("source").asText("");
            out.add(SearchHit.web("SERPER", title, url, snippet, metaContent(date, source)));
        }
        return out;
    }

    /** 时效元数据载体:JSON 串(机器可读,供 R4b);date/source 皆空时返回 null。 */
    private String metaContent(String date, String source) {
        boolean hasDate = date != null && !date.isBlank();
        boolean hasSource = source != null && !source.isBlank();
        if (!hasDate && !hasSource) return null;
        Map<String, String> meta = new LinkedHashMap<>();
        if (hasDate) meta.put("date", date);
        if (hasSource) meta.put("source", source);
        try {
            return json.writeValueAsString(meta);
        } catch (Exception e) {
            // 序列化失败不阻断(极端);降级为无元数据
            return null;
        }
    }

    /** vertical 归一:null/空白/未知 → {@code web} + warn(运行时启发式,不抛异常;与配置错误抛异常刻意不同)。 */
    private String normalizeVertical(String vertical) {
        if (vertical == null || vertical.isBlank()) return VERTICAL_WEB;
        String v = vertical.trim().toLowerCase(Locale.ROOT);
        if (VERTICAL_WEB.equals(v) || VERTICAL_NEWS.equals(v)) return v;
        log.warn("未知搜索 vertical「{}」,回落 web", vertical);
        return VERTICAL_WEB;
    }

    /** 末尾斜杠归一(保留路径段;避免拼出 {@code //search})。 */
    private static String stripTrailingSlash(String base) {
        String b = base.trim();
        while (b.endsWith("/") && b.length() > 1) b = b.substring(0, b.length() - 1);
        return b;
    }
}
