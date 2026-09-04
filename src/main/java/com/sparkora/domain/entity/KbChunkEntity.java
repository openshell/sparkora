package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

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
}