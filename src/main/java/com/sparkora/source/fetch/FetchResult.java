package com.sparkora.source.fetch;

/**
 * 规范化抓取结果(10-05-crawl4ai-transport,父任务 design §2.2)。
 *
 * @param finalUrl  最终 URL(重定向后;未拿到时回填请求 URL)
 * @param status    HTTP 状态码;0 表示未得到响应(未配置/被拒/异常)
 * @param html      HTML 正文(wantHtml=true 时有值)
 * @param content   文本正文(默认 markdown)
 * @param latencyMs 耗时(毫秒)
 * @param error     失败原因(null=无错误);分类值:UNCONFIGURED/TIMEOUT/EMPTY/HTTP_<status>/ERROR/CONCURRENCY_LIMIT/PER_HOST_DAILY_LIMIT
 * @param limited   true=被并发/频控拒绝;调用方应降级跳过,不得无限重试
 */
public record FetchResult(String finalUrl, int status, String html, String content,
                          long latencyMs, String error, boolean limited) {

    /** 是否成功(无错误且 2xx)。 */
    public boolean ok() {
        return error == null && status >= 200 && status < 300;
    }

    /** 失败态(status=0,非限流)。 */
    public static FetchResult failure(String url, String error) {
        return new FetchResult(url, 0, null, null, 0L, error, false);
    }

    /** 限流态(被并发/频控拒绝,快速返回不排队)。 */
    public static FetchResult limited(String url, String error) {
        return new FetchResult(url, 0, null, null, 0L, error, true);
    }
}
