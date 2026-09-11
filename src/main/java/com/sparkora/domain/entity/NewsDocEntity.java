package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 新闻切块实体。对应 sparkora_news_doc(C2 新闻知识域)。
 * 检索单元;chunk_text 首行固定「新闻：<title>（<publishDate>）」。
 * 注意:此处 newsId 是 sparkora_news.id 内部 BIGINT(区别于 NewsEntity.newsId 官方字符串 id)。
 */
@Data
@TableName("sparkora_news_doc")
public class NewsDocEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long newsId;           // 内部 id(FK sparkora_news.id)
    private Integer seq;           // 块序号(同新闻内连续)
    private String chunkType;      // NEWS_BODY(空正文兜底 NEWS_TITLE)
    private String chunkText;      // 切分后的文本块(首行标题锚点)
    private Integer tokenCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
