package com.sparkora.service;

import com.sparkora.config.WenyanProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * wenyan-server 发布通道客户端（S5,方案 A 双通道的发布侧）。
 *
 * 对接 @wenyan-md/cli 2.0.11 serve 模式(接口形状 2026-09-01 实测):
 *  - GET  /health            → {status,service,version}（探活,public）
 *  - GET  /verify            → 200 {success:true,message:"Authorized"} / 401 {code:-1,desc}（探针,注意是 GET）
 *  - POST /upload            → multipart 字段名 file（限 md/css/json/图片,≤10MB）
 *                              成功 200 {success:true,data:{fileId,originalFilename,mimetype,size}}
 *  - POST /publish           → JSON {"fileId": "..."}（fileId 须为上传的 .json 文件;appId 可选,默认 server 端凭据）
 *                              成功 200 {media_id}
 *  - 错误统一 {code:-1,desc:中文原因} + HTTP 400/500;401 = key 无效。
 * 上传文件 TTL 10 分钟（server 端清理）,上传后须尽快 publish。
 *
 * <p><b>超时分两档,且探针不得借用发布档</b>(09-27-wenyan-stale-conn):
 * {@code /verify}、{@code /health} 走 {@code probeRest}(短阈值,通道不可用要秒级失败,
 * 否则整个发布页被挂住);{@code /upload}、{@code /publish} 走 {@code rest}(长阈值,
 * 按实测实耗设定,见 {@link WenyanProperties#publishTimeoutMs})。
 * <p>注意历史上「错误 key 会挂起 → 客户端超时不宜放宽」是<b>已被实测推翻</b>的旧结论(当前部署
 * 无效 key 即刻 401),照它把阈值压在 30s 正是 09-27 事故的根因;详见 docs/wenyan.md §7。
 *
 * 配置未配置时抛 IllegalStateException(中文),由调用方转 R.fail(400)。
 */
@Slf4j
@Service
public class WenyanServerService {

    /**
     * 需从用户可见文案中剔除的框架内部串 → 中性中文替身。
     * <p>这些串只说明「Spring 读/解析响应体时出问题了」,既不指向根因也不含可执行动作,
     * 透给用户只会让人以为系统坏了(09-27 AC3:提示须可读、不得含框架内部类名)。
     * <p><b>键一律小写</b>:匹配时对异常消息做 {@code toLowerCase()} 后再 contains,
     * 否则 "Error while extracting response…" 因首字母大写而漏判(已被单测钉死)。
     * <p>注意片段要按框架的<b>真实</b>拼写写(camelCase 不插空格):Spring 实际抛的是
     * {@code No suitable HttpMessageConverter for …},写成 "http message converter" 永远匹配不上。
     */
    private static final Map<String, String> FRAMEWORK_NOISE = new LinkedHashMap<>();
    static {
        // 读响应体超时/中断的典型包装
        FRAMEWORK_NOISE.put("error while extracting response", "读取响应失败(服务端可能已中断连接)");
        FRAMEWORK_NOISE.put("no suitable httpmessageconverter", "服务端响应内容无法解析");
        FRAMEWORK_NOISE.put("unknown content type", "服务端响应内容无法解析");
    }

    /** {@link #describeTransportFailure} 判定「读超时」时返回的归因串。 */
    static final String TIMEOUT_REASON = "请求超时";
    /**
     * /publish 读超时的用户可见提示(AC3)。
     * <p>核心语义是「<b>超时不等于失败</b>」:服务端很可能已写入草稿箱,必须先让用户去确认再决定重发,
     * 否则重试会造出重复草稿(09-27 事故根因)。措辞不得含框架内部类名。
     */
    static final String PUBLISH_TIMEOUT_MSG =
            "发布超时:服务端可能仍在处理并已写入公众号草稿箱,请先到草稿箱确认,确认缺失后再重发";
    /** /publish 其它传输层失败的提示前缀(后接中文归因)。 */
    static final String PUBLISH_TRANSPORT_FAIL_MSG_PREFIX = "发布请求失败(";
    /** /publish 传输层失败的「先确认草稿箱」统一尾巴(非幂等调用共用)。 */
    static final String PUBLISH_DRAFT_HINT_MSG = "):服务端可能已写入草稿,请先到草稿箱确认";

    private final WenyanProperties props;
    private final ObjectMapper json;
    /** 发布链路(upload/publish)客户端:读超时 = publishTimeoutMs(实耗 ~43s,故默认 180s)。 */
    private final RestClient rest;
    /** 探针(/verify、/health)专用客户端:读超时 = verifyTimeoutMs(默认 5s),与发布解耦。 */
    private final RestClient probeRest;

    public WenyanServerService(WenyanProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        // 环境值常见「粘贴带出首尾空白」问题,会导致微信 40125 invalid appsecret / server 401 一类疑难;
        // 这里统一 trim 回写(@ConfigurationProperties 单例 bean,启动期一次性处理),杜绝空格类配置事故
        if (props.getServerUrl() != null) props.setServerUrl(props.getServerUrl().trim());
        if (props.getServerApiKey() != null) props.setServerApiKey(props.getServerApiKey().trim());
        // 连接 5s:内网/公网 server 建连都不该慢。读超时按用途分两档(见字段注释):
        // 探针要「快速失败」,否则通道不可用会把发布页挂住整个发布超时;发布要「够长」,否则假失败+重复草稿。
        this.rest = buildRest(Duration.ofMillis(Math.max(props.getPublishTimeoutMs(), 5000)));
        this.probeRest = buildRest(Duration.ofMillis(Math.max(props.getVerifyTimeoutMs(), 1000)));
    }

    /** 构造指定读超时的 RestClient;连接超时固定 5s。 */
    private static RestClient buildRest(Duration readTimeout) {
        return RestClient.builder()
                .requestFactory(org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder.jdk().build(
                        org.springframework.boot.http.client.HttpClientSettings.defaults()
                                .withConnectTimeout(Duration.ofSeconds(5))
                                .withReadTimeout(readTimeout)))
                .build();
    }

    /** 发布通道是否可用(配置齐备)。 */
    public boolean configured() {
        return props.serverConfigured();
    }

    /** 校验配置,未配置抛中文异常(调用方转 400)。 */
    public void requireConfigured() {
        if (props.getServerUrl() == null || props.getServerUrl().isBlank())
            throw new IllegalStateException("发布通道未配置(WENYAN_MCP_SERVER_URL)");
        if (props.getServerApiKey() == null || props.getServerApiKey().isBlank())
            throw new IllegalStateException("发布通道未配置(WENYAN_MCP_SERVER_API_KEY)");
    }

    /**
     * 鉴权探针(GET /verify)。true=可用;false=key 无效或不可达(含挂起超时)。严格按 JSON success 字段判定。
     * 走探针专用客户端(短读超时):通道不可用时要快速失败,否则会把「发布参数」接口乃至整个发布页
     * 挂住整个发布超时(180s)。只读探针,不加自动重试——通道真不可达时重试只会让等待翻倍。
     */
    public boolean verify() {
        try {
            String body = get("/verify", probeRest);
            return json.readTree(body == null ? "" : body).path("success").asBoolean(false);
        } catch (Exception e) {
            log.warn("wenyan-server verify 失败(探针超时 {}ms): {}", props.getVerifyTimeoutMs(), e.getMessage());
            return false;
        }
    }

    /** 探活 GET /health,返回版本描述(如 "wenyan-cli 2.0.11");不可达抛中文异常。走探针专用客户端。 */
    public String health() {
        try {
            String body = get("/health", probeRest);
            JsonNode node = json.readTree(body == null ? "" : body.trim());
            return node.path("service").asText("wenyan-server") + " " + node.path("version").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("wenyan-server 不可达: " + describeTransportFailure(e), e);
        }
    }

    /**
     * 上传发布内容 JSON(gzhContent 序列化结果),返回 server 生成的 fileId。
     * 文件名必须带 .json 后缀(server 按扩展名放行,发布时校验必须是 .json)。
     */
    public String uploadJson(String fileName, String jsonContent) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new org.springframework.core.io.ByteArrayResource(
                jsonContent.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() {
                return fileName.endsWith(".json") ? fileName : fileName + ".json";
            }
        });
        JsonNode node = postForData("/upload", body, MediaType.MULTIPART_FORM_DATA);
        String fileId = node.path("fileId").asText("");
        if (fileId.isBlank()) throw new IllegalStateException("wenyan-server 上传成功但未返回 fileId");
        return fileId;
    }

    /**
     * 发布:按 fileId 发布到公众号草稿箱,返回 media_id。fileId 须为 10 分钟内上传的 .json 文件。
     *
     * <p><b>绝不自动重试</b>:本方法写公众号草稿箱,非幂等——响应丢失但服务端已写入时重试会产生
     * <b>重复草稿</b>(09-27 事故:阈值 30s < 实耗 43s,客户端超时退出而服务端随后成功写入,
     * 用户重试又一篇)。超时时改为提示「可能已写入草稿,请先到草稿箱确认」。
     */
    public String publish(String fileId) {
        log.info("wenyan-server publish 开始: url={} fileId={} 读超时={}ms",
                props.getServerUrl() + "/publish", fileId, props.getPublishTimeoutMs());
        try {
            java.util.Map<String, String> payload = Map.of("fileId", fileId);
            String resp = rest.post()
                    .uri(props.getServerUrl() + "/publish")
                    .header("x-api-key", props.getServerApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            JsonNode node = json.readTree(resp == null ? "" : resp);
            // 成功唯一形态:{media_id: "..."};错误形态 {code:-1,desc} 不会到这(retrieve 已抛 4xx/5xx)
            String mediaId = node.path("media_id").asText("");
            if (mediaId.isBlank()) {
                String shortResp = resp == null ? "" : resp;
                if (shortResp.length() > 200) shortResp = shortResp.substring(0, 200) + "…";
                throw new IllegalStateException("发布响应缺少 media_id: " + shortResp);
            }
            return mediaId;
        } catch (IllegalStateException e) {
            throw e;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("wenyan-server /publish HTTP {}: body={}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new IllegalStateException(describeServerError(e));
        } catch (Exception e) {
            String cause = describeTransportFailure(e);
            log.error("wenyan-server /publish 请求失败({}),读超时阈值 {}ms,未自动重试(非幂等,防重复草稿)",
                    cause, props.getPublishTimeoutMs(), e);
            if (TIMEOUT_REASON.equals(cause)) throw new IllegalStateException(PUBLISH_TIMEOUT_MSG, e);
            throw new IllegalStateException(PUBLISH_TRANSPORT_FAIL_MSG_PREFIX + cause
                    + PUBLISH_DRAFT_HINT_MSG, e);
        }
    }

    // ==================== 内部:统一 HTTP 与错误解析 ====================

    /**
     * GET 指定端点;{@code client} 决定读超时档位(探针用 probeRest,发布用 rest)。
     * <p>刻意<b>不提供</b>「默认走发布客户端」的 1 参重载:探针/发布超时已解耦(09-27),留一个
     * 默认值等于给「探针误用长超时」留后门(正是 AC4 事故形态),且该重载当前无调用方(死代码)。
     */
    private String get(String path, RestClient client) {
        try {
            return client.get()
                    .uri(props.getServerUrl() + path)
                    .header("x-api-key", props.getServerApiKey())
                    .retrieve()
                    .body(String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            throw new IllegalStateException(describeServerError(e));
        } catch (Exception e) {
            throw new IllegalStateException("wenyan-server 请求失败: " + describeTransportFailure(e), e);
        }
    }

    /** POST multipart/JSON 载荷到 server,成功返回响应 JSON 的 data 节点(无 data 节点则整个响应)。 */
    private JsonNode postForData(String path, Object body, MediaType contentType) {
        try {
            String resp = rest.post()
                    .uri(props.getServerUrl() + path)
                    .header("x-api-key", props.getServerApiKey())
                    .contentType(contentType)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode node = json.readTree(resp == null ? "" : resp);
            JsonNode data = node.path("data");
            if (node.has("success")) {
                if (!node.path("success").asBoolean(false))
                    throw new IllegalStateException("wenyan-server 返回失败: " + descOf(node));
                return data.isMissingNode() ? node : data;
            }
            if (node.hasNonNull("code") && node.get("code").asInt(0) != 0)
                throw new IllegalStateException("wenyan-server 返回失败: " + descOf(node));
            return data.isMissingNode() ? node : data;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            throw new IllegalStateException(describeServerError(e));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("wenyan-server 请求失败: " + describeTransportFailure(e), e);
        }
    }

    /**
     * 传输层失败(超时/连接被关/连接不上)的中文归因。
     *
     * <p>存在的理由:框架在读响应体超时时抛的是
     * {@code RestClientException: Error while extracting response for type [java.lang.String] and
     * content type [application/octet-stream]}——纯框架内部串,既不说明「超时」也不说明「服务端还在跑」,
     * 用户完全无法据此排障(09-27 实际投诉的报错就是它)。这里把它翻译成可执行的中文语义。
     *
     * <p>注意超时与非超时的处置完全不同:<b>超时不等于失败</b>——服务端很可能仍在处理,
     * 甚至已经写入微信草稿箱(实测 /publish 实耗 43s 而阈值曾为 30s),此时<b>绝不能</b>自动重试
     * (/publish 非幂等,重试会产生重复草稿)。
     *
     * <p>包可见(非 private)以便 {@code WenyanServerServiceTransportTest} 直接单测这条纯函数。
     */
    static String describeTransportFailure(Throwable e) {
        // 单次遍历整条 cause 链:JDK HttpClient 抛 HttpTimeoutException、Simple 工厂抛 SocketTimeoutException,
        // 均可能包在 RestClientException / ResourceAccessException 里,只看顶层会漏判。
        String frameworkNoise = null;   // 命中框架串则记下,但让「超时」判定优先
        Throwable root = e;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException          // SimpleClientHttpRequestFactory / HttpURLConnection
                    || t instanceof java.net.http.HttpTimeoutException // JdkClientHttpRequestFactory(本项目实际使用)
                    || t instanceof java.io.InterruptedIOException) {  // 兜底:部分连接池用此族表达超时
                return TIMEOUT_REASON;
            }
            String m = t.getMessage();
            // 框架内部串(读响应体失败/内容类型无法反序列化)无信息量:命中即换成中性中文,
            // 不把 [java.lang.String] / content type 之类的类名串透给用户(AC3)。
            // 片段常量为小写,故比对前统一小写化(大小写敏感会漏判,单测已钉死)。
            if (frameworkNoise == null && m != null) {
                String lower = m.toLowerCase();
                for (Map.Entry<String, String> hit : FRAMEWORK_NOISE.entrySet()) {
                    if (lower.contains(hit.getKey())) { frameworkNoise = hit.getValue(); break; }
                }
            }
            if (t.getCause() != t) root = t;
        }
        if (frameworkNoise != null) return frameworkNoise;
        // 兜底:取最内层(根因)消息——比顶层 "I/O error on POST request for ..." 更有指向性;
        // 并压掉换行、截断,避免长串灌进 last_publish_error 与前端黄条。
        String msg = root.getMessage() != null && !root.getMessage().isBlank()
                ? root.getMessage() : e.getMessage();
        if (msg == null || msg.isBlank()) return "连接中断";
        msg = msg.replaceAll("\\s+", " ").trim();
        return msg.length() > 200 ? msg.substring(0, 200) + "…" : msg;
    }

    /** HTTP 非 2xx:server 错误体是 {code:-1,desc},网络层异常(ReadTimeout/Connect)给中文原因。 */
    private String describeServerError(org.springframework.web.client.HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        if (body != null && !body.isBlank()) {
            try {
                JsonNode node = json.readTree(body);
                String desc = node.path("desc").asText("");
                if (node.hasNonNull("code") && node.get("code").asInt(0) != 0 && !desc.isBlank()) {
                    log.error("wenyan-server 返回业务失败: desc={}", desc);
                    return wechatErrorHint(desc);
                }
            } catch (Exception ignore) { }
            String shortBody = body.length() > 200 ? body.substring(0, 200) + "…" : body;
            log.error("wenyan-server HTTP {} 非预期错误体: {}", e.getStatusCode().value(), shortBody);
            return "wenyan-server 错误(" + e.getStatusCode().value() + "): " + shortBody;
        }
        if (e.getCause() instanceof java.net.SocketTimeoutException)
            return "wenyan-server 响应超时(若已核对 API Key,请检查 server 是否存活)";
        log.error("wenyan-server HTTP {} 错误: {}", e.getStatusCode().value(), e.getMessage());
        return "wenyan-server 错误: " + e.getMessage();
    }

    /**
     * 微信侧错误码翻译(错误源自 wenyan-server → 微信开放接口):
     *  40125 invalid appsecret:AppSecret 不对(或刚重置未同步)——本服务不持有 AppSecret,
     *    需在 wenyan-server 所在机器修正其 WECHAT_APP_SECRET 并重启 server;
     *  40001 credential 拿不到 / access_token 已吊销:AppSecret 失效或 IP 白名单拦截;
     *  40164 IP 不在白名单:把 server 出口 IP 加入公众号「IP 白名单」。
     */
    private String wechatErrorHint(String desc) {
        if (desc.contains("40125"))
            return "微信 40125: AppSecret 不正确或已重置。AppSecret 由 wenyan-server 持有,请在其所在机器修正 WECHAT_APP_SECRET 并重启 server 后重试(" + desc + ")";
        if (desc.contains("40001"))
            return "微信 40001: 凭据无效或 IP 白名单拦截。请核对 wenyan-server 侧 WECHAT_APP_SECRET、确认本机出口 IP 已加入公众号「IP 白名单」(" + desc + ")";
        if (desc.contains("40164"))
            return "微信 40164: wenyan-server 出口 IP 不在公众号「IP 白名单」内,请在公众号后台添加后重试(" + desc + ")";
        return desc;
    }

    private static String descOf(JsonNode node) {
        return node.path("desc").asText(node.toString());
    }
}