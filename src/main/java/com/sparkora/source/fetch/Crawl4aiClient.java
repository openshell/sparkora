package com.sparkora.source.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.Crawl4aiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Crawl4AI 客户端(10-05-crawl4ai-transport,design §0/§4;10-09-cpca-gasgoo-collection G1 扩展)。封装端点:
 *
 * <ul>
 *   <li>{@code POST /md} body {@code {url, f:"fit"}} → {@code {url,filter,query,cache,markdown,success}};</li>
 *   <li>{@code POST /crawl} body {@code {urls:[url]}} → {@code {results:[{success,cleaned_html,html,...}]}}
 *       —— 返回<b>渲染后</b> DOM(G1:JS 站点列表链接在 {@code /html} 里缺失);</li>
 *   <li>{@code POST /html} body {@code {url}} → {@code {html,url,success}}(旧路径,仅作 /crawl 失败兜底)。</li>
 * </ul>
 *
 * <p>鉴权 {@code Authorization: Bearer <CRAWL4AI_API_KEY>}。失败(超时/非 2xx/空正文)以结果态
 * {@link Result} 返回,<b>不抛穿调用方</b>;异常只记 {@code e.getClass().getSimpleName()},不记 message/key/url 全量。
 * 传输引擎显式 {@code .jdk()}(禁用 detect,见 error-handling.md Boot 4 漂移约定)。
 */
@Slf4j
@Component
public class Crawl4aiClient {

    /** 连接超时(毫秒);读超时按 {@link Crawl4aiProperties#getTimeoutMs()}。 */
    private static final long CONNECT_TIMEOUT_MS = 5000;

    private final Crawl4aiProperties props;
    private final RestClient rest;
    private final ObjectMapper json = new ObjectMapper();
    /** 最近一次调用是否成功(乐观初值,失败不闩锁;仅供健康展示,不参与 configured())。 */
    private volatile boolean lastCallOk = true;

