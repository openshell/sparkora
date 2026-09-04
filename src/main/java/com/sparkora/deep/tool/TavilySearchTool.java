package com.sparkora.deep.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tavily 搜索(S9):POST https://api.tavily.com/search {api_key, query, max_results, search_depth}。
 * 密钥 TAVILY_API_KEY(.env);未配置或调用失败 → available()=false,调用方降级。
 */
@Slf4j
@Component
public class TavilySearchTool implements SearchTool {

    private final String apiKey;
    private final RestClient rest;
    private final ObjectMapper json;
    private volatile boolean lastOk = true;

    public TavilySearchTool(com.sparkora.config.DeepProperties deepProps,
                            ObjectMapper mapper) {
        this.apiKey = deepProps == null ? "" : deepProps.effectiveTavilyKey();
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
        return apiKey != null && !apiKey.isBlank() && lastOk;
    }

    @Override
    public List<SearchHit> search(String query, int maxResults) {
        try {
            String resp = rest.post()
                    .uri("https://api.tavily.com/search")
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
            log.warn("Tavily 搜索失败(降级): {}", e.getMessage());
            lastOk = false;
            return List.of();
        }
    }
}