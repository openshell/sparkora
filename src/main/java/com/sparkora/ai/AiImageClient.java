package com.sparkora.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
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

    /** 文生图：按序轮询候选模型，任一成功即返回第一个 URL。 */
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
                String resp = rest.post()
                        .uri("/v1/images/generations")
                        .header("Content-Type", "application/json")
                        .body(body)
                        .retrieve()
                        .body(String.class);
                String url = parseFirstUrl(resp);
                log.info("文生图成功 model={} url={}", model, url);
                return url;
            } catch (Exception e) {
                log.warn("文生图模型 {} 失败，尝试下一个: {}", model, e.getMessage());
                errs.append("[").append(model).append("] ").append(e.getMessage()).append("; ");
            }
        }
        throw new AiException("所有图片模型均失败: " + errs, null);
    }

    /**
     * 图生图：按序轮询候选模型。S1 占位实现（图生图需 multipart 上传参考图，留 S3b 补全 multipart 部分）。
     * 此方法保留轮询骨架，待 S3b 接入真实 /v1/images/edits。
     */
    public String generateImage2Image(String prompt, byte[] refImageBytes, String refFileName, String size) {
        throw new AiException("图生图接入待 S3b 实现（轮询骨架已就绪）", null);
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
}
