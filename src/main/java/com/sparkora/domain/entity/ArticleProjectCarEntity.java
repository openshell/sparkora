package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创作项目-车型关联实体。对应 sparkora_article_project_car。
 * 一篇文章可关联多个车型;生成时跨车型检索知识库注入事实约束。
 */
@Data
@TableName("sparkora_article_project_car")
public class ArticleProjectCarEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long carModelId;
    private Integer sortOrder;   // 关联顺序(首个为主车型)
    private LocalDateTime createdAt;
    @TableLogic
    private Integer deleted;
}
