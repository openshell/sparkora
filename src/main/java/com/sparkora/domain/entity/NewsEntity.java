package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 新闻主表实体。对应 sparkora_news(C2 新闻知识域)。
 * news_id 是官方字符串 id(业务唯一键,幂等 upsert 依据);url 为官方相对路径。
 */
@Data
@TableName("sparkora_news")
public class NewsEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String newsId;         // 官方字符串 id,如 /page/byd-cn/news-2026/detail634
    private String title;          // 新闻标题
    private String url;            // 官方相对路径,如 /cn/detail634
    private String imageUrl;       // 封面(相对或绝对,不下载)
    private LocalDateTime publishDate; // 官方 date 解析(失败置空)
    private String tags;           // JSON 数组(官方 tags)
    private String tagNames;       // JSON 数组(官方 tagNames,展示用)
    // 正文大字段:列表接口不返回(NewsService.list 置 null,NON_NULL 时不出现);详情返回完整正文
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String content;        // 抽取正文纯文本(图片型新闻可能为空)
    private String source;         // 来源标识,默认 byd-news
    private String syncStatus;     // SUCCESS/FAILED(单条抽取失败)
    private LocalDateTime lastSyncAt;
    private String lastSyncError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
    /** 封面图对应的图库资产 id（09-15 img-classify；可空，不建外键）。 */
    private Long coverImageId;
    /** 非持久化派生字段:该新闻的向量块数(列表展示用,由 NewsService 填充)。 */
    @TableField(exist = false)
    private Long chunkCount;
    /** 非持久化派生字段:封面图库公网 URL(由 coverImageId 经图库取,优先于官网 imageUrl)。 */
    @TableField(exist = false)
    private String coverImageUrl;
    /** 非持久化派生字段:按标题分类命中的主题(受控词表,保序;供前端标签展示与筛选跳转)。 */
    @TableField(exist = false)
    private List<String> themes;
}
