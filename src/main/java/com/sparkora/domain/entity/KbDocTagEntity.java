package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * KB 文档标签实体。对应 sparkora_kb_doc_tag（10-03 E3）。
 * 标签与文档的关联记录：按名称使用（不建标签字典表），同一文档同一标签不重复
 * （UNIQUE(doc_id, tag_name) 数据库级防重，应用层捕冲突静默吞 = 幂等）。
 * 无 deleted 逻辑删除列：关系行生命周期 = 文档生命周期，物理删（镜像 sparkora_image_tag）。
 */
@Data
@TableName("sparkora_kb_doc_tag")
public class KbDocTagEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;             // → sparkora_kb_doc.id（应用层维护，不建强 FK）
    private String tagName;         // 标签名（trim 后 1~50 字符）
    private String createdBy;       // 操作人（用户名或 system）
    private LocalDateTime createdAt;
}
