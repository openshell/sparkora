package com.sparkora.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.AiProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C6 文生图经 Spring AI {@link ImageModel} 的契约单测：本地 stub HTTP + **真实
 * {@link OpenAiImageModel}**（官方 com.openai SDK），覆盖
 *  - url / b64_json 两路响应；
 *  - octet-stream 包裹的 JSON 仍能解析（旧 RestClient 需 byte[] 的根因验证）；
 *  - 多模型按序轮询（首个失败次个成功）；
 *  - 全失败文案含「所有图片模型均失败」；
 *  - wire 请求体透传 model/n/size。
 *
 * <p>图生图（edits）契约不在此文件，仍由 {@link AiImageClientMultiRefTest} 覆盖。
 */
class AiImageClientText2ImageTest {

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final CopyOnWriteArrayList<String> bodies = new CopyOnWriteArrayList<>();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** responseFormat: "url" | "b64" | "octet" | "error"（按 model 名分支见下）。 */
    private volatile String mode = "url";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", ex -> {
            calls.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            if (body.contains("\"model\":\"bad")) {
                byte[] err = "{\"error\":{\"message\":\"boom\"}}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(500, err.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(err); }
                return;
            }
            String json;
            switch (mode) {
                case "b64" -> json = "{\"created\":1,\"data\":[{\"b64_json\":\"QUJD\"}]}";
                case "octet" -> json = "{\"created\":1,\"data\":[{\"url\":\"https://example.com/o.png\"}]}";
                default -> json = "{\"created\":1,\"data\":[{\"url\":\"https://example.com/a.png\"}]}";
            }
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            // octet-stream 场景：响应体仍是 JSON，但 Content-Type 被网关误标
            ex.getResponseHeaders().add("Content-Type",
                    "octet".equals(mode) ? "application/octet-stream" : "application/json");
            ex.sendResponseHeaders(200, resp.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(resp); }
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    /** 真实 OpenAiImageModel 指向本地 stub（复用生产同款 SDK 装配路径）。 */
    private AiImageClient client(String models) {
        AiProperties p = new AiProperties();
        p.setBaseUrl(baseUrl()); // AiProperties.baseUrl 为网关根地址（无 /v1）
        p.setApiKey("k");
        p.setImageModels(models);
        OpenAiImageOptions opts = OpenAiImageOptions.builder()
                .baseUrl(baseUrl() + "/v1")
                .apiKey("k")
                .timeout(Duration.ofSeconds(10))
                .maxRetries(0)
                .build();
        ImageModel model = OpenAiImageModel.builder().options(opts).build();
        return new AiImageClient(p, model);
    }

    private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @Test
    void url响应_返回url与命中模型() {
        mode = "url";
        AiImageClient.GenResult r = client("m1").generateText2Image("一只猫", "1024x1024");
        assertEquals("https://example.com/a.png", r.url());
        assertEquals("m1", r.model());
    }

    @Test
    void b64响应_转data_url() {
        mode = "b64";
        AiImageClient.GenResult r = client("m1").generateText2Image("一只猫", null);
        assertEquals("data:image/png;base64,QUJD", r.url());
        assertEquals("m1", r.model());
    }

    @Test
    void octet_stream响应_仍成功解析() {
        mode = "octet";
        AiImageClient.GenResult r = client("m1").generateText2Image("一只猫", "1024x1024");
        assertEquals("https://example.com/o.png", r.url());
    }

    @Test
    void 多模型轮询_首个失败次个成功() {
        mode = "url";
        AiImageClient.GenResult r = client("bad,m2").generateText2Image("一只猫", "1024x1024");
        assertEquals("m2", r.model());
        assertTrue(bodies.get(0).contains("\"model\":\"bad\""), "先试 bad");
        assertTrue(bodies.get(1).contains("\"model\":\"m2\""), "再试 m2");
    }

    @Test
    void 全失败_文案不变() {
        AiException ex = assertThrows(AiException.class,
                () -> client("bad,bad2").generateText2Image("一只猫", null));
        assertTrue(ex.getMessage().startsWith("所有图片模型均失败: "), ex.getMessage());
    }

    @Test
    void 未配置模型_文案不变() {
        AiException ex = assertThrows(AiException.class,
                () -> client("").generateText2Image("一只猫", null));
        assertEquals("AI_IMAGE_MODELS / AI_IMAGE_MODEL 均未配置", ex.getMessage());
    }

    @Test
    void imageModel未注入_回退自建仍可生图() {
        mode = "url";
        AiProperties p = new AiProperties();
        p.setBaseUrl(baseUrl()); // 根地址，回退路径自行归一化补 /v1
        p.setApiKey("k");
        p.setImageModel("m1");
        AiImageClient.GenResult r = new AiImageClient(p).generateText2Image("一只猫", null);
        assertEquals("https://example.com/a.png", r.url());
        assertEquals("m1", r.model());
    }

    @Test
    void wire请求_n与size透传_默认尺寸() throws Exception {
        mode = "url";
        client("m1").generateText2Image("一只猫", null);
        JsonNode body = JSON.readTree(bodies.get(0));
        assertEquals("m1", body.path("model").asText());
        assertEquals(1, body.path("n").asInt());
        assertEquals("1024x1024", body.path("size").asText(), "size 为空回退 1024x1024");
        assertEquals("一只猫", body.path("prompt").asText());
    }
}
