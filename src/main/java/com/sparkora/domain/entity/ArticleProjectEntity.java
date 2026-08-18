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
    private String remark;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
