package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创作项目实体。对应 sparkora_article_project。
 */
@Data
@TableName("sparkora_article_project")
public class ArticleProjectEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String topic;
    private String keywords;
    private String audience;
    private Integer wordCountTarget;
    private Long brandVoiceProfileId;
    private String status;  // DRAFT / GENERATING_BRIEF / READY / ...
    private Long currentBriefId;       // S1：指向当前 brief（sparkora_article_brief.id）
    private String lastBriefError;     // S1：最近一次生成失败原因（成功后清空）
    private Long currentVersionId;      // S1b：指向选定版本（sparkora_article_version.id）
    private String lastVersionError;    // S1b：最近一次版本生成失败原因（成功后清空）
    private String remark;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
