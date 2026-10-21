package com.sparkora.car.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.car.dto.GoodsAttrListDto;
import com.sparkora.car.dto.GoodsInfoDto;
import com.sparkora.car.dto.GoodsParamsDto;
import com.sparkora.config.CarProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 比亚迪官网采集客户端。封装 4 个公开 JSON API。
 *
 * <p>10-21-car-sync-safety 加固:
 * <ul>
 *   <li>按端点校验业务 {@code code}(goodsInfo/goodsAttrList=0,goodsParams=200)与 {@code data} 结构;
 *       {@code data} 缺失/类型不符抛 {@link SourceUnavailableException},不再当成空数据返回;</li>
 *   <li>{@code catalog} 业务/HTTP 失败直接抛错,调度器记录失败而不是创建空目录任务;</li>
 *   <li>仅对 429/5xx/网络异常做有限重试(指数退避 + jitter,尊重 Retry-After),调用间限速;</li>
 *   <li>HMAC 的 header 与签名共用同一个时间戳(跨秒不一致会验签失败);</li>
 *   <li>异常消息只含端点名,完整 URL/堆栈仅进日志。</li>
 * </ul>
 */
@Slf4j
@Component
public class BydCmsClient {

    private final CarProperties props;
    private final RestClient rest;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 最近一次调用时刻(毫秒),用于简单限速(单实例串行节流,避免对官网突发)。 */
    private long lastCallAt;

