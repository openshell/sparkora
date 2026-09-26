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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图生图多参考图契约单测（09-26 img2img-multi-ref，R1/AC-1）：以本地 stub HTTP 服务捕获
 * `/v1/images/edits` 的 multipart body，断言**保序重复 image part**（数量与顺序），
 * 以及空集合/长度不匹配**在模型轮询前 fail-fast** 抛 AiException。
 */
class AiImageClientMultiRefTest {

    private HttpServer server;
    private final AtomicReference<String> captured = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/edits", ex -> {
            captured.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = "{\"data\":[{\"url\":\"https://example.com/a.png\"}]}".getBytes(StandardCharsets.UTF_8);
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

    private AiImageClient client() {
        AiProperties p = new AiProperties();
        p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        p.setApiKey("k");
        p.setImageModel("gpt-image-2");
        return new AiImageClient(p);
    }

    @Test
    void 多图_保序重复image_part() {
        AiImageClient.GenResult r = client().generateImage2Image("p",
                List.of(new byte[]{1, 2, 3}, new byte[]{4, 5}, new byte[]{6, 7, 8, 9}),
                List.of("r1.png", "r2.webp", "r3.jpg"), null);

        assertEquals("https://example.com/a.png", r.url());
        String body = captured.get();
        long parts = body.split("name=\"image\"", -1).length - 1;
        assertEquals(3, parts, "应有 3 个 image part（重复 part 保序）");
        int i1 = body.indexOf("filename=\"r1.png\"");
        int i2 = body.indexOf("filename=\"r2.webp\"");
        int i3 = body.indexOf("filename=\"r3.jpg\"");
        assertTrue(i1 >= 0 && i2 > i1 && i3 > i2, "image part 顺序须与入参一致: " + body);
    }

    @Test
    void 空集合_模型轮询前抛异常() {
        AiException ex = assertThrows(AiException.class,
                () -> client().generateImage2Image("p", List.of(), List.of(), null));
        assertTrue(ex.getMessage().contains("参考图"), ex.getMessage());
        assertTrue(captured.get() == null, "空集合须 fail-fast，不得发出请求");
    }

    @Test
    void 字节与文件名长度不匹配_抛异常() {
        AiException ex = assertThrows(AiException.class, () -> client().generateImage2Image("p",
                List.of(new byte[]{1}), List.of("a.png", "b.png"), null));
        assertTrue(ex.getMessage().contains("不匹配"), ex.getMessage());
        assertTrue(captured.get() == null, "长度不匹配须 fail-fast，不得发出请求");
    }

    @Test
    void 文件名为空回退referencePng() {
        client().generateImage2Image("p", List.of(new byte[]{1, 2, 3}), List.of("  "), null);
        assertTrue(captured.get().contains("filename=\"reference.png\""), captured.get());
    }
}
