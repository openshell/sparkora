package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答会话实体。对应 sparkora_qa_session(C4 多轮对话式知识问答)。
 * 按 created_by 归属,仅本人可见;逻辑删除。
 */
@Data
@TableName("sparkora_qa_session")
public class QaSessionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;          // 首问摘要,可空
    private String createdBy;      // 归属用户
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
