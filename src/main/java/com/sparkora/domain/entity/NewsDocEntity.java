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
    /** 非持久化:新闻标题(构建期填充,写 vector_store metadata.name 用,不落 sparkora_news_doc)。 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String newsTitle;
    /** 非持久化:发布日期(构建期填充,写 vector_store metadata.publishDate 用,不落 sparkora_news_doc;10-05 E)。 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private LocalDateTime publishDate;
    /** 非持久化:NEWS 域内来源类型(构建期填充,写 store metadata.sourceType 用;10-05 E)。 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String sourceType;
    /** 非持久化:NEWS 域内来源分类(构建期填充,写 store metadata.category 用;10-05 E)。 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String category;
    /** 非持久化:来源内容原文 URL(构建期填充,写 store metadata.url 用;10-09 M,供 F-R3 跨源同 URL 去重)。 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String url;
    /** 非持久化:信源权威档 official|industry|media|ugc(构建期填充,写 store metadata.authorityTier 用;10-09 M,供 F-R4 分档)。 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String authorityTier;
}
