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
        // 读超时/连接超时消费 AI_TIMEOUT_MS(.env),默认 120s;AI 卡死不再无限占用请求线程
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

    /** chat 调用结果。 */
    public record ChatResult(String content, String model, int totalTokens) {}

    /**
     * AI 输出 JSON 容错清洗(2026-09-10):模型偶发违反 response_format=json_object 约束,
     * 在字符串值内输出裸换行/制表等控制字符,Jackson 严格模式直接报
     * "Illegal unquoted character (CTRL-CHAR, code 10)" 导致整版生成失败。
     * 处理:①剥 markdown 代码围栏;②把 JSON 字符串字面量内部的裸控制字符转义成 unicode 转义
     * (逐字符扫描,只在引号内的普通字符上生效,不会破坏已有的合法转义序列)。
     * 供所有「AI 产物 JSON 解析点」统一调用(VersionService/ImitationService 等)。
     */
    public static String sanitizeAiJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        // 剥 ```json ... ``` / ``` ... ``` 围栏(模型偶发无视"不要包代码块围栏")
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNl >= 0 && lastFence > firstNl) s = s.substring(firstNl + 1, lastFence);
            else s = s.substring(firstNl >= 0 ? firstNl + 1 : 3);
            s = s.trim();
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        boolean inStr = false, esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { out.append(c); esc = false; continue; }   // 已是合法转义,原样带过
            if (c == '\\' && inStr) { out.append(c); esc = true; continue; }
            if (c == '"') { inStr = !inStr; out.append(c); continue; }
            if (inStr && c < 0x20) {   // 字符串值内的裸控制字符 → 转义
                out.append(String.format("\\u%04x", (int) c));
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

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
            return parseChat(resp, true);
        } catch (Exception e) {
            throw new AiException("AI chat 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 普通文本 chat(非 JSON 约束;S9 深度写作用)。
     */
    public ChatResult chat(String systemPrompt, String userPrompt, int maxTokens) {
        Map<String, Object> body = Map.of(
                "model", resolveTextModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                "temperature", props.getTemperature(),
                "max_tokens", maxTokens
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

    /**
     * 多轮消息 chat(C4 知识问答,不强制 JSON;S12)。
     * messages 每项为 {role, content},按顺序原样送模型(system / user / assistant 交替)。
     * temperature/model 同 {@link #chat};复用同一 RestClient 与 parseChat。
     *
     * @param messages  有序消息列表
     * @param maxTokens 上限
     * @return ChatResult
     */
    public ChatResult chatMessages(List<Map<String, String>> messages, int maxTokens) {
        Map<String, Object> body = Map.of(
                "model", resolveTextModel(),
                "messages", messages,
                "temperature", props.getTemperature(),
                "max_tokens", maxTokens
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
        return parseChat(resp, false);
    }

    /**
     * @param requireJson 该次调用是否强制 response_format=json_object。
     *  Model 命中 max_tokens 被截断(finish_reason=length)时,JSON 内容必然是半截,
     *  直接给出可操作的错误(增大 max_tokens),避免上层 Jackson 抛
     *  "Unexpected end-of-input" 这类无法定位的解析报错(2026-09-13 ClarifyService 实测)。
     */
    private ChatResult parseChat(String resp, boolean requireJson) {
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
            if (requireJson && "length".equals(choices.get(0).path("finish_reason").asText())) {
                throw new AiException("AI 输出被 max_tokens 截断（finish_reason=length），JSON 不完整，请增大 max_tokens: " + truncate(content), null);
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
