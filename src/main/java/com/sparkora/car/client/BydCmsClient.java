package com.sparkora.car.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.car.dto.GoodsAttrListDto;
import com.sparkora.car.dto.GoodsInfoDto;
import com.sparkora.car.dto.GoodsParamsDto;
import com.sparkora.config.CarProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 比亚迪官网采集客户端。封装 4 个公开 JSON API(无鉴权,直接 GET)。
 *
 * 接口(已用 Playwright 实测 goodsId=156 大唐EV / 10051 海狮06):
 *  - goodsListForSearch:全车型目录
 *  - getGoodsInfoById:车型基础信息(名称/价格/卖点/图片/权益)
 *  - goodsParams:完整参数表(核心,分组结构)
 *  - getGoodsAttrListForCompareByGoodsId:版本/外观/内饰/价格
 *
 * 注意:goodsParams 的 code 为 200(其余为 0),统一按 data 字段解析,不校验 code。
 */
@Slf4j
@Component
public class BydCmsClient {

    private final CarProperties props;
    private final RestClient rest;
    private final ObjectMapper mapper = new ObjectMapper();

    public BydCmsClient(CarProperties props) {
        this.props = props;
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofMillis(props.getTimeoutMs()));
        this.rest = RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    /** 车型目录(全车型列表)。返回 data 数组的原始 JSON,由调用方解析。 */
    public JsonNode goodsList() {
        return getJson(props.getGoodsListUrl());
    }

    /** 车型基础信息。 */
    public GoodsInfoDto goodsInfo(String goodsId) {
        JsonNode node = getJson(props.getGoodsInfoUrl() + "?goodsId=" + goodsId);
        return mapper.convertValue(node.path("data"), GoodsInfoDto.class);
    }

    /** 完整参数表(核心)。返回 data 内容(含 configs)。该接口需 HMAC 签名鉴权。 */
    public GoodsParamsDto.ParamsData goodsParams(String goodsId) {
        JsonNode node = getJsonSigned(props.getGoodsParamsUrl() + "?goodsId=" + goodsId);
        return mapper.convertValue(node.path("data"), GoodsParamsDto.ParamsData.class);
    }

    /** 版本/外观/内饰/价格。 */
    public List<GoodsAttrListDto.Version> goodsAttrList(String goodsId) {
        JsonNode node = getJson(props.getGoodsAttrListUrl() + "?goodsId=" + goodsId);
        return mapper.convertValue(node.path("data"),
                mapper.getTypeFactory().constructCollectionType(List.class, GoodsAttrListDto.Version.class));
    }

    private JsonNode getJson(String url) {
        try {
            String resp = rest.get().uri(url).retrieve().body(String.class);
            return mapper.readTree(resp);
        } catch (Exception e) {
            throw new com.sparkora.ai.AiException("比亚迪官网采集失败: " + url + " -> " + e.getMessage(), e);
        }
    }

    /** 带 HMAC 签名头的 GET(用于 goodsParams 等需验签接口)。 */
    private JsonNode getJsonSigned(String url) {
        try {
            String resp = rest.get().uri(url)
                    .header(HttpHeaders.REFERER, "https://www.byd.com/")
                    .header("X-HMAC-SIGNATURE", hmacSignature())
                    .header("X-HMAC-TIMESTAMP", String.valueOf(System.currentTimeMillis() / 1000))
                    .header("X-HMAC-SIGNKEY", props.getHmacSignKey())
                    .retrieve()
                    .body(String.class);
            return mapper.readTree(resp);
        } catch (Exception e) {
            throw new com.sparkora.ai.AiException("比亚迪官网采集失败(需签名): " + url + " -> " + e.getMessage(), e);
        }
    }

    /**
     * 计算 goodsParams 接口的 HMAC-SHA256 签名。
     * 算法(官网前端 JS 逆向):signingString = signKey + "\n" + secretKey + "\n" + timestamp,
     * 用 secretKey 做 HMAC-SHA256,输出 hex。
     */
    private String hmacSignature() {
        try {
            String signKey = props.getHmacSignKey();
            String secretKey = props.getHmacSecretKey();
            long ts = System.currentTimeMillis() / 1000;
            String signingString = signKey + "\n" + secretKey + "\n" + ts;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(signingString.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new com.sparkora.ai.AiException("HMAC 签名计算失败: " + e.getMessage(), e);
        }
    }
}
