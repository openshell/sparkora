package com.sparkora.car.client;

import com.sparkora.car.dto.GoodsInfoDto;
import com.sparkora.car.dto.GoodsParamsDto;
import com.sparkora.config.CarProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 官网客户端加固单测(10-21):业务 code/data 校验、catalog 失败不返回空目录、
 * 429 重试、HMAC header 与签名使用同一时间戳。
 */
class BydCmsClientTest {

    HttpServer server;
    CarProperties props;
    BydCmsClient client;
    String base;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/info", ex -> json(ex, 200, "{\"code\":0,\"data\":{\"goodsinfoList\":{\"items\":[]}}}"));
        server.createContext("/info-bad", ex -> json(ex, 200, "{\"code\":1,\"data\":{}}"));
        server.createContext("/params", ex -> json(ex, 200, "{\"code\":200,\"data\":null}"));
        server.createContext("/params-ok", ex -> json(ex, 200,
                "{\"code\":200,\"data\":{\"configs\":[{\"name\":\"尺寸\",\"value\":[]}]}}"));
        server.createContext("/attrs", ex -> json(ex, 200, "{\"code\":0,\"data\":[]}"));
        server.createContext("/catalog-bad", ex -> json(ex, 200, "{\"code\":0,\"data\":{}}"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        props = new CarProperties();
        props.setGoodsInfoUrl(base + "/info");
        props.setGoodsParamsUrl(base + "/params");
        props.setGoodsAttrListUrl(base + "/attrs");
        props.setGoodsListUrl(base + "/catalog-bad");
        props.setRetryMax(2);
        props.setRetryBackoffMs(1);
        props.setMinIntervalMs(0);
        client = new BydCmsClient(props);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static void json(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (var os = ex.getResponseBody()) { os.write(bytes); }
        ex.close();
    }

    @Test
    void 业务code非成功_抛SourceUnavailable而不是空DTO() {
        CarProperties p = new CarProperties();
        p.setGoodsInfoUrl(base + "/info-bad");
        p.setMinIntervalMs(0);
        BydCmsClient c = new BydCmsClient(p);

        SourceUnavailableException e = assertThrows(SourceUnavailableException.class, () -> c.goodsInfo("1"));
        assertTrue(e.userMessage().contains("官网响应异常"), e.userMessage());
    }

    @Test
    void data为null_抛错而不是返回空结构() {
        SourceUnavailableException e = assertThrows(SourceUnavailableException.class, () -> client.goodsParams("1"));
        assertTrue(e.userMessage().contains("数据缺失"), e.userMessage());
    }

    @Test
    void 空items是有效数据_交由上层判EMPTY() {
        GoodsInfoDto dto = client.goodsInfo("1");
        assertNotNull(dto);
        assertTrue(dto.getGoodsinfoList() == null || dto.getGoodsinfoList().getItems().isEmpty());
    }

    @Test
    void catalog_data非数组_抛错不冒充空目录() {
        assertThrows(SourceUnavailableException.class, () -> client.goodsList());
    }

    @Test
    void 限流429_有限重试后成功() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/retry", ex -> {
            if (calls.incrementAndGet() == 1) {
                ex.getResponseHeaders().add("Retry-After", "0");
                ex.sendResponseHeaders(429, -1);
                ex.close();
            } else {
                json(ex, 200, "{\"code\":0,\"data\":[]}");
            }
        });
        CarProperties p = new CarProperties();
        p.setGoodsAttrListUrl(base + "/retry");
        p.setRetryMax(2);
        p.setRetryBackoffMs(1);
        p.setMinIntervalMs(0);
        BydCmsClient c = new BydCmsClient(p);

        List<?> data = c.goodsAttrList("1");
        assertEquals(0, data.size());
        assertEquals(2, calls.get());
    }

    @Test
    void HMAC的header时间戳与签名使用同一值() throws Exception {
        server.createContext("/signed", ex -> {
            String ts = ex.getRequestHeaders().getFirst("X-HMAC-TIMESTAMP");
            String sig = ex.getRequestHeaders().getFirst("X-HMAC-SIGNATURE");
            String expect;
            try {
                expect = hmac(props.getHmacSignKey(), props.getHmacSecretKey(), ts);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            json(ex, 200, expect.equals(sig)
                    ? "{\"code\":200,\"data\":{\"configs\":[]}}"
                    : "{\"code\":401,\"data\":null}");
            ex.close();
        });
        CarProperties p = new CarProperties();
        p.setGoodsParamsUrl(base + "/signed");
        p.setMinIntervalMs(0);
        p.setRetryMax(0);
        BydCmsClient c = new BydCmsClient(p);

        GoodsParamsDto.ParamsData data = c.goodsParams("1");
        assertNotNull(data);
        assertTrue(data.getConfigs() != null);
    }

    private static String hmac(String signKey, String secretKey, String ts) throws Exception {
        String signing = signKey + "\n" + secretKey + "\n" + ts;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(signing.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
