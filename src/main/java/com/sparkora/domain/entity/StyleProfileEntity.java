package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 风格画像实体。对应 sparkora_style_profile。
 * 由用户提供样文，AI 提炼为 toneGuidance（生成时作为 system prompt 片段）。
 */
@Data
@TableName("sparkora_style_profile")
public class StyleProfileEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String toneGuidance;    // 给生成模型的语气/结构指令
    private String sourceExcerpt;   // 提炼自哪段原文（截断保留）
    private Boolean enabled;
    private LocalDateTime createdAt;
}
