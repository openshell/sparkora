package com.sparkora.ai;

import com.sparkora.config.AiProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiClient reasoning 透出单测(10-02-brief-reasoning-maxtokens R2)。
 *
 * <p>本地 stub HTTP 服务模拟 OpenAI 兼容响应,断言:
 *  - {@code message.reasoning} 优先透出(deepseek-v4.1-flash 实测字段名);
 *  - 缺省回退 {@code message.reasoning_content};
 *  - 两者皆无 → null(非推理模型/历史行为不变);
 *  - 超长 reasoning 按 {@link AiClient#REASONING_MAX_CHARS} 截断;
 *  - 保留 3 参/4 参构造器(既有调用方与测试零改动)。
 */
class AiClientReasoningTest {

    private HttpServer server;
    private volatile String responseBody = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AiClient client() {
        AiProperties p = new AiProperties();
        p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        p.setApiKey("k");
        p.setModel("test-model");
        return new AiClient(p);
    }

    /** 构造响应;{@code insideMessage} 为 message 对象内追加字段(如 {@code ,"reasoning":"..."})。 */
    private static String resp(String insideMessage) {
        return "{\"choices\":[{\"message\":{\"content\":\"正文\"" + insideMessage + "},"
                + "\"finish_reason\":\"stop\"}],\"model\":\"m\",\"usage\":{\"total_tokens\":9}}";
    }

    @Test
    void reasoning字段_优先透出() {
        responseBody = resp(",\"reasoning\":\"这是思考过程\"");
        AiClient.ChatResult cr = client().chatJson("s", "u", 100);
        assertEquals("这是思考过程", cr.reasoning());
    }

    @Test
    void reasoning缺省_回退reasoning_content() {
        responseBody = resp(",\"reasoning_content\":\"GLM 系思考过程\"");
        AiClient.ChatResult cr = client().chatJson("s", "u", 100);
        assertEquals("GLM 系思考过程", cr.reasoning());
    }

    @Test
    void 无reasoning字段_透出null() {
        responseBody = resp("");
        AiClient.ChatResult cr = client().chatJson("s", "u", 100);
        assertNull(cr.reasoning(), "非推理模型无 reasoning 应为 null");
    }

    @Test
    void reasoning超长_按上限截断() {
        String huge = "思".repeat(AiClient.REASONING_MAX_CHARS + 500);
        responseBody = resp(",\"reasoning\":\"" + huge + "\"");
        AiClient.ChatResult cr = client().chatJson("s", "u", 100);
        assertEquals(AiClient.REASONING_MAX_CHARS, cr.reasoning().length(), "reasoning 必须截断到上限");
    }

    @Test
    void 保留3参与4参构造器_reasoning为null() {
        AiClient.ChatResult three = new AiClient.ChatResult("c", "m", 1);
        assertNull(three.reasoning());
        assertNull(three.finishReason());
        AiClient.ChatResult four = new AiClient.ChatResult("c", "m", 1, "stop");
        assertEquals("stop", four.finishReason());
        assertNull(four.reasoning());
        assertTrue(AiClient.REASONING_MAX_CHARS == 20000, "常量口径 20000");
    }
}
