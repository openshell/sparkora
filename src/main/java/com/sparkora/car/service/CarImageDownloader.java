package com.sparkora.car.service;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * 车型图片下载加固(10-21-car-sync-safety R6)。
 *
 * <p>安全边界:
 * <ul>
 *   <li>仅 HTTPS + 比亚迪/CDN 域名白名单(跟随重定向后重新校验,最多 3 跳);</li>
 *   <li>拒绝 loopback/私网/链路本地/组播地址(防 SSRF);</li>
 *   <li>字节上限 + 像素上限(流式读取,超限立即中止);</li>
 *   <li>Content-Type 必须 image/*;严格魔数校验(png/jpg/webp),未知格式直接失败(不兜底 png);</li>
 *   <li>尺寸用 {@link ImageReader} 读取,不做 {@code ImageIO.read} 全量解码(38MB/1920×43353 长图会 OOM)。</li>
 * </ul>
 *
 * <p>失败抛 {@link ImageRejectedException}(带分类短文案),调用方转任务 note,不写图库。
 */
@Slf4j
public class CarImageDownloader {

    /** 默认字节上限 10MB。 */
    public static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;
    /** 默认像素上限 50MP。 */
    public static final long DEFAULT_MAX_PIXELS = 50_000_000L;
    private static final int MAX_REDIRECTS = 3;

    /** 生产白名单:官网接口域 + 图片 CDN 域(按后缀匹配)。 */
    private static final Set<String> ALLOWED_HOST_SUFFIXES = Set.of(
            "byd.com", "bydstatic.com", "bydauto.com.cn", "bydoceanauto.com", "caiqz.cn");

    private final long maxBytes;
    private final long maxPixels;
    private final Set<String> allowedHostSuffixes;
    private final boolean allowHttp;
    private final boolean allowPrivateHosts;
    private final HttpClient http;

    public CarImageDownloader() {
        this(DEFAULT_MAX_BYTES, DEFAULT_MAX_PIXELS, ALLOWED_HOST_SUFFIXES, false, false);
    }

    /** 测试用:允许本地 HTTP/私网(仅单测注入,生产绝不用)。 */
    public static CarImageDownloader permissiveForTest() {
        return new CarImageDownloader(DEFAULT_MAX_BYTES, DEFAULT_MAX_PIXELS,
                Set.of("127.0.0.1", "localhost"), true, true);
    }

    /** 测试用:自定义上限。 */
    public static CarImageDownloader forTest(long maxBytes, long maxPixels) {
        return new CarImageDownloader(maxBytes, maxPixels,
                Set.of("127.0.0.1", "localhost"), true, true);
    }

