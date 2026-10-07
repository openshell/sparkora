package com.sparkora.source.fetch;

/**
 * 抓取通道抽象(10-05-crawl4ai-transport,父任务 design §2.2)。
 *
 * <p>职责:只负责「给 URL 拿 HTML/正文」,不知道信源是谁。两实现:
 * {@link HttpFetchTransport}(普通 GET)与 {@link Crawl4aiFetchTransport}(POST Crawl4AI,过指纹 WAF/渲染 SPA)。
 *
 * <p><b>契约</b>:{@link #fetch(String, FetchOptions)} <b>永不抛出</b>——失败经 {@link FetchResult#error()}
 * / {@link FetchResult#limited()} 表达,调用方据此降级而非重试。
 */
public interface FetchTransport {

    /** 通道类型:HTTP(普通 GET) / CRAWL4AI(无头浏览器)。 */
    enum Kind { HTTP, CRAWL4AI }

    /** 通道类型。 */
    Kind kind();

    /** 配置是否就绪(CRAWL4AI:baseUrl 非空;HTTP:恒 true)。不参与健康态判定。 */
    boolean configured();

    /**
     * 抓取 URL。失败/限流经 {@link FetchResult} 表达,绝不抛出。
     *
     * @param url  目标 URL
     * @param opts 选项(可空,实现取默认值)
     * @return 规范化抓取结果(失败时 status=0、error 非空、limited 表被并发/频控拒绝)
     */
    FetchResult fetch(String url, FetchOptions opts);
}
