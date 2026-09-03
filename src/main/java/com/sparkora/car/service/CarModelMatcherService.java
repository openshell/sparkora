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
     * 分析文章主题/关键词,判断是否应关联车型知识库,并选出相关车型。
     * @return 不相关或知识库无数据支撑时 related=false、modelIds 为空
     */
    public MatchResult match(String topic, String keywords) {
        List<CarModelEntity> models = carModelService.list();
        if (models.isEmpty()) return new MatchResult(false, List.of(), "知识库暂无车型数据");

        // 车型清单(名称 + 销售网络),供 AI 从候选里选
        StringBuilder catalog = new StringBuilder();
        for (CarModelEntity m : models) {
            catalog.append("- ").append(m.getName())
                    .append(m.getSalesNetwork() == null ? "" : "(" + m.getSalesNetwork() + ")")
                    .append("\n");
        }

        String sys = """
                你是车型知识库关联分析助手。判断一篇文章是否应该关联车型知识库。
                知识库只包含车型的参数/配置/价格/版本等结构化数据,能支撑「车型对比、配置分析、购车建议」类文章。
                对资讯、技术原理、销量趋势等知识库无数据支撑的文章,不要关联车型。
                只输出 JSON 对象,不要任何额外文字:
                {
                  "related": true或false,   // 是否应关联车型知识库
                  "modelIds": [车型id数组],  // related=true 时,从候选车型中选出文章涉及的相关车型 id(可多个);related=false 时为空数组
                  "reason": "一句话说明判断依据"
                }
                所有内容用中文。
                """;

        String user = "文章主题：" + (topic == null ? "" : topic)
                + "\n关键词：" + (keywords == null || keywords.isBlank() ? "无" : keywords)
                + "\n\n知识库现有车型(名称(销售网络) + id):\n" + catalog;

        try {
            AiClient.ChatResult cr = aiClient.chatJson(sys, user, 1024);
            JsonNode node = json.readTree(cr.content());
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
