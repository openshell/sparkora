package com.sparkora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 信源采集基座配置(10-05-source-crawl-base)。对应 .env: SOURCE_*。
 *
 * <p>总开关默认关闭(零回归);并发/同站上限是资源红线(父任务 design §3.3:无头浏览器 300-400MB、
 * swap 偏紧 → 全局串行、并发 ≤2)。Crawl4AI 通道地址由 {@link Crawl4aiProperties} 持有(B 只读)。
 */
@Data
@ConfigurationProperties(prefix = "sparkora.source")
public class SourceProperties {
    /** 采集总开关(默认关=零回归;关时不注册任何调度、不发起采集)。 */
    private boolean collectEnabled = false;
    /** 全局采集并发上限(红线,默认 2;实际采集串行,此值供编排约束)。 */
    private int maxConcurrency = 2;
    /** 同站每天上限(红线,默认 2;Crawl4AI 同 host 日限由 C 强制,HTTP 无硬日限)。 */
    private int perSiteDailyMax = 2;
}
