package com.sparkora.deep.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tavily 搜索(S9):POST https://api.tavily.com/search {api_key, query, max_results, search_depth}。
 * 密钥 TAVILY_API_KEY(.env);available() 仅判密钥是否配置(失败不闩锁,下次研究自动重试)。
 *
 * <p>09-27-tavily-extract-kind-hypotheses R1(机制 B):正文补抓走 {@code POST /extract}
 * (query 按语义重排 + chunks_per_source=3 + extract_depth=basic + markdown),取 {@code raw_content}
 * 作为正文片段并在工具层截断到 {@link com.sparkora.config.DeepProperties#effectiveWebContentMaxChars()}。
 * 失败/空({@code failed_results}/空 {@code results})/异常 → 返回空列表(降级回摘要),绝不抛出。
 */
@Slf4j
@Component
public class TavilySearchTool implements SearchTool {

    private static final String DEFAULT_API_BASE = "https://api.tavily.com";

    private final String apiKey;
    private final RestClient rest;
    private final ObjectMapper json;
    /** 正文补抓单条上限(字符):工具层唯一上限,默认 2000。 */
    private final int contentMaxChars;
    /** API base(测试可注入;生产默认 https://api.tavily.com)。 */
    private final String apiBase;
    /** 最近一次调用是否成功(仅供健康展示;不参与 available 门控,失败可自恢复)。 */
    private volatile boolean lastOk = true;

    @org.springframework.beans.factory.annotation.Autowired
    public TavilySearchTool(com.sparkora.config.DeepProperties deepProps,
                            ObjectMapper mapper) {
        this(deepProps, mapper, DEFAULT_API_BASE);
    }

    /** 测试可注入 API base(仅包级可见,不改变生产默认端点)。 */
    TavilySearchTool(com.sparkora.config.DeepProperties deepProps, ObjectMapper mapper, String apiBase) {
        this.apiKey = deepProps == null ? "" : deepProps.effectiveTavilyKey();
        this.contentMaxChars = deepProps == null ? 2000 : deepProps.effectiveWebContentMaxChars();
        this.apiBase = apiBase == null || apiBase.isBlank() ? DEFAULT_API_BASE : apiBase;
        this.rest = RestClient.builder()
                .requestFactory(org.springframework.boot.web.client.ClientHttpRequestFactories.get(
                        ClientHttpRequestFactorySettings.DEFAULTS.withConnectTimeout(Duration.ofSeconds(5))
                                .withReadTimeout(Duration.ofSeconds(15))))
                .build();
        this.json = mapper;
    }

    @Override
    public String name() { return "TAVILY"; }

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
        try {
            String resp = rest.post()
                    .uri(apiBase + "/search")
                    .header("Content-Type", "application/json")
                    .body(Map.of("api_key", apiKey, "query", query,
                            "max_results", maxResults, "search_depth", "basic"))
                    .retrieve().body(String.class);
            JsonNode root = json.readTree(resp);
            JsonNode results = root.path("results");
            List<SearchHit> out = new ArrayList<>();
            for (int i = 0; i < results.size() && out.size() < maxResults; i++) {
                JsonNode r = results.get(i);
                String title = r.path("title").asText("");
                String url = r.path("url").asText("");
                String snippet = r.path("content").asText("");
                if (title.isBlank() && url.isBlank()) continue;
                out.add(SearchHit.web("TAVILY", title, url, snippet));
            }
            lastOk = true;
            return out;
        } catch (Exception e) {
            // R12:异常文本可能回显含 api_key 的请求上下文,仅记类型化原因,不写 e.getMessage()
            log.warn("Tavily 搜索失败(降级): {}", e.getClass().getSimpleName());
            lastOk = false;
            return List.of();
        }
    }

    /**
     * R1(09-27-tavily-extract-kind-hypotheses):按 URL 抽取正文片段(机制 B)。
     *
     * <p>{@code POST /extract} {@code {api_key, urls, query, chunks_per_source:3, extract_depth:"basic"}};
     * 取每条 {@code raw_content}(markdown)并在工具层截断到 {@code contentMaxChars}。
     * 未配置密钥 / 空 urls / 无 results(含 {@code failed_results}) / 异常 → 空列表(降级回摘要,不抛出)。
     * 本方法不修改 {@code lastOk}(正文补抓是增强,失败不应污染搜索健康态判定)。
     */
    @Override
    public List<SearchHit> extract(List<String> urls, String query) {
        if (!configured() || urls == null || urls.isEmpty()) return List.of();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("api_key", apiKey);
            body.put("urls", urls);
            if (query != null && !query.isBlank()) body.put("query", query);
            body.put("chunks_per_source", 3);
            body.put("extract_depth", "basic");
            body.put("format", "markdown");
            String resp = rest.post()
                    .uri(apiBase + "/extract")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve().body(String.class);
            JsonNode root = json.readTree(resp);
            List<SearchHit> out = new ArrayList<>();
            for (JsonNode r : root.path("results")) {
                String url = r.path("url").asText("");
                String raw = r.path("raw_content").asText("");
                if (url.isBlank() || raw.isBlank()) continue;
                out.add(SearchHit.webContent("TAVILY", url, truncate(raw)));
            }
            return out;
        } catch (Exception e) {
            // 异常文本可能回显含 api_key 的请求上下文,仅记类型化原因
            log.warn("Tavily 正文抽取失败(降级回摘要): {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** 正文工具层截断(唯一上限;null/空 → 空串)。 */
    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > contentMaxChars ? s.substring(0, contentMaxChars) : s;
    }
}
