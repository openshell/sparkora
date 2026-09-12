package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答消息实体。对应 sparkora_qa_message(C4 多轮对话式知识问答)。
 * role=user/assistant;assistant 消息携带 citations(JSON)与 rag_status。消息保留,无逻辑删除。
 */
@Data
@TableName("sparkora_qa_message")
public class QaMessageEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private String role;           // user / assistant
    private String content;
    private String citations;      // JSON 数组(Citation);user 消息为空
    private String ragStatus;      // OK/LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE;user 消息为空
    private LocalDateTime createdAt;
}
