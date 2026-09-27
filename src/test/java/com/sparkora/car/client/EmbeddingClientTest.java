package com.sparkora.car.client;

import com.sparkora.ai.AiException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EmbeddingClient 维度校验单测（09-27 R7/AC8）。
 * 以本地 stub HTTP 服务返回指定长度的 embedding，断言：
 *  - 维度不等于配置 embeddingDim → 抛 AiException（中文提示含实际/期望维度）；
 *  - 维度相符 → 正常返回；
 *  - modelName() 返回配置模型。
 */
class EmbeddingClientTest {

    private HttpServer server;
    private volatile String embeddingJson = "[]";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", ex -> {
            String body = "{\"data\":[{\"embedding\":" + embeddingJson + "}]}";
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
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

    private EmbeddingClient client(int dim) {
        AiProperties p = new AiProperties();
        p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        p.setApiKey("k");
        p.setEmbeddingModel("test-embed");
        p.setEmbeddingDim(dim);
        return new EmbeddingClient(p);
    }

    @Test
    void 维度相符_正常返回() {
        embeddingJson = "[0.1,0.2,0.3]";
        assertEquals(3, client(3).embedList("x").size());
    }

    @Test
    void 维度不符_抛AiException含实际与期望() {
        embeddingJson = "[0.1,0.2,0.3]";
        AiException ex = assertThrows(AiException.class, () -> client(1024).embedList("x"));
        assertTrue(ex.getMessage().contains("维度不符"), ex.getMessage());
        assertTrue(ex.getMessage().contains("期望 1024"), ex.getMessage());
        assertTrue(ex.getMessage().contains("实际 3"), ex.getMessage());
        assertTrue(ex.getMessage().contains("test-embed"), ex.getMessage());
    }

    @Test
    void 默认维度为1024() {
        assertEquals(1024, new AiProperties().getEmbeddingDim());
    }

    @Test
    void modelName返回配置模型() {
        assertEquals("test-embed", client(1024).modelName());
    }
}
