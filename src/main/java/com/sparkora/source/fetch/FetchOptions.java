package com.sparkora.source.fetch;

import java.util.Map;

/**
 * 抓取选项(10-05-crawl4ai-transport,父任务 design §2.2)。
 *
 * @param httpMethod HTTP 方法(默认 GET;HTTP 通道用)
 * @param headers    附加请求头(可空;HTTP 通道用)
 * @param wantHtml   true=要 HTML(HTTP 返回 body;Crawl4AI 改调 {@code POST /html});false=要正文
 * @param timeoutMs  读超时(毫秒;≤0 时用通道默认值)
 */
public record FetchOptions(String httpMethod, Map<String, String> headers, boolean wantHtml, long timeoutMs) {

    /** 默认选项:GET、无附加头、要正文、通道默认超时。 */
    public static FetchOptions defaults() {
        return new FetchOptions("GET", Map.of(), false, 0L);
    }
}