    public CarImageDownloader(long maxBytes, long maxPixels, Set<String> allowedHostSuffixes,
                              boolean allowHttp, boolean allowPrivateHosts) {
        this.maxBytes = maxBytes;
        this.maxPixels = maxPixels;
        this.allowedHostSuffixes = allowedHostSuffixes;
        this.allowHttp = allowHttp;
        this.allowPrivateHosts = allowPrivateHosts;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)   // 手动跟随,逐跳复检白名单/私网
                .build();
    }

    /** 下载结果:图片字节 + 严格嗅探出的扩展名(png/jpg/webp)。 */
    public record DownloadedImage(byte[] bytes, String ext) {}

    /** 图片被安全策略拒绝(分类短文案供任务明细)。 */
    public static class ImageRejectedException extends RuntimeException {
        public ImageRejectedException(String message) {
            super(message);
        }

        public ImageRejectedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 下载并校验图片。
     *
     * @throws ImageRejectedException URL 非 HTTPS/非白名单/私网/超大/非图片/魔数未知/尺寸超限
     */
    public DownloadedImage download(String url) {
        URI uri = parse(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            validateTarget(uri);
            HttpResponse<InputStream> resp;
            try {
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(30))
                        .header("Accept", "image/*")
                        .GET().build();
                resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ImageRejectedException("图片下载中断", e);
            } catch (Exception e) {
                throw new ImageRejectedException("图片下载失败", e);
            }

            int status = resp.statusCode();
            if (status >= 300 && status < 400) {
                String location = resp.headers().firstValue("Location").orElse(null);
                closeQuietly(resp.body());
                if (location == null || location.isBlank()) throw new ImageRejectedException("图片重定向地址缺失");
                uri = uri.resolve(location);
                continue;   // 下一跳重新校验 scheme/host/私网
            }
            if (status != 200) {
                closeQuietly(resp.body());
                throw new ImageRejectedException("图片下载失败(HTTP " + status + ")");
            }
            String contentType = resp.headers().firstValue("Content-Type").orElse("");
            if (!contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                closeQuietly(resp.body());
                throw new ImageRejectedException("图片格式错误(非 image 内容)");
            }
            String declared = resp.headers().firstValue("Content-Length").orElse(null);
            if (declared != null) {
                try {
                    if (Long.parseLong(declared) > maxBytes) {
                        closeQuietly(resp.body());
                        throw new ImageRejectedException("图片过大(超过 " + (maxBytes / 1024 / 1024) + "MB)");
                    }
                } catch (NumberFormatException ignore) {
                    // 非法 Content-Length 交给流式上限兜底
                }
            }
            byte[] bytes = readBounded(resp.body());
            String ext = sniffExact(bytes);
            if (ext == null) throw new ImageRejectedException("图片格式错误(未知魔数)");
            checkDimensions(bytes, ext);
            return new DownloadedImage(bytes, ext);
        }
        throw new ImageRejectedException("图片重定向过多");
    }

    private URI parse(String url) {
        if (url == null || url.isBlank()) throw new ImageRejectedException("图片地址为空");
        try {
            return URI.create(url.trim());
        } catch (Exception e) {
            throw new ImageRejectedException("图片地址非法", e);
        }
    }

    /** scheme/host 白名单 + 私网地址拒绝(重定向每一跳都调用)。 */
    void validateTarget(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !(allowHttp && "http".equals(scheme))) {
            throw new ImageRejectedException("图片地址必须使用 HTTPS");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new ImageRejectedException("图片地址缺少主机名");
        String h = host.toLowerCase(Locale.ROOT);
        boolean allowed = allowedHostSuffixes.stream()
                .anyMatch(suffix -> h.equals(suffix) || h.endsWith("." + suffix));
        if (!allowed) throw new ImageRejectedException("图片域名不在白名单");
        if (allowPrivateHosts) return;
        try {
            for (InetAddress addr : InetAddress.getAllByName(h)) {
                if (isBlocked(addr)) throw new ImageRejectedException("图片地址指向内网,已拒绝");
            }
        } catch (ImageRejectedException e) {
            throw e;
        } catch (Exception e) {
            throw new ImageRejectedException("图片域名解析失败", e);
        }
    }

    /**
     * 私网/保留地址判定:Java 自带的 site-local 不含 CGNAT(100.64/10)、0.0.0.0/8、
     * 198.18/15 等保留段,这里显式补齐;IPv4-mapped IPv6(::ffff:a.b.c.d)按 IPv4 规则判定。
     */
    static boolean isBlocked(InetAddress addr) {
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress() || addr.isMulticastAddress()) return true;
        byte[] b = addr.getAddress();
        if (b.length == 4) return isBlockedIpv4(b, 0);
        if (b.length == 16) {
            int first = b[0] & 0xFF;
            // fc00::/7 (IPv6 unique local)
            if ((first & 0xFE) == 0xFC) return true;
            // ::ffff:a.b.c.d / ::a.b.c.d(IPv4-mapped/compatible)
            boolean prefixZero = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) { prefixZero = false; break; }
            }
            boolean mapped = (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
            boolean compatible = b[10] == 0 && b[11] == 0;
            if (prefixZero && (mapped || compatible)) return isBlockedIpv4(b, 12);
        }
        return false;
    }

    private static boolean isBlockedIpv4(byte[] b, int off) {
        int a0 = b[off] & 0xFF, a1 = b[off + 1] & 0xFF, a2 = b[off + 2] & 0xFF;
        if (a0 == 0) return true;                                   // 0.0.0.0/8
        if (a0 == 100 && (a1 & 0xC0) == 64) return true;            // 100.64/10 CGNAT
        if (a0 == 192 && a1 == 0 && a2 == 0) return true;           // 192.0.0.0/24
        if (a0 == 198 && (a1 & 0xFE) == 18) return true;            // 198.18/15 benchmarking
        return false;
    }

    /** 流式读取,超过字节上限立即中止(不把超大响应全量读入内存)。 */
    private byte[] readBounded(InputStream in) {
        try (in) {
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > maxBytes) {
                    throw new ImageRejectedException("图片过大(超过 " + (maxBytes / 1024 / 1024) + "MB)");
                }
                out.write(buf, 0, n);
            }
            if (out.size() == 0) throw new ImageRejectedException("图片内容为空");
            return out.toByteArray();
        } catch (ImageRejectedException e) {
            throw e;
        } catch (Exception e) {
            throw new ImageRejectedException("图片下载失败", e);
        }
    }

    /** 严格魔数嗅探;未知返回 null(调用方必须失败,禁止默认 png)。 */
    static String sniffExact(byte[] b) {
        if (b == null || b.length < 4) return null;
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpg";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "webp";
        return null;
    }

    /**
     * 只读尺寸,避免全量解码。
     * png/jpg 走 {@link ImageReader};webp 在 JDK 无内置 reader(项目未引 webp-imageio),
     * 直接解析 VP8/VP8L/VP8X 头拿宽高,避免「魔数允许 webp 但尺寸校验必失败」。
     */
    private void checkDimensions(byte[] bytes, String ext) {
        if ("webp".equals(ext)) {
            int[] wh = webpSize(bytes);
            if (wh == null) throw new ImageRejectedException("图片格式错误(无法解析)");
            checkPixelLimit(wh[0], wh[1]);
            return;
        }
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) throw new ImageRejectedException("图片格式错误(无法解析)");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) throw new ImageRejectedException("图片格式错误(无法解析)");
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                checkPixelLimit(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (ImageRejectedException e) {
            throw e;
        } catch (Exception e) {
            throw new ImageRejectedException("图片格式错误(无法解析)", e);
        }
    }

    private void checkPixelLimit(long w, long h) {
        if (w <= 0 || h <= 0) throw new ImageRejectedException("图片尺寸非法");
        if (w * h > maxPixels) {
            throw new ImageRejectedException("图片像素过大(超过 " + (maxPixels / 1_000_000) + "MP)");
        }
    }

    /** 解析 WebP 宽高(VP8X / VP8 lossy / VP8L lossless);无法解析返回 null。 */
    static int[] webpSize(byte[] b) {
        if (b == null || b.length < 30) return null;
        String fourcc = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        switch (fourcc) {
            case "VP8X" -> {
                int w = 1 + ((b[24] & 0xFF) | ((b[25] & 0xFF) << 8) | ((b[26] & 0xFF) << 16));
                int h = 1 + ((b[27] & 0xFF) | ((b[28] & 0xFF) << 8) | ((b[29] & 0xFF) << 16));
                return new int[]{w, h};
            }
            case "VP8 " -> {
                if ((b[23] & 0xFF) != 0x9D || b[24] != 0x01 || b[25] != 0x2A) return null;
                int w = ((b[26] & 0xFF) | ((b[27] & 0xFF) << 8)) & 0x3FFF;
                int h = ((b[28] & 0xFF) | ((b[29] & 0xFF) << 8)) & 0x3FFF;
                return new int[]{w, h};
            }
            case "VP8L" -> {
                if (b.length < 25 || (b[20] & 0xFF) != 0x2F) return null;
                int bits = (b[21] & 0xFF) | ((b[22] & 0xFF) << 8) | ((b[23] & 0xFF) << 16) | ((b[24] & 0xFF) << 24);
                return new int[]{(bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1};
            }
            default -> {
                return null;
            }
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try { in.close(); } catch (Exception ignore) {}
    }
}