    public Crawl4aiClient(Crawl4aiProperties props) {
        this.props = props;
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .withReadTimeout(Duration.ofMillis(props.getTimeoutMs()));
        this.rest = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings))
                .build();
    }

    /** 配置是否就绪(baseUrl 非空)。 */
    public boolean configured() {
        return props.getBaseUrl() != null && !props.getBaseUrl().isBlank();
    }

    /** 最近一次调用健康态(仅展示,乐观初值、失败不闩锁)。 */
    public boolean lastCallOk() {
        return lastCallOk;
    }

    /** /md(fit)抓取正文;wantHtml=true 时抓取渲染后 HTML(/crawl)。 */
    public Result fetch(String url, boolean wantHtml) {
        if (!configured()) return Result.failure(0L, "UNCONFIGURED");
        return wantHtml ? fetchHtml(url) : fetchMarkdown(url);
    }

    /** {@code POST /md {url, f:"fit"}} → markdown 正文。 */
    public Result fetchMarkdown(String url) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        body.put("f", "fit");
        return post("/md", body, "markdown");
    }

    /**
     * {@code POST /crawl {urls:[url]}} → 渲染后 HTML(取 {@code results[0].cleaned_html},回退 {@code html})。
     *
     * <p>G1:乘联会等 JS 渲染列表页 {@code /html} 拿不到 {@code <a href>};{@code /crawl} 返回渲染后 DOM。
     * {@code /crawl} 失败/空 → best-effort 回退 {@link #fetchHtmlLegacy}({@code /html}),保证不劣化现状;
     * 两者皆失败时返回 {@code /crawl} 的失败态便于定位。
     */
    public Result fetchHtml(String url) {
        if (!configured()) return Result.failure(0L, "UNCONFIGURED");
        Result crawl = postCrawl(url);
        if (crawl.ok()) return crawl;
        Result legacy = fetchHtmlLegacy(url);
        return legacy.ok() ? legacy : crawl;
    }

    /** 旧 {@code POST /html {url}} → 原始(未渲染)HTML;仅作 {@link #fetchHtml} 的兼容兜底。 */
    Result fetchHtmlLegacy(String url) {
        return post("/html", Map.of("url", url), "html");
    }

    /** {@code POST /crawl {urls:[url]}} 解析渲染后 HTML。 */
    private Result postCrawl(String url) {
        long start = System.currentTimeMillis();
        try {
            String resp = execute("/crawl", Map.of("urls", List.of(url)));
            long latency = System.currentTimeMillis() - start;
            JsonNode root = json.readTree(resp);
            if (root.has("success") && !root.path("success").asBoolean(true)) {
                lastCallOk = false;
                return Result.failure(latency, "EMPTY");
            }
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                lastCallOk = false;
                return Result.failure(latency, "EMPTY");
            }
            JsonNode first = results.get(0);
            if (first.has("success") && !first.path("success").asBoolean(true)) {
                lastCallOk = false;
                return Result.failure(latency, "EMPTY");
            }
            String text = first.path("cleaned_html").asText("");
            if (text.isBlank()) text = first.path("html").asText("");
            if (text.isBlank()) {
                lastCallOk = false;
                return Result.failure(latency, "EMPTY");
            }
            lastCallOk = true;
            return new Result(200, text, latency, null);
        } catch (Exception e) {
            lastCallOk = false;
            return failureFor(System.currentTimeMillis() - start, e);
        }
    }

    private Result post(String path, Map<String, Object> body, String field) {
        long start = System.currentTimeMillis();
        try {
            String resp = execute(path, body);
            long latency = System.currentTimeMillis() - start;
            JsonNode root = json.readTree(resp);
            // success=false 视为失败态(不误判空正文)
            if (root.has("success") && !root.path("success").asBoolean(true)) {
                lastCallOk = false;
                return Result.failure(latency, "EMPTY");
            }
            String text = root.path(field).asText("");
            if (text.isBlank()) {
                lastCallOk = false;
                return Result.failure(latency, "EMPTY");
            }
            lastCallOk = true;
            return new Result(200, text, latency, null);
        } catch (Exception e) {
            lastCallOk = false;
            return failureFor(System.currentTimeMillis() - start, e);
        }
    }

    /** 统一 POST(url/鉴权/JSON body → String)。 */
    private String execute(String path, Map<String, Object> body) {
        return rest.post()
                .uri(stripTrailingSlash(props.getBaseUrl()) + path)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + safeKey())
                .body(body)
                .retrieve()
                .body(String.class);
    }

    /** 异常 → 结果态:非 2xx 取状态码分类,超时优先,否则只记类名(不写 message/key/url)。 */
    private static Result failureFor(long latency, Exception e) {
        if (e instanceof org.springframework.web.client.RestClientResponseException rre) {
            int status = rre.getStatusCode().value();
            return new Result(status, null, latency, "HTTP_" + status);
        }
        return Result.failure(latency, classify(e));
    }

    /** 异常归因:超时优先(遍历 cause 链),否则只记类名(不写 message/key/url)。 */
    private static String classify(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String n = t.getClass().getSimpleName();
            if (n.contains("Timeout")) return "TIMEOUT";
        }
        return "ERROR";
    }

    private String safeKey() {
        return props.getApiKey() == null ? "" : props.getApiKey();
    }

    /** 末尾斜杠归一(避免拼出 {@code //md});保留路径段。 */
    private static String stripTrailingSlash(String base) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/") && b.length() > 1) b = b.substring(0, b.length() - 1);
        return b;
    }

    /** 客户端结果(status/content/latencyMs/error);error 非空即失败。 */
    public record Result(int status, String content, long latencyMs, String error) {
        public boolean ok() {
            return error == null;
        }

        public static Result failure(long latencyMs, String error) {
            return new Result(0, null, latencyMs, error);
        }
    }
}
