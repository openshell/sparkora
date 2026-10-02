package com.sparkora.car.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.domain.entity.CarModelEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 车型自动识别服务(S6 多车型)。
 *
 * 用途:创建文章时若用户未指定车型,由 AI 判断该文章是否与车型知识库相关,
 * 并从知识库车型列表中选出相关车型 id 回填到项目。
 *
 * 判定原则(避免误导):
 *  - 仅当「文章涉及车型」且「知识库有该文章需要的数据(参数/配置/价格)」时才关联;
 *  - 资讯/技术/销量等知识库无数据支撑的文章,返回 related=false,不关联车型。
 */
@Slf4j
@Service
public class CarModelMatcherService {

    private final AiClient aiClient;
    private final CarModelService carModelService;
    private final ObjectMapper json;

    public CarModelMatcherService(AiClient aiClient, CarModelService carModelService, ObjectMapper json) {
        this.aiClient = aiClient;
        this.carModelService = carModelService;
        this.json = json;
    }

    /** 识别结果。 */
    public record MatchResult(boolean related, List<Long> modelIds, String reason) {}

    /**
     * 分析文章主题/内容描述,判断是否应关联车型知识库,并选出相关车型。
     * @return 不相关或知识库无数据支撑时 related=false、modelIds 为空
     */
    public MatchResult match(String topic, String contentDescription) {
        List<CarModelEntity> models = carModelService.list();
        if (models.isEmpty()) return new MatchResult(false, List.of(), "知识库暂无车型数据");

        // 车型清单(名称 + 销售网络),供 AI 从候选里选
        StringBuilder catalog = new StringBuilder();
        for (CarModelEntity m : models) {
            catalog.append("- ").append(m.getName())
                    .append(m.getSalesNetwork() == null ? "" : "(" + m.getSalesNetwork() + ")")
                    .append("\n");
        }

        // C1:固定 system 指令外置模板 prompts/car/model-match-system.st
        String sys = com.sparkora.ai.PromptTemplateLoader.render("car/model-match-system.st", java.util.Map.of());

        String user = "文章主题：" + (topic == null ? "" : topic)
                + "\n内容描述：" + (contentDescription == null || contentDescription.isBlank() ? "无" : contentDescription)
                + "\n\n知识库现有车型(名称(销售网络) + id):\n" + catalog;

        try {
            AiClient.ChatResult cr = aiClient.chatJson(sys, user, 1024);
            // AI 输出 JSON 容错:剥围栏+转义字符串内裸控制字符(统一走 AiClient.sanitizeAiJson)
            JsonNode node = json.readTree(AiClient.sanitizeAiJson(cr.content()));
            boolean related = node.path("related").asBoolean(false);
            String reason = node.path("reason").asText("");
            List<Long> ids = new ArrayList<>();
            if (related) {
                JsonNode arr = node.path("modelIds");
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        long id = n.asLong(-1);
                        if (id > 0) ids.add(id);
                    }
                }
            }
            // 过滤:只保留知识库真实存在的车型 id
            List<Long> valid = new ArrayList<>();
            for (Long id : ids) {
                if (models.stream().anyMatch(m -> m.getId().equals(id))) valid.add(id);
            }
            return new MatchResult(related && !valid.isEmpty(), valid, reason);
        } catch (Exception e) {
            log.warn("车型自动识别失败,按不关联处理: {}", e.getMessage());
            return new MatchResult(false, List.of(), "车型识别失败,未自动关联");
        }
    }
}
