package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 图片标签实体。对应 sparkora_image_tag（09-13 image-tags）。
 * 标签与图片的关联记录：按名称使用（不建标签字典表），同一图片同一标签不重复
 * （UNIQUE(image_id, tag_name) 数据库级防重，应用层捕冲突静默吞=幂等）。
 * 无 deleted 逻辑删除列：关系行生命周期 = 图片生命周期，物理删（图库表本身无逻辑删除）。
 */
@Data
@TableName("sparkora_image_tag")
public class ImageTagEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long imageId;           // → sparkora_image_asset.id（应用层维护，不建强外键）
    private String tagName;        // 标签名（trim 后 1~50 字符）
    private String createdBy;      // 操作人（用户名或 system）
    private LocalDateTime createdAt;
}