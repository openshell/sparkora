package com.sparkora.news.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiException;
import com.sparkora.config.NewsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 比亚迪官方新闻采集客户端(C2)。封装两个公开接口(无鉴权):
 *  - 列表:POST {listUrl}(/es/search),body 固定 brandName/siteName/type/sortField;
 *  - 详情:GET {detailBaseUrl}{url},SSR HTML 由 NewsContentParser 抽取正文。
 *
 * 实测:列表响应 {code:0,data:{records:[{id,title,url,imageUrl,date,tags,tagNames}],total,size,current,pages}},total=167。
 */
@Slf4j
@Component
public class BydNewsClient {

    private final NewsProperties props;
    private final RestClient rest;
    private final ObjectMapper mapper = new ObjectMapper();

    public BydNewsClient(NewsProperties props) {
        this.props = props;
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofMillis(props.getTimeoutMs()));
        this.rest = RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    /** 抓取一页新闻列表,返回 data 节点(含 records/total/pages)。 */
    public JsonNode searchPage(int page, int size) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brandName", "byd");
        body.put("siteName", "cn");
        body.put("type", "news");
        body.put("page", page);
        body.put("size", size);
        body.put("sortField", "date");
        body.put("year", "");
        try {
            String resp = rest.post()
                    .uri(props.getListUrl())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.REFERER, "https://www.byd.com/")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return mapper.readTree(resp).path("data");
        } catch (Exception e) {
            throw new AiException("比亚迪新闻列表采集失败: page=" + page + " -> " + e.getMessage(), e);
        }
    }

    /** 抓取详情页 SSR HTML(相对 url 拼站点前缀;绝对 url 原样用)。 */
    public String fetchDetailHtml(String url) {
        String full = detailUrl(url);
        try {
            return rest.get().uri(full)
                    .header(HttpHeaders.REFERER, "https://www.byd.com/")
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new AiException("比亚迪新闻详情采集失败: " + full + " -> " + e.getMessage(), e);
        }
    }

    /** 相对路径补全为详情页绝对 URL;已是 http(s) 开头则原样返回。 */
    private String detailUrl(String url) {
        if (url == null) return props.getDetailBaseUrl();
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        String base = props.getDetailBaseUrl() == null ? "" : props.getDetailBaseUrl().replaceAll("/+$", "");
        return base + (url.startsWith("/") ? url : "/" + url);
    }
}
