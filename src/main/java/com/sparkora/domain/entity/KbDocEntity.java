package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通用汽车知识文档实体。对应 sparkora_kb_doc(S7 车型库泛化)。
 * 手工录入的知识条目;切块+向量化后供 RAG 检索(通用域,与车型域并行)。
 */
@Data
@TableName("sparkora_kb_doc")
public class KbDocEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;          // 知识标题
    private String domain;         // 领域标签: 通用/充电/保养/政策/技术科普…
    private String content;        // 原始正文
    private Boolean enabled;       // 停用后重建向量跳过(检索层无特判)
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}