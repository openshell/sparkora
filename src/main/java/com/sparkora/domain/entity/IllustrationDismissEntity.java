package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配图建议「忽略」记录实体。对应 sparkora_illustration_dismiss（09-15 article-auto-illustrate，子C）。
 *
 * 只有用户显式「忽略某锚点建议组」这个**决策**落库；建议候选本身不落库
 * （建议是「当前正文 + 当前图库」的派生视图，可重算且结果稳定，落库会陈旧）。
 * UNIQUE(version_id, anchor_key) 数据库级防重（重复忽略幂等，不报错）；
 * 无 deleted 逻辑删除列：关系行生命周期 = 版本生命周期，物理删（同 sparkora_image_tag 惯例）。
 */
@Data
@TableName("sparkora_illustration_dismiss")
public class IllustrationDismissEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;         // → sparkora_article_project.id（应用层维护，不建强外键）
    private Long versionId;         // → sparkora_article_version.id（忽略记录不跨版本）
    private String anchorKey;       // 锚点指纹（headingPath + 文本短哈希，见 AnchorExtractor.fingerprint）
    private String createdBy;       // 操作人（用户名或 system）
    private LocalDateTime createdAt;
}
