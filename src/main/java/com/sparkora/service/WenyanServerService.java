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
import java.util.Map;

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
 *  - 错误统一 {code:-1,desc:中文原因} + HTTP 400/500;401 = key 无效（旧版本曾有挂起行为,客户端超时不放宽）。
 * 上传文件 TTL 10 分钟（server 端清理）,上传后须尽快 publish。
 *
 * 配置未配置时抛 IllegalStateException(中文),由调用方转 R.fail(400)。
 */
@Slf4j
@Service
public class WenyanServerService {

    private final WenyanProperties props;
    private final ObjectMapper json;
    private final RestClient rest;

    public WenyanServerService(WenyanProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        // 环境值常见「粘贴带出首尾空白」问题,会导致微信 40125 invalid appsecret / server 401 一类疑难;
        // 这里统一 trim 回写(@ConfigurationProperties 单例 bean,启动期一次性处理),杜绝空格类配置事故
        if (props.getServerUrl() != null) props.setServerUrl(props.getServerUrl().trim());
        if (props.getServerApiKey() != null) props.setServerApiKey(props.getServerApiKey().trim());
        // 连接 5s:内网/公网 server 建连都不该慢;读超时用发布超时(错误 key 挂起场景靠它兜底,不宜过长)
        this.rest = RestClient.builder()
                .requestFactory(org.springframework.boot.web.client.ClientHttpRequestFactories.get(
                        org.springframework.boot.web.client.ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(Duration.ofSeconds(5))
                                .withReadTimeout(Duration.ofMillis(Math.max(props.getPublishTimeoutMs(), 5000)))))
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

    /** 鉴权探针(GET /verify)。true=可用;false=key 无效或不可达(含挂起超时)。严格按 JSON success 字段判定。 */
    public boolean verify() {
        try {
            String body = get("/verify");
            return json.readTree(body == null ? "" : body).path("success").asBoolean(false);
        } catch (Exception e) {
            log.warn("wenyan-server verify 失败: {}", e.getMessage());
            return false;
        }
    }

    /** 探活 GET /health,返回版本描述(如 "wenyan-cli 2.0.11");不可达抛中文异常。 */
    public String health() {
        try {
            String body = get("/health");
            JsonNode node = json.readTree(body == null ? "" : body.trim());
            return node.path("service").asText("wenyan-server") + " " + node.path("version").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("wenyan-server 不可达: " + e.getMessage(), e);
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

    /** 发布:按 fileId 发布到公众号草稿箱,返回 media_id。fileId 须为 10 分钟内上传的 .json 文件。 */
    public String publish(String fileId) {
        log.info("wenyan-server publish 开始: url={} fileId={}", props.getServerUrl() + "/publish", fileId);
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
            log.error("wenyan-server /publish 请求失败", e);
            throw new IllegalStateException("发布请求失败: " + e.getMessage(), e);
        }
    }

    // ==================== 内部:统一 HTTP 与错误解析 ====================

    private String get(String path) {
        try {
            return rest.get()
                    .uri(props.getServerUrl() + path)
                    .header("x-api-key", props.getServerApiKey())
                    .retrieve()
                    .body(String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            throw new IllegalStateException(describeServerError(e));
        } catch (Exception e) {
            throw new IllegalStateException("wenyan-server 请求失败: " + e.getMessage(), e);
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
            throw new IllegalStateException("wenyan-server 请求失败: " + e.getMessage(), e);
        }
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