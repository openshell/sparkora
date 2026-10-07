package com.sparkora.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 信源内容查询行(10-05-source-crawl-base,GET /api/source-contents)。
 * 列表不返回正文大字段;详情接口回填 content。source=byd 条目带 U 条件渲染所需字段。
 */
@Data
public class SourceContentDTO {
    private Long id;
    private Long sourceId;
    private Long channelId;
    private String title;
    private String url;
    private LocalDateTime publishDate;
    private String category;
    private String source;        // source 标识(通用信源="source";BYD 存量="byd-news")
    private Long chunkCount;      // 切块数(E 消费/展示)
    private String content;       // 详情接口返回
}
