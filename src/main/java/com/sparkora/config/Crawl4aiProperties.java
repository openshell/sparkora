package com.sparkora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Crawl4AI 抓取通道配置(10-05-crawl4ai-transport)。对应 .env: CRAWL4AI_*。
 *
 * <p>抓取通道用于 B 级信源(指纹 WAF/SPA)的无头浏览器抓取;资源红线(并发 ≤2、同 host ≤2/天)
 * 来自调研报告「本机 swap 偏紧、无头浏览器 300-400MB」,默认值不得放宽。
 */
@Data
@ConfigurationProperties(prefix = "sparkora.crawl4ai")
public class Crawl4aiProperties {
    /** Crawl4AI 服务地址(默认空 = 未配置,通道降级跳过)。 */
    private String baseUrl = "";
    /** Bearer 令牌(Authorization: Bearer <key>)。 */
    private String apiKey = "";
    /** 全局并发上限(红线,默认 2)。 */
    private int maxConcurrency = 2;
    /** Crawl4AI 通道同 host 每本地日请求上限(红线,默认 2)。 */
    private int perHostDailyLimit = 2;
    /** Crawl4AI 读超时(毫秒;实测乘联会 /md 约 7s,默认 20s 留余量)。 */
    private long timeoutMs = 20000;
    /** HTTP 通道同 host 相邻请求最小间隔(毫秒;无日上限,默认 2000)。 */
    private long sourceHttpMinIntervalMs = 2000;
}
