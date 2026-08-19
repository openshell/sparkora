package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章版本实体。对应 sparkora_article_version。
 * 基于 brief 循环生成的多版正文（Markdown，风格各异）。project.current_version_id 指向选定版。
 */
@Data
@TableName("sparkora_article_version")
public class ArticleVersionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long briefId;
    private String title;
    private String contentMd;       // 正文 Markdown
    private String versionLabel;    // A / B / C
    private String styleTag;        // 正式 / 活泼 / 干货 等
    private String aiModel;
    private Integer tokenUsage;
    private Integer wordCount;
    private LocalDateTime createdAt;
}
