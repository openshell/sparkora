package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 通用知识切块实体。对应 sparkora_kb_chunk(S7)。
 * 检索单元;chunk_text 首行固定「知识:<title>(<domain>)」,便于跨域检索时自带主题锚点。
 */
@Data
@TableName("sparkora_kb_chunk")
public class KbChunkEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;
    private Integer seq;           // 同 doc 内块序号
    private String chunkText;
    private LocalDateTime createdAt;
    /** 非持久化:文档标题(构建期填充,写 vector_store metadata.name 用,不落 sparkora_kb_chunk)。 */
    @TableField(exist = false)
    private String docTitle;
    /** 非持久化:store metadata.active(10-03 E3 生效期,rebuild 时按 enabled+生效期计算)。 */
    @TableField(exist = false)
    private Boolean storeActive;
    /** 非持久化:来源(10-03 E3,写 store metadata.source)。 */
    @TableField(exist = false)
    private String source;
    /** 非持久化:生效起(10-03 E3,写 store metadata.effectiveFrom)。 */
    @TableField(exist = false)
    private LocalDate effectiveFrom;
    /** 非持久化:生效止(10-03 E3,写 store metadata.effectiveTo)。 */
    @TableField(exist = false)
    private LocalDate effectiveTo;
    /** 非持久化:标签(10-03 E3,写 store metadata.tags)。 */
    @TableField(exist = false)
    private List<String> tags;
}