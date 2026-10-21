package com.sparkora.car.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 车型图片下载安全边界(10-21-car-sync-safety R6):HTTPS/白名单/私网拦截/重定向复检/
 * 大小与像素上限/Content-Type/严格魔数。
 */
class CarImageDownloaderTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    HttpServer server;
    String base;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/ok.png", ex -> respond(ex, 200, "image/png", PNG));
        server.createContext("/html", ex -> respond(ex, 200, "text/html", "<html>not an image</html>".getBytes()));
        server.createContext("/magic", ex -> respond(ex, 200, "image/png", "not an image".getBytes()));
        server.createContext("/big", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.getResponseHeaders().add("Content-Length", "99999999");
            ex.sendResponseHeaders(200, 0);
            ex.close();
        });
        server.createContext("/redir", ex -> {
            ex.getResponseHeaders().add("Location", "http://example.com/x.png");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String type, byte[] body) throws java.io.IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, body.length);
        try (var os = ex.getResponseBody()) { os.write(body); }
        ex.close();
    }

    @Test
    void 默认下载器_拒绝HTTP与私网() {
        CarImageDownloader d = new CarImageDownloader();
        CarImageDownloader.ImageRejectedException e = assertThrows(
                CarImageDownloader.ImageRejectedException.class, () -> d.download(base + "/ok.png"));
        assertTrue(e.getMessage().contains("HTTPS"), e.getMessage());
    }

    @Test
    void 允许HTTP但拒绝私网地址() {
        CarImageDownloader d = new CarImageDownloader(
                CarImageDownloader.DEFAULT_MAX_BYTES, CarImageDownloader.DEFAULT_MAX_PIXELS,
                Set.of("127.0.0.1", "localhost"), true, false);
        CarImageDownloader.ImageRejectedException e = assertThrows(
                CarImageDownloader.ImageRejectedException.class, () -> d.download(base + "/ok.png"));
        assertTrue(e.getMessage().contains("内网"), e.getMessage());
    }

    @Test
    void 合法图片_下载并严格嗅探扩展名() {
        CarImageDownloader.DownloadedImage img = CarImageDownloader.permissiveForTest().download(base + "/ok.png");
        assertEquals("png", img.ext());
        assertTrue(img.bytes().length > 0);
    }

    @Test
    void HTML错误页_被拒绝() {
        CarImageDownloader.ImageRejectedException e = assertThrows(
                CarImageDownloader.ImageRejectedException.class,
                () -> CarImageDownloader.permissiveForTest().download(base + "/html"));
        assertTrue(e.getMessage().contains("图片格式错误"), e.getMessage());
    }

    @Test
    void 未知魔数_被拒绝而不是兜底png() {
        CarImageDownloader.ImageRejectedException e = assertThrows(
                CarImageDownloader.ImageRejectedException.class,
                () -> CarImageDownloader.permissiveForTest().download(base + "/magic"));
        assertTrue(e.getMessage().contains("未知魔数"), e.getMessage());
    }

    @Test
    void 超过字节上限_被拒绝() {
        CarImageDownloader d = CarImageDownloader.forTest(100, CarImageDownloader.DEFAULT_MAX_PIXELS);
        CarImageDownloader.ImageRejectedException e = assertThrows(
                CarImageDownloader.ImageRejectedException.class, () -> d.download(base + "/big"));
        assertTrue(e.getMessage().contains("图片过大"), e.getMessage());
    }

    @Test
    void 保留地址段被拦截_cgnat与benchmark() throws Exception {
        assertTrue(CarImageDownloader.isBlocked(InetAddress.getByName("100.64.0.1")), "CGNAT 100.64/10");
        assertTrue(CarImageDownloader.isBlocked(InetAddress.getByName("198.18.0.1")), "198.18/15");
        assertTrue(CarImageDownloader.isBlocked(InetAddress.getByName("0.0.0.1")), "0.0.0.0/8");
        assertTrue(CarImageDownloader.isBlocked(InetAddress.getByName("10.0.0.1")), "私网");
        assertTrue(CarImageDownloader.isBlocked(InetAddress.getByName("::ffff:10.0.0.1")), "IPv4-mapped 私网");
        assertTrue(!CarImageDownloader.isBlocked(InetAddress.getByName("8.8.8.8")), "公网不应被拦");
    }

    /** JDK 无 webp reader:必须能从头部解析宽高,否则「允许 webp」会被尺寸校验全量拒绝。 */
    @Test
    void webp尺寸解析_vp8x与vp8l() {
        byte[] vp8x = new byte[30];
        writeAscii(vp8x, 0, "RIFF");
        writeAscii(vp8x, 8, "WEBP");
        writeAscii(vp8x, 12, "VP8X");
        vp8x[24] = 99;    // width-1 = 99 → 100
        vp8x[27] = 120;   // height-1 = 120 → 121

        assertArrayEquals(new int[]{100, 121}, CarImageDownloader.webpSize(vp8x));

        byte[] vp8l = new byte[30];
        writeAscii(vp8l, 0, "RIFF");
        writeAscii(vp8l, 8, "WEBP");
        writeAscii(vp8l, 12, "VP8L");
        vp8l[20] = 0x2F;
        int bits = 99 | (199 << 14);   // width-1 / height-1
        vp8l[21] = (byte) bits;
        vp8l[22] = (byte) (bits >> 8);
        vp8l[23] = (byte) (bits >> 16);
        vp8l[24] = (byte) (bits >> 24);

        assertArrayEquals(new int[]{100, 200}, CarImageDownloader.webpSize(vp8l));
    }

    private static void writeAscii(byte[] target, int offset, String s) {
        for (int i = 0; i < s.length(); i++) target[offset + i] = (byte) s.charAt(i);
    }

    @Test
    void 重定向后重新校验域名_非白名单被拒绝() {
        CarImageDownloader.ImageRejectedException e = assertThrows(
                CarImageDownloader.ImageRejectedException.class,
                () -> CarImageDownloader.permissiveForTest().download(base + "/redir"));
        assertTrue(e.getMessage().contains("白名单"), e.getMessage());
    }
}
