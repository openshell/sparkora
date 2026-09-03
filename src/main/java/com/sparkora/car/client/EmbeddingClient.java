package com.sparkora.car.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiException;
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
 * 向量化客户端。调 axonhub OpenAI 兼容的 /v1/embeddings。
 * 实测模型 Qwen3-Embedding-8B,向量维度 1024。
 */
@Slf4j
@Component
public class EmbeddingClient {

    private final AiProperties props;
    private final RestClient rest;
    private final ObjectMapper mapper = new ObjectMapper();

    public EmbeddingClient(AiProperties props) {
        this.props = props;
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofMillis(props.getTimeoutMs()));
        this.rest = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .defaultHeader("Authorization", "Bearer " + props.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * 对单个文本生成向量,返回 pgvector 字面量字符串(如 "[0.1,0.2,...]")。
     */
    public String embed(String text) {
        List<Double> vec = embedList(text);
        return toPgVector(vec);
    }

    /** 对单个文本生成向量,返回 double 列表。 */
    public List<Double> embedList(String text) {
        String model = props.getEmbeddingModel();
        if (model == null || model.isBlank()) throw new AiException("AI_EMBEDDING_MODEL 未配置", null);
        Map<String, Object> body = Map.of(
                "model", model,
                "input", List.of(text));
        try {
            String resp = rest.post()
                    .uri("/v1/embeddings")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode root = mapper.readTree(resp);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new AiException("embedding 返回无 data: " + truncate(resp), null);
            }
            JsonNode emb = data.get(0).path("embedding");
            if (!emb.isArray() || emb.isEmpty()) {
                throw new AiException("embedding 为空: " + truncate(resp), null);
            }
            return mapper.convertValue(emb, mapper.getTypeFactory().constructCollectionType(List.class, Double.class));
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("embedding 调用失败: " + e.getMessage(), e);
        }
    }

    /** 把 double 列表转成 pgvector 字面量字符串。 */
    public static String toPgVector(List<Double> vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(vec.get(i));
        }
        return sb.append("]").toString();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
