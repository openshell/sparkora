package com.sparkora.deep.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * SEARXNG 搜索(S9):本机聚合搜索,GET {base}/search?q=&format=json。
 * 已实测(2026-09-04)当前实例上游引擎全部 Suspended/CAPTCHA,results 常为空——
 * 实现层容忍:超时/空结果不重试不抛出,available() 仅判地址是否配置(失败不闩锁,下次研究自动重试)。
 */
@Slf4j
@Component
public class SearxngSearchTool implements SearchTool {

    private final String baseUrl;
    private final RestClient rest;
    private final ObjectMapper json;
    /** 最近一次调用是否拿到结果/成功(仅供健康展示;不参与 available 门控,失败可自恢复)。 */
    private volatile boolean lastCallHadResults = true;

    public SearxngSearchTool(org.springframework.core.env.Environment env, ObjectMapper mapper) {
        this.baseUrl = env.getProperty("SEARXNG_BASE_URL", "http://localhost:5676");
        this.rest = RestClient.builder()
                .requestFactory(org.springframework.boot.web.client.ClientHttpRequestFactories.get(
                        ClientHttpRequestFactorySettings.DEFAULTS.withConnectTimeout(Duration.ofSeconds(5))
                                .withReadTimeout(Duration.ofSeconds(10))))
                .build();
        this.json = mapper;
    }

    @Override
    public String name() { return "SEARXNG"; }

    @Override
    public boolean available() {
        return configured();
    }

    @Override
    public boolean configured() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    @Override
    public boolean lastCallOk() {
        return lastCallHadResults;
    }

    @Override
    public List<SearchHit> search(String query, int maxResults) {
        try {
            String resp = rest.get()
                    .uri(baseUrl + "/search?q={q}&format=json&language=zh-CN", query)
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
                out.add(SearchHit.web("SEARXNG", title, url, snippet));
            }
            lastCallHadResults = !out.isEmpty();
            return out;
        } catch (Exception e) {
            log.warn("SEARXNG 搜索失败(降级): {}", e.getMessage());
            lastCallHadResults = false;
            return List.of();
        }
    }
}