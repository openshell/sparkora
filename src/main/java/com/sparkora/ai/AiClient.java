package com.sparkora.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 轻量 AI 客户端：用 RestClient（spring-boot-starter-web 自带，同步阻塞）直调 axonhub
 * OpenAI 兼容的 /v1/chat/completions。
 *
 * 设计要点（来自真机联调）：
 *  - axonhub 把模型名路由到实际模型（如 deepseek-v4-pro-cus → glm-5.2），无需关心。
 *  - 部分 GLM 系模型会先输出 reasoning_content 再输出 content；只取 content。
 *  - 调用强制 response_format=json_object，要求模型返回纯 JSON，避免解析不稳。
 *  - 失败抛 AiException，由上层决定状态回滚与错误展示。
 */
@Slf4j
@Component
public class AiClient {

    private final AiProperties props;
    private final RestClient rest;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiClient(AiProperties props) {
        this.props = props;
        this.rest = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + props.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /** chat 调用结果。 */
    public record ChatResult(String content, String model, int totalTokens) {}

    /**
     * 调用 chat/completions，要求模型以 JSON 对象回应。
     * @param systemPrompt 系统指令
     * @param userPrompt   用户输入
     * @param maxTokens    上限（GLM 会先用一部分做 reasoning，需给足）
     * @return ChatResult
     */
    public ChatResult chatJson(String systemPrompt, String userPrompt, int maxTokens) {
        Map<String, Object> body = Map.of(
                "model", resolveTextModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                "temperature", props.getTemperature(),
                "max_tokens", maxTokens,
                "response_format", Map.of("type", "json_object")
        );
        try {
            String resp = rest.post()
                    .uri("/v1/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseChat(resp);
        } catch (Exception e) {
            throw new AiException("AI chat 调用失败: " + e.getMessage(), e);
        }
    }

    private ChatResult parseChat(String resp) {
        try {
            JsonNode root = mapper.readTree(resp);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new AiException("AI 返回无 choices: " + truncate(resp), null);
            }
            JsonNode msg = choices.get(0).path("message");
            String content = msg.path("content").asText("");
            if (content.isBlank()) {
                throw new AiException("AI content 为空（可能 reasoning_content 截断，需增大 max_tokens）: " + truncate(resp), null);
            }
            int tokens = root.path("usage").path("total_tokens").asInt(0);
            String model = root.path("model").asText("");
            return new ChatResult(content, model, tokens);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("解析 AI 返回失败: " + truncate(resp), e);
        }
    }

    private String resolveTextModel() {
        String m = props.getModel();
        if (m == null || m.isBlank()) throw new AiException("AI_MODEL 未配置", null);
        return m;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
