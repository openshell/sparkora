package com.sparkora.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.AiProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C1 {@link AiClient} 任务级参数接线单测（AC-任务级参数）：本地 stub HTTP 捕获真实请求体，断言
 *  - {@code chatJson} → 结构化低温 + response_format=json_object + 额度/模型透传；
 *  - {@code chat} → 正文高温 + 无 response_format；
 *  - {@code chatMessages} → 问答中温 + 多轮 system/user/assistant 顺序保真。
 *
 * <p>走真实 Spring AI OpenAI SDK（只把 base-url 指向本地 stub），验证的是最终 wire 契约，不连真实 AI/DB。
 */
class AiClientTaskOptionsTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String resp = "{\"id\":\"c\",\"choices\":[{\"index\":0,\"message\":{\"content\":\"{\\\"ok\\\":true}\"},"
                    + "\"finish_reason\":\"stop\"}],\"model\":\"m\","
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":9}}";
            byte[] r = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, r.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(r); }
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private AiClient client() {
        AiProperties p = new AiProperties();
        p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        p.setApiKey("sk-test-secret-key");
        p.setModel("test-model");
        return new AiClient(p);
    }

    private JsonNode lastRequest() throws Exception { return JSON.readTree(lastBody.get()); }

    @Test
    void chatJson_结构化低温_强制JSON对象_额度模型透传() throws Exception {
        client().chatJson("sys", "u", 4096);
        JsonNode body = lastRequest();

        assertEquals(0.2, body.path("temperature").asDouble(), 1e-9, "chatJson 用结构化低温");
        assertEquals("json_object", body.path("response_format").path("type").asText(), "强制 JSON 对象");
        assertEquals(4096, body.path("max_tokens").asInt(), "额度透传");
        assertEquals("test-model", body.path("model").asText(), "模型透传");
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertEquals("user", body.path("messages").get(1).path("role").asText());
    }

    @Test
    void chat_正文高温_无JSON约束() throws Exception {
        client().chat("sys", "u", 2048);
        JsonNode body = lastRequest();

        assertEquals(0.7, body.path("temperature").asDouble(), 1e-9, "chat 用正文高温");
        assertFalse(body.has("response_format"), "chat 不强制 JSON");
        assertEquals(2048, body.path("max_tokens").asInt());
    }

    @Test
    void chatMessages_问答中温_多轮角色顺序保真() throws Exception {
        List<Map<String, String>> msgs = new ArrayList<>();
        msgs.add(Map.of("role", "system", "content", "sys"));
        msgs.add(Map.of("role", "user", "content", "u1"));
        msgs.add(Map.of("role", "assistant", "content", "a1"));
        msgs.add(Map.of("role", "user", "content", "u2"));
        client().chatMessages(msgs, 512);
        JsonNode body = lastRequest();

        assertEquals(0.5, body.path("temperature").asDouble(), 1e-9, "问答用中温");
        JsonNode m = body.path("messages");
        assertEquals(4, m.size(), "多轮消息条数保真");
        assertEquals("system", m.get(0).path("role").asText());
        assertEquals("sys", m.get(0).path("content").asText());
        assertEquals("assistant", m.get(2).path("role").asText());
        assertEquals("a1", m.get(2).path("content").asText());
        assertEquals("u2", m.get(3).path("content").asText());
    }

    /** C4:ChatMemory advisor 装配历史,线序必须为 system → 历史(升序) → 本轮 user。 */
    @Test
    void chatWithMemory_历史经advisor装配_线序system历史本轮() throws Exception {
        List<Map<String, String>> history = new ArrayList<>();
        history.add(Map.of("role", "user", "content", "老问题"));
        history.add(Map.of("role", "assistant", "content", "老答案"));
        client().chatWithMemory("qa-1", "系统提示", "本轮问题", history, 512);
        JsonNode body = lastRequest();

        JsonNode m = body.path("messages");
        assertEquals(4, m.size(), "system + 2 条历史 + 本轮 = 4");
        assertEquals("system", m.get(0).path("role").asText());
        assertEquals("系统提示", m.get(0).path("content").asText());
        assertEquals("user", m.get(1).path("role").asText());
        assertEquals("老问题", m.get(1).path("content").asText());
        assertEquals("assistant", m.get(2).path("role").asText());
        assertEquals("老答案", m.get(2).path("content").asText());
        assertEquals("user", m.get(3).path("role").asText());
        assertEquals("本轮问题", m.get(3).path("content").asText());
        assertEquals(0.5, body.path("temperature").asDouble(), 1e-9, "问答中温");
    }

    @Test
    void 请求体不含密钥() {
        client().chat("s", "u", 100);
        assertFalse(lastBody.get().contains("sk-test-secret-key"), "请求体不得出现密钥(仅 Authorization 头)");
    }
}
