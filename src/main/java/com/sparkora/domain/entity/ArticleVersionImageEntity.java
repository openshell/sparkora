package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 版本-正文插图关联实体。对应 sparkora_article_version_image（P1-⑦ 原逗号列规范化）。
 *
 * 原「版本表正文插图 id 逗号列」（违反 1NF）→ 一对多关联表：一行一图，sort_order 保序。
 *  - UNIQUE(version_id, image_id) 数据库级防重（重复登记幂等，应用层捕 DuplicateKeyException 静默吞）；
 *  - 无 deleted 逻辑删除列（关系行生命周期 = 版本生命周期，物理删，同 sparkora_image_tag 惯例）；
 *  - 不建强外键（应用层维护，version_id/image_id 直接 WHERE）。
 */
@Data
@TableName("sparkora_article_version_image")
public class ArticleVersionImageEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long versionId;        // → sparkora_article_version.id（应用层维护，不建强 FK）
    private Long imageId;          // → sparkora_image_asset.id（应用层维护，不建强 FK）
    private Integer sortOrder;     // 正文插图顺序（0 起，与原逗号串顺序一致）
    private LocalDateTime createdAt;
}
