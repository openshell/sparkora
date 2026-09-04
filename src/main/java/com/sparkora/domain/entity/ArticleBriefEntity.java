package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创作 Brief 实体。对应 sparkora_article_brief。
 * 一个项目可多次重生成 brief，保留历史；project.current_brief_id 指向当前生效的。
 * JSON 字段以 String 存储（brief 结构由后端写入、前端解析），避免引入 JSON 类型处理器。
 *
 * 字段约定：
 *  - titleCandidates / coreViewpoints: JSON 字符串数组 ["...", ...]
 *  - outline: JSON 数组 [{ "heading": "...", "subPoints": ["...", ...] }, ...]
 *  - factRisks:  JSON 数组 [{ "claim": "...", "riskLevel": "low|medium|high", "suggestion": "..." }, ...]
 */
@Data
@TableName("sparkora_article_brief")
public class ArticleBriefEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String titleCandidates;  // JSON
    private String audienceRefine;
    private String coreViewpoints;   // JSON
    private String outline;          // JSON
    private String factRisks;        // JSON
    private String aiModel;
    private Integer tokenUsage;
    private String ragStatus;      // S6.1:知识库检索状态 OK/LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE
    // ===== S9 深度生成模式(gen_mode=DEEP 时使用;快速模式全空) =====
    private String genMode;            // FAST/DEEP
    private String clarifyQuestions;   // JSON [{q,type,options[],required}]
    private String clarifyAnswers;     // JSON [{q,a}] 锁定需求
    private String researchPlan;       // JSON {keyQuestions[],dataNeeds[],hypotheses[],toolHints[]}
    private String researchNotes;      // JSON [{agentId,question,status,facts[],gaps[]}] 逐子代理状态
    private String factSheet;          // JSON {entries[{key,value,source,confidence}],gaps[],warnings[]}
    private LocalDateTime createdAt;
}
