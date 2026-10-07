package com.sparkora.source.client;

import com.sparkora.domain.entity.SourceChannelEntity;

import java.util.List;

/**
 * 采集客户端抽象(10-05-source-crawl-base)。列表/详情两阶段。
 *
 * <p><b>按栏目(channel)驱动</b>:一个源遍历其 enabled channels,每 channel 用各自 {@code parse_rules}。
 * 实现只负责「HTML → 结构化条目/正文」,不发起抓取(抓取经 C 的 {@code FetchTransport} 由编排层完成),
 * 便于纯解析单测。
 */
public interface SourceClient {

    /** 支持的源类型:RSS | SITE(对应 {@code sparkora_source.type})。 */
    String type();

    /** 解析栏目列表页 HTML → 条目列表(解析失败返回空列表,不抛)。 */
    List<SourceItem> list(String html, SourceChannelEntity channel);

    /** 解析条目详情页 HTML → 正文内容(解析失败降级空内容,不抛)。 */
    SourceContent detail(String html, SourceChannelEntity channel, SourceItem item);
}
