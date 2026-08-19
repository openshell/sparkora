package com.sparkora.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.domain.entity.StyleProfileEntity;
import com.sparkora.mapper.StyleProfileMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 风格库服务：CRUD + 从用户提供的样文提炼风格画像入库。
 *
 * 提炼（extract）：把用户样文喂给 AI，输出 {name, toneGuidance, description}。
 *  toneGuidance 是「给正文生成模型用的语气/结构指令」，后续 VersionService 生成版本时作为 system prompt 片段。
 *  不做交互式风格提炼 UI（按用户决定，前期由素材直接入库）。
 */
@Slf4j
@Service
public class StyleService {

    private final StyleProfileMapper mapper;
    private final AiClient aiClient;
    private final ObjectMapper json;

    public StyleService(StyleProfileMapper mapper, AiClient aiClient, ObjectMapper json) {
        this.mapper = mapper;
        this.aiClient = aiClient;
        this.json = json;
    }

    public StyleProfileEntity create(StyleProfileEntity e) {
        if (e.getName() == null || e.getName().isBlank()) throw new IllegalArgumentException("风格名必填");
        if (e.getEnabled() == null) e.setEnabled(true);
        e.setCreatedAt(LocalDateTime.now());
        mapper.insert(e);
        return e;
    }

    public StyleProfileEntity get(Long id) { return mapper.selectById(id); }

    public java.util.List<StyleProfileEntity> list(Boolean enabledOnly) {
        var qw = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StyleProfileEntity>();
        if (Boolean.TRUE.equals(enabledOnly)) qw.eq("enabled", true);
        qw.orderByDesc("id");
        return mapper.selectList(qw);
    }

    public StyleProfileEntity update(StyleProfileEntity e) {
        if (e.getId() == null) throw new IllegalArgumentException("id 必填");
        mapper.updateById(e);
        return mapper.selectById(e.getId());
    }

    public void delete(Long id) { mapper.deleteById(id); }

    /**
     * 从样文提炼风格画像并入库。
     * @param sourceText 用户提供的整篇样文
     * @param name 用户起的风络名（可空，由 AI 拟后用户再改）
     */
    public StyleProfileEntity extract(String sourceText, String name) {
        if (sourceText == null || sourceText.isBlank()) throw new IllegalArgumentException("样文不能为空");
        String sample = sourceText.length() > 4000 ? sourceText.substring(0, 4000) : sourceText;
        try {
            AiClient.ChatResult cr = aiClient.chatJson(
                    """
                    你是新媒体文风分析专家。阅读下面这篇样文，提炼其写作风格，输出 JSON 对象：
                    {
                      "name": "简短风格名(2-6字，若用户给了 name 字段则沿用)",
                      "description": "一句话描述这种风格的特点(20-40字)",
                      "toneGuidance": "给正文生成模型的语气与结构指令(中文，2-4句，可直接作为 system prompt 片段，要可操作：语气/句式/结构/用词偏好)"
                    }
                    只输出 JSON，不要额外文字。
                    """,
                    "（用户指定的风格名，可空）：" + (name == null ? "" : name) + "\n\n样文：\n" + sample,
                    1024);
            var node = json.readTree(cr.content());
            StyleProfileEntity e = new StyleProfileEntity();
            String n = node.path("name").asText("");
            String chosen = !n.isBlank() ? n
                    : (name != null && !name.isBlank() ? name : "新风格");
            e.setName(chosen);
            e.setDescription(node.path("description").asText(""));
            e.setToneGuidance(node.path("toneGuidance").asText(""));
            e.setSourceExcerpt(sourceText.length() > 2000 ? sourceText.substring(0, 2000) : sourceText);
            e.setEnabled(true);
            e.setCreatedAt(LocalDateTime.now());
            mapper.insert(e);
            return e;
        } catch (Exception e) {
            throw new AiException("风格提炼失败: " + e.getMessage(), e);
        }
    }
}
