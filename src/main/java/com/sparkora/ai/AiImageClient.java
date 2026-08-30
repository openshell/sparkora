package com.sparkora.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 图片生成客户端：封装「多模型按序轮询」。
 *
 * 背景：axonhub 上的图片模型（如 gpt-image-2-url）不稳定，单个模型偶发超时/5xx。
 * AiProperties.imageModelList() 给出逗号分隔的候选模型，本类按序尝试：
 *  前一个非 4xx 业务错误（连接失败/超时/5xx）时切到下一个；遇 4xx（如鉴权/参数错）也切，避免卡死。
 *  全部失败才抛 AiException，异常信息列出每个模型的失败原因。
 *
 * 接口（OpenAI 兼容，axonhub）：
 *  - 文生图：POST /v1/images/generations  → { model, prompt, n, size }
 *  - 图生图：POST /v1/images/edits         → multipart: model, prompt, image, size, n
 *
 * 返回统一为图片 URL（gpt-image-2-url 这类模型返回 data[].url）。
 */
@Slf4j
@Component
public class AiImageClient {

    private final AiProperties props;
    private final RestClient rest;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiImageClient(AiProperties props) {
        this.props = props;
        // 读超时消费 AI_TIMEOUT_MS(.env);图片生成较慢,读超时放宽一倍,连接超时仍 10s
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofMillis(props.getTimeoutMs() * 2));
        this.rest = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .defaultHeader("Authorization", "Bearer " + props.getApiKey())
                .build();
    }

    /** 文生图：按序轮询候选模型，任一成功即返回 URL（http/https）或 data URL（base64）。 */
    public String generateText2Image(String prompt, String size) {
        List<String> models = props.imageModelList();
        if (models.isEmpty()) throw new AiException("AI_IMAGE_MODELS / AI_IMAGE_MODEL 均未配置", null);
        StringBuilder errs = new StringBuilder();
        for (String model : models) {
            try {
                Map<String, Object> body = Map.of(
                        "model", model,
                        "prompt", prompt,
                        "n", 1,
                        "size", size == null ? "1024x1024" : size);
                String resp = postForJsonText("/v1/images/generations", body);
                String url = parseFirstUrl(resp);
                log.info("文生图成功 model={} url={}", model, shorten(url));
                return url;
            } catch (Exception e) {
                log.warn("文生图模型 {} 失败，尝试下一个: {}", model, e.getMessage());
                errs.append("[").append(model).append("] ").append(e.getMessage()).append("; ");
            }
        }
        throw new AiException("所有图片模型均失败: " + errs, null);
    }

    /**
     * 图生图：multipart POST /v1/images/edits，按序轮询候选模型（S3b 实现）。
     * @param refImageBytes 参考图字节
     * @param refFileName   参考图文件名（供 multipart 的 filename；png/jpg/webp）
     */
    public String generateImage2Image(String prompt, byte[] refImageBytes, String refFileName, String size) {
        List<String> models = props.imageModelList();
        if (models.isEmpty()) throw new AiException("AI_IMAGE_MODELS / AI_IMAGE_MODEL 均未配置", null);
        if (refImageBytes == null || refImageBytes.length == 0)
            throw new AiException("图生图参考图为空", null);
        StringBuilder errs = new StringBuilder();
        for (String model : models) {
            try {
                MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
                body.add("model", model);
                body.add("prompt", prompt);
                if (size != null && !size.isBlank()) body.add("size", size);
                body.add("n", 1);
                body.add("image", new ByteArrayResource(refImageBytes) {
                    @Override public String getFilename() {
                        return refFileName == null || refFileName.isBlank() ? "reference.png" : refFileName;
                    }
                });
                String resp = postMultipartForJsonText("/v1/images/edits", body);
                String url = parseFirstUrl(resp);
                log.info("图生图成功 model={} refSize={}B url={}", model, refImageBytes.length, shorten(url));
                return url;
            } catch (Exception e) {
                log.warn("图生图模型 {} 失败，尝试下一个: {}", model, e.getMessage());
                errs.append("[").append(model).append("] ").append(e.getMessage()).append("; ");
            }
        }
        throw new AiException("所有图片模型均失败(图生图): " + errs + "。若提示接口不存在，"
                + "说明该模型不支持 /v1/images/edits，请改用文生图或更换 AI_IMAGE_MODELS。", null);
    }

    /**
     * POST JSON 并以 byte[] 收响应再转字符串。
     * 不用 body(String.class)的原因:个别网关偶发给 JSON 响应标 application/octet-stream,
     * String 转换器拒绝该 Content-Type 会抛 "Error while extracting response" 掩盖真实结果;
     * byte[] 收取与 Content-Type 无关,响应文本原样交由 parseFirstUrl 解析。
     */
    private String postForJsonText(String path, Object body) {
        byte[] bytes = rest.post()
                .uri(path)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(byte[].class);
        return bytes == null ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** multipart 版本,同上以 byte[] 收取(见 postForJsonText 注释)。 */
    private String postMultipartForJsonText(String path, MultiValueMap<String, Object> multipart) {
        byte[] bytes = rest.post()
                .uri(path)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart)
                .retrieve()
                .body(byte[].class);
        return bytes == null ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 从 images 响应取第一个 data[].url（或 b64_json，若模型返回 base64）。 */
    private String parseFirstUrl(String resp) {
        try {
            JsonNode data = mapper.readTree(resp).path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new AiException("图片返回无 data: " + resp, null);
            }
            JsonNode first = data.get(0);
            String url = first.path("url").asText("");
            if (!url.isBlank()) return url;
            String b64 = first.path("b64_json").asText("");
            if (!b64.isBlank()) return "data:image/png;base64," + b64;
            throw new AiException("图片返回无 url/b64_json: " + resp, null);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("解析图片返回失败: " + resp, e);
        }
    }

    /** 日志里长 URL/dataURL 截断，避免刷屏。 */
    private static String shorten(String s) {
        if (s == null) return "null";
        return s.length() > 80 ? s.substring(0, 80) + "…(" + s.length() + "B)" : s;
    }
}
