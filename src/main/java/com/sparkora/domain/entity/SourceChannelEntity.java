package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 信源栏目实体(10-05-source-crawl-base)。对应 sparkora_source_channel。
 *
 * <p>一个栏目 = 一个列表页/feed;列表选择器/详情选择器/日期/正文/表格/图片选择器集中存 {@code parseRules}(JSON),
 * 站点改版只改一条记录。{@code needCrawl4ai} 为 NULL 时继承源级默认。
 */
@Data
@TableName("sparkora_source_channel")
public class SourceChannelEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sourceId;
    private String name;            // 栏目名(乘联会「车市解读」)
    private String listUrl;         // 该栏目列表/feed 地址
    private String detailBaseUrl;   // 详情相对链接基址(相对图链同样按此解析)
    private String category;        // 官方新闻|销量数据|投诉榜|政策公示|行业资讯
    private String parseRules;      // JSON:列表/详情/日期/正文/表格/图片选择器
    private Boolean needCrawl4ai;   // NULL=继承源级
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
