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
    private String ragStatus;      // S6.1:知识库检索状态 OK/LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE
    private String ragCitations;   // R3:知识库引用明细 JSON [{source,modelName,chunkType,score,chunkText}]
    private String factRisks;      // S9:数值回查结果 JSON [{claim,riskLevel,suggestion}](深度模式)
    private Integer wordCount;
    private Double similarityScore;  // 文章仿写:与原文 5-gram 重合率 0~1(仅仿写版有值)
    private String similarityReport; // 文章仿写:自检明细 JSON {maxRunLength,repeatedRuns:[{text,length}]}
    private Long coverImageId;      // S3b：该版本封面（sparkora_image_asset.id，可空）
    // S3b 正文插图原为版本表中的逗号分隔 id 列（违反 1NF）；P1-⑦ 已规范化为
    // sparkora_article_version_image 关联表（一行一图，sort_order 保序），此处不再有对应字段。
    private LocalDateTime createdAt;
}