    public BydCmsClient(CarProperties props) {
        this.props = props;
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofMillis(props.getTimeoutMs()));
        this.rest = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings))
                .build();
    }

    /** 车型目录(全车型列表)。返回 data 数组;业务失败/结构不符抛 {@link SourceUnavailableException}。 */
    public JsonNode goodsList() {
        JsonNode data = request("/goodsListForSearch", props.getGoodsListUrl(), false, 0);
        if (!data.isArray()) {
            throw new SourceUnavailableException("官网目录响应异常", "catalog data 不是数组");
        }
        return data;
    }

    /** 车型基础信息。data 缺失抛 {@link SourceUnavailableException}(空 items 由上层判 EMPTY)。 */
    public GoodsInfoDto goodsInfo(String goodsId) {
        JsonNode data = request("/getGoodsInfoById", withGoodsId(props.getGoodsInfoUrl(), goodsId), false, 0);
        if (!data.isObject()) {
            throw new SourceUnavailableException("官网车型信息响应异常", "goodsInfo data 不是对象, goodsId=" + goodsId);
        }
        return mapper.convertValue(data, GoodsInfoDto.class);
    }

    /** 完整参数表(核心)。返回 data 内容(含 configs)。该接口需 HMAC 签名鉴权。 */
    public GoodsParamsDto.ParamsData goodsParams(String goodsId) {
        JsonNode data = request("/goodsParams", withGoodsId(props.getGoodsParamsUrl(), goodsId), true, 200);
        if (!data.isObject()) {
            throw new SourceUnavailableException("官网参数响应异常", "goodsParams data 不是对象, goodsId=" + goodsId);
        }
        return mapper.convertValue(data, GoodsParamsDto.ParamsData.class);
    }

    /** 版本/外观/内饰/价格。data 必须是数组,否则抛错(空数组由上层判 INCOMPLETE)。 */
    public List<GoodsAttrListDto.Version> goodsAttrList(String goodsId) {
        JsonNode data = request("/getGoodsAttrList", withGoodsId(props.getGoodsAttrListUrl(), goodsId), false, 0);
        if (!data.isArray()) {
            throw new SourceUnavailableException("官网版本响应异常", "goodsAttrList data 不是数组, goodsId=" + goodsId);
        }
        return mapper.convertValue(data,
                mapper.getTypeFactory().constructCollectionType(List.class, GoodsAttrListDto.Version.class));
    }

    private static String withGoodsId(String base, String goodsId) {
        return base + (base.contains("?") ? "&" : "?") + "goodsId=" + goodsId;
    }

    /**
     * 统一请求 + 业务校验 + 有限重试。
     *
     * @param endpoint     端点短名(只用于日志/用户文案,不含完整 URL)
     * @param expectedCode 业务成功码(goodsParams=200,其余=0)
     */
    private JsonNode request(String endpoint, String url, boolean signed, int expectedCode) {
        int maxRetries = Math.max(0, props.getRetryMax());
        long backoff = Math.max(0, props.getRetryBackoffMs());
        for (int attempt = 0; ; attempt++) {
            pace();
            try {
                JsonNode node = signed ? executeSigned(url) : execute(url);
                return requireData(node, endpoint, expectedCode);
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (attempt >= maxRetries) {
                    throw unavailable(endpoint, "官网限流", e, true);
                }
                sleepBackoff(attempt, backoff, retryAfterMs(e));
            } catch (HttpServerErrorException e) {
                if (attempt >= maxRetries) {
                    throw unavailable(endpoint, "官网服务异常(HTTP " + e.getStatusCode().value() + ")", e, true);
                }
                sleepBackoff(attempt, backoff, retryAfterMs(e));
            } catch (ResourceAccessException e) {
                if (attempt >= maxRetries) {
                    throw unavailable(endpoint, "网络超时或连接失败", e, true);
                }
                sleepBackoff(attempt, backoff, -1);
            } catch (SourceUnavailableException e) {
                throw e;
            } catch (Exception e) {
                // JSON 解析/其它非 HTTP 错误:不重试,分类为不可用
                throw unavailable(endpoint, "官网响应解析失败", e, false);
            }
        }
    }

    private JsonNode execute(String url) throws Exception {
        String resp = rest.get().uri(url).retrieve().body(String.class);
        return mapper.readTree(resp);
    }

    /** 带 HMAC 签名头的 GET。header 与签名共用一个时间戳。 */
    private JsonNode executeSigned(String url) throws Exception {
        long ts = System.currentTimeMillis() / 1000;
        String signature = hmacSignature(ts);
        String resp = rest.get().uri(url)
                .header(HttpHeaders.REFERER, "https://www.byd.com/")
                .header("X-HMAC-SIGNATURE", signature)
                .header("X-HMAC-TIMESTAMP", String.valueOf(ts))
                .header("X-HMAC-SIGNKEY", props.getHmacSignKey())
                .retrieve()
                .body(String.class);
        return mapper.readTree(resp);
    }

    /** 校验业务 code 与 data;失败抛 {@link SourceUnavailableException},绝不返回空对象冒充成功。 */
    private JsonNode requireData(JsonNode node, String endpoint, int expectedCode) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            throw new SourceUnavailableException("官网响应为空", endpoint + " 响应体为空");
        }
        JsonNode code = node.get("code");
        if (code == null || code.isNull() || code.asInt(Integer.MIN_VALUE) != expectedCode) {
            String business = code == null ? "missing" : code.asText();
            throw new SourceUnavailableException("官网响应异常",
                    endpoint + " 业务 code 非成功: " + business + " (期望 " + expectedCode + ")");
        }
        JsonNode data = node.get("data");
        if (data == null || data.isNull() || data.isMissingNode()) {
            throw new SourceUnavailableException("官网响应数据缺失", endpoint + " data 为空");
        }
        return data;
    }

    private SourceUnavailableException unavailable(String endpoint, String userMessage, Exception cause, boolean transientFailure) {
        // 完整异常仅进日志;对外只给分类短文案 + 端点名
        log.warn("比亚迪官网采集失败 endpoint={} reason={}: {}", endpoint, userMessage, cause == null ? "-" : cause.toString());
        return new SourceUnavailableException(userMessage, endpoint + " 采集失败: " + (cause == null ? userMessage : cause.getClass().getSimpleName()),
                cause, transientFailure);
    }

    /** 简单限速:同一客户端两次调用至少间隔配置值(默认 200ms)。 */
    private synchronized void pace() {
        long min = Math.max(0, props.getMinIntervalMs());
        long now = System.currentTimeMillis();
        long wait = lastCallAt + min - now;
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastCallAt = System.currentTimeMillis();
    }

    private static void sleepBackoff(int attempt, long baseMs, long retryAfterMs) {
        long delay = retryAfterMs >= 0 ? retryAfterMs
                : (long) (baseMs * Math.pow(2, attempt)) + ThreadLocalRandom.current().nextLong(Math.max(1, baseMs / 2 + 1));
        if (delay <= 0) return;
        try {
            Thread.sleep(Math.min(delay, 30_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 解析 Retry-After(秒或 HTTP 日期,仅取秒);缺失返回 -1。 */
    private static long retryAfterMs(HttpStatusCodeException e) {
        String v = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        if (v == null || v.isBlank()) return -1;
        try {
            return Long.parseLong(v.trim()) * 1000L;
        } catch (NumberFormatException ignore) {
            return -1;
        }
    }

    /**
     * 计算 goodsParams 接口的 HMAC-SHA256 签名。
     * 算法(官网前端 JS 逆向):signingString = signKey + "\n" + secretKey + "\n" + timestamp,
     * 用 secretKey 做 HMAC-SHA256,输出 hex。时间戳由调用方固定,保证 header 与签名一致。
     */
    private String hmacSignature(long timestamp) {
        try {
            String signKey = props.getHmacSignKey();
            String secretKey = props.getHmacSecretKey();
            String signingString = signKey + "\n" + secretKey + "\n" + timestamp;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(signingString.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new SourceUnavailableException("签名计算失败", "HMAC 签名计算失败: " + e.getMessage(), e, false);
        }
    }
}
