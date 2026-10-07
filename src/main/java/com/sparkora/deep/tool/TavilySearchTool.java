package com.sparkora.deep.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.deep.search.WebResultNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tavily 搜索(S9):POST {apiBase}/search {api_key, query, max_results, search_depth}。
 *
 * <p>10-05-tavily-endpoint-priority <b>双端点</b>:工具内部持有两个端点——{@code relay}(中转,可选,
 * 便宜/不限量)与 {@code official}(官方,限额度但稳)。{@code search} 按 {@code relay → official} 顺序
 * failover:中转返回<b>有效命中</b>(端点级质量门通过)即采用、不再调用官方;失败/超时/空/低质则自动降级。
 * <b>两端点对外 {@code name()} 均为 {@code TAVILY}</b>(不新增 provider 名,防来源虚高)。
 *
 * <p>10-05 T-R3 <b>独立超时</b>:每个端点一个 {@link RestClient}(relay read 默认 8s / official read 默认 30s;
 * connect 统一 5s)。{@code extract} 另持一个 {@link RestClient}(read 默认 15s),<b>不被</b>官方 search 的 30s
 * 连带改变——这是「快速失败 + 快速兜底」正确性的前提。
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
    /** 中转端点 id(可观测 witness)。 */
    static final String ENDPOINT_RELAY = "relay";
    /** 官方端点 id(可观测 witness)。 */
    static final String ENDPOINT_OFFICIAL = "official";

    private final String officialKey;
    private final String officialBase;
    private final String relayKey;
    private final String relayBase;
    private final List<String> denyDomains;
    /** 结果级最低 content 长度(0=off)。 */
    private final int minContentChars;
    /** 正文补抓单条上限(字符):工具层唯一上限,默认 2000。 */
    private final int contentMaxChars;

    /** relay search 客户端(独立 read 超时)。 */
    private final RestClient relaySearch;
    /** official search 客户端(独立 read 超时)。 */
    private final RestClient officialSearch;
    /** extract 客户端(独立 read 超时,官方 base/key;不被 search 超时影响)。 */
    private final RestClient extractClient;

    private final ObjectMapper json;
    /** 最近一次调用是否成功(仅供健康展示;不参与 available 门控,失败可自恢复)。 */
    private volatile boolean lastOk = true;
    /** 最近一次 search 实际采用的端点(relay/official;无有效命中为 null,仅观测)。 */
    private volatile String lastUsedEndpoint;

    @org.springframework.beans.factory.annotation.Autowired
    public TavilySearchTool(com.sparkora.config.DeepProperties deepProps,
                            ObjectMapper mapper) {
        this(deepProps, mapper, deepProps == null ? null : deepProps.effectiveTavilyApiBase());
    }

    /** 测试可注入<b>官方</b> API base(仅包级可见,不改变生产默认端点;中转端点仍读配置)。 */
    TavilySearchTool(com.sparkora.config.DeepProperties deepProps, ObjectMapper mapper, String officialApiBase) {
        this.officialKey = deepProps == null ? "" : deepProps.effectiveTavilyKey();
        this.relayKey = deepProps == null ? "" : deepProps.effectiveTavilyRelayKey();
        this.contentMaxChars = deepProps == null ? 2000 : deepProps.effectiveWebContentMaxChars();
        this.officialBase = officialApiBase == null || officialApiBase.isBlank()
                ? (deepProps == null ? DEFAULT_API_BASE : stripTrailingSlash(deepProps.effectiveTavilyApiBase()))
                : stripTrailingSlash(officialApiBase);
        this.relayBase = deepProps == null ? "" : stripTrailingSlash(deepProps.effectiveTavilyRelayBase());
        this.denyDomains = deepProps == null ? List.of() : deepProps.effectiveTavilyDenyDomains();
        this.minContentChars = deepProps == null ? 0 : deepProps.effectiveTavilyMinContentChars();
        long relayRead = deepProps == null ? 8000 : deepProps.effectiveTavilyRelayReadTimeoutMs();
        long officialRead = deepProps == null ? 30000 : deepProps.effectiveTavilyOfficialReadTimeoutMs();
        long extractRead = deepProps == null ? 15000 : deepProps.effectiveTavilyExtractReadTimeoutMs();
        this.relaySearch = client(relayRead);
        this.officialSearch = client(officialRead);
        this.extractClient = client(extractRead);
        this.json = mapper;
    }

    /** 按 read 超时构建独立 RestClient(connect 统一 5s;绝不跨端点/跨用途共用)。 */
    private static RestClient client(long readTimeoutMs) {
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(
                        HttpClientSettings.defaults().withConnectTimeout(Duration.ofSeconds(5))
                                .withReadTimeout(Duration.ofMillis(readTimeoutMs))))
                .build();
    }

    @Override
    public String name() { return "TAVILY"; }

    @Override
    public boolean available() {
        return configured();
    }

    /** 任一端点配置即视为就绪(官方 key 为既有基线;仅中转亦算可用)。 */
    @Override
    public boolean configured() {
        return isConfigured(officialKey, officialBase) || isConfigured(relayKey, relayBase);
    }

    /** 端点配置就绪 = base 与 key 均非空。 */
    private static boolean isConfigured(String key, String base) {
        return key != null && !key.isBlank() && base != null && !base.isBlank();
    }

    @Override
    public boolean lastCallOk() {
        return lastOk;
    }

    /** 最近一次 search 采用的端点 id(relay/official;无有效命中为 null)。 */
    @Override
    public String lastUsedEndpoint() {
        return lastUsedEndpoint;
    }

    @Override
    public List<SearchHit> search(String query, int maxResults) {
        lastUsedEndpoint = null;
        boolean attempted = false;
        boolean anyCallSucceeded = false;
        // relay → official 顺序 failover;每端点每轮最多一次调用(不重试)。
        if (isConfigured(relayKey, relayBase)) {
            attempted = true;
            EndpointResult r = callEndpoint(relaySearch, relayBase, relayKey, query, maxResults);
            if (r.callOk) anyCallSucceeded = true;
            if (r.acceptable) {
                lastOk = true;
                lastUsedEndpoint = ENDPOINT_RELAY;
                return applyResultGate(r.hits);
            }
            logAttempt(ENDPOINT_RELAY, r);
        }
        if (isConfigured(officialKey, officialBase)) {
            attempted = true;
            EndpointResult r = callEndpoint(officialSearch, officialBase, officialKey, query, maxResults);
            if (r.callOk) anyCallSucceeded = true;
            if (r.acceptable) {
                lastOk = true;
                lastUsedEndpoint = ENDPOINT_OFFICIAL;
                return applyResultGate(r.hits);
            }
            logAttempt(ENDPOINT_OFFICIAL, r);
        }
        // 两端点均无有效命中:不改动 lastOk(未发起任何调用时)或标记调用态
        if (attempted) lastOk = anyCallSucceeded;
        return List.of();
    }

    /** 单端点调用结果(含端点级质量门判定与可观测原因)。 */
    private static final class EndpointResult {
        final boolean callOk;       // HTTP 调用成功(未抛异常)
        final boolean acceptable;   // 命中非空且通过端点级质量门
        final List<SearchHit> hits;
        final String reason;        // EMPTY/LOW_QUALITY/TIMEOUT/ERROR
        final String errorClass;    // 异常类型简单名(仅 ERROR/TIMEOUT;不落异常文本)
        final long latencyMs;

        EndpointResult(boolean callOk, boolean acceptable, List<SearchHit> hits, String reason,
                       String errorClass, long latencyMs) {
            this.callOk = callOk;
            this.acceptable = acceptable;
            this.hits = hits;
            this.reason = reason;
            this.errorClass = errorClass;
            this.latencyMs = latencyMs;
        }
    }

    /** 记录端点未采信原因(不落 key/URL/异常文本)。 */
    private static void logAttempt(String endpointId, EndpointResult r) {
        if (r.errorClass != null) {
            log.warn("provider=TAVILY endpoint={} reason={} error={} latencyMs={}",
                    endpointId, r.reason, r.errorClass, r.latencyMs);
        } else {
            log.warn("provider=TAVILY endpoint={} reason={} latencyMs={}", endpointId, r.reason, r.latencyMs);
        }
    }

    /** 调用单端点并做端点级质量门;异常仅记类型化原因(绝不抛出)。 */
    private EndpointResult callEndpoint(RestClient client, String base, String key,
                                        String query, int maxResults) {
        long began = System.currentTimeMillis();
        try {
            List<SearchHit> hits = doSearch(client, base, key, query, maxResults);
            long cost = System.currentTimeMillis() - began;
            if (hits.isEmpty()) {
                return new EndpointResult(true, false, hits, "EMPTY", null, cost);
            }
            if (!qualityPass(hits)) {
                return new EndpointResult(true, false, hits, "LOW_QUALITY", null, cost);
            }
            return new EndpointResult(true, true, hits, null, null, cost);
        } catch (Exception e) {
            // 异常文本可能回显含 api_key 的请求上下文,仅记类型化类名,不写 e.getMessage()
            String reason = isTimeout(e) ? "TIMEOUT" : "ERROR";
            return new EndpointResult(false, false, List.of(), reason, e.getClass().getSimpleName(),
                    System.currentTimeMillis() - began);
        }
    }

    /** 超时判定:遍历 cause 链(JDK HttpClient 超时为 {@code HttpTimeoutException},被 RestClient 包成 ResourceAccessException)。 */
    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String name = t.getClass().getSimpleName();
            if (name.contains("Timeout")) return true;
        }
        return false;
    }

    /** 实际 HTTP 调用 + 解析(results[])。 */
    private List<SearchHit> doSearch(RestClient client, String base, String key, String query, int maxResults)
            throws Exception {
        String resp = client.post()
                .uri(base + "/search")
                .header("Content-Type", "application/json")
                .body(Map.of("api_key", key, "query", query,
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
        return out;
    }

    /**
     * 端点级质量门(T-R5):至少 1 条命中满足「URL 可规范化为主流 scheme + 非噪声域 + title 与正文不同时为空」。
     * 通过才采用该端点结果,否则视为失败并切下一端点。
     */
    private boolean qualityPass(List<SearchHit> hits) {
        if (hits == null || hits.isEmpty()) return false;
        for (SearchHit h : hits) {
            String normalized = WebResultNormalizer.normalizeUrl(h.url());
            if (normalized == null) continue;                       // 非法/非 http(s) URL
            if (matchesDomain(hostOf(normalized), denyDomains)) continue;   // 噪声域
            boolean blankTitle = h.title() == null || h.title().isBlank();
            boolean blankBody = (h.snippet() == null || h.snippet().isBlank())
                    && (h.content() == null || h.content().isBlank());
            if (!blankTitle || !blankBody) return true;             // 至少一条 title 或 content 非空
        }
        return false;
    }

    /**
     * 结果级质量门(T-R5):低于 {@code minContentChars} 的命中丢弃(默认 0=off,零回归)。
     * 口径:正文片段(content)优先,缺失回退摘要(snippet)——Tavily {@code /search} 的 content 字段映射为 snippet。
     */
    private List<SearchHit> applyResultGate(List<SearchHit> hits) {
        if (minContentChars <= 0 || hits == null) return hits;
        List<SearchHit> out = new ArrayList<>();
        for (SearchHit h : hits) {
            String body = h.content() != null && !h.content().isBlank() ? h.content() : h.snippet();
            if (body != null && body.length() >= minContentChars) out.add(h);
        }
        return out;
    }

    /** 域名是否命中噪声黑名单(host == domain 或 host 以 .domain 结尾)。 */
    private static boolean matchesDomain(String host, List<String> domains) {
        if (host == null || domains == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String d : domains) {
            if (d == null || d.isBlank()) continue;
            String dom = d.toLowerCase(Locale.ROOT);
            if (h.equals(dom) || h.endsWith("." + dom)) return true;
        }
        return false;
    }

    /** 提取规范化 URL 的 host(小写);非法返回 null。 */
    private static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * R1(09-27-tavily-extract-kind-hypotheses):按 URL 抽取正文片段(机制 B)。
     *
     * <p>{@code POST {officialBase}/extract} {@code {api_key, urls, query, chunks_per_source:3,
     * extract_depth:"basic", format:"markdown"}};取每条 {@code raw_content}(markdown)并在工具层截断到
     * {@code contentMaxChars}。<b>extract 只走官方端点、超时独立</b>(10-05 T-R3)。
     * 未配置官方密钥 / 空 urls / 无 results(含 {@code failed_results}) / 异常 → 空列表(降级回摘要,不抛出)。
     * 本方法不修改 {@code lastOk}(正文补抓是增强,失败不应污染搜索健康态判定)。
     */
    @Override
    public List<SearchHit> extract(List<String> urls, String query) {
        // extract 固定走官方端点:官方 key/base 未配置即不发起(relay-only 部署不误用中转)
        if (!isConfigured(officialKey, officialBase) || urls == null || urls.isEmpty()) return List.of();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("api_key", officialKey);
            body.put("urls", urls);
            if (query != null && !query.isBlank()) body.put("query", query);
            body.put("chunks_per_source", 3);
            body.put("extract_depth", "basic");
            body.put("format", "markdown");
            String resp = extractClient.post()
                    .uri(officialBase + "/extract")
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

    // ===== 包级可见的测试探针(不改变生产行为) =====

    /** relay search 客户端(测试断言与 official/extract 为不同实例)。 */
    RestClient relaySearchClient() { return relaySearch; }

    /** official search 客户端。 */
    RestClient officialSearchClient() { return officialSearch; }

    /** extract 客户端(须与两 search 客户端均不同实例)。 */
    RestClient extractClient() { return extractClient; }

    /** 端点配置态探针。 */
    public boolean relayConfigured() { return isConfigured(relayKey, relayBase); }

    /** 官方端点配置态探针。 */
    public boolean officialConfigured() { return isConfigured(officialKey, officialBase); }

    /** 末尾斜杠归一(避免拼出 {@code //search});保留路径段。 */
    private static String stripTrailingSlash(String base) {
        String b = base.trim();
        while (b.endsWith("/") && b.length() > 1) b = b.substring(0, b.length() - 1);
        return b;
    }
}
