package com.sparkora.car.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.car.dto.ParamCleanResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 参数清洗器。规则引擎覆盖不了的复合/歧义值,喂 LLM 解析为结构化 JSON。
 * 复用现有 AiClient.chatJson(强制 JSON 输出)。
 */
@Slf4j
@Component
public class AiParamCleaner {

    private final AiClient aiClient;
    private final ObjectMapper json;

    public AiParamCleaner(AiClient aiClient, ObjectMapper json) {
        this.aiClient = aiClient;
        this.json = json;
    }

    /**
     * AI 清洗单个参数值。
     * @param paramName 原始参数名
     * @param rawValue  原始值
     * @return 清洗结果;失败返回 null
     */
    public ParamCleanResult clean(String paramName, String rawValue) {
        try {
            AiClient.ChatResult cr = aiClient.chatJson(
                    buildSystemPrompt(),
                    "参数名：" + paramName + "\n原始值：" + rawValue,
                    1024);
            JsonNode node = json.readTree(cr.content());
            ParamCleanResult r = new ParamCleanResult();
            r.setParamKey(node.path("paramKey").asText(paramName));
            r.setValueType(node.path("valueType").asText("STRING"));
            r.setValue(node.path("value").asText(""));
            r.setUnit(node.path("unit").isNull() ? null : node.path("unit").asText());
            r.setEnumValue(node.path("enumValue").isNull() ? null : node.path("enumValue").asText());
            if (node.path("numericValue").isNumber()) {
                r.setNumericValue(node.path("numericValue").decimalValue());
            }
            if (node.path("listValues").isArray()) {
                List<String> list = new ArrayList<>();
                for (JsonNode item : node.path("listValues")) list.add(item.asText());
                r.setListValues(list);
            }
            r.setCleanMethod("AI");
            r.setConfidence(node.path("confidence").isNumber()
                    ? node.path("confidence").decimalValue() : new BigDecimal("0.5"));
            return r;
        } catch (Exception e) {
            log.warn("AI 参数清洗失败 {}={}: {}", paramName, rawValue, e.getMessage());
            return null;
        }
    }

    private String buildSystemPrompt() {
        return """
                你是汽车参数数据清洗专家。把比亚迪官网的原始参数值解析为结构化 JSON。
                只输出 JSON 对象,字段:
                {
                  "paramKey": "规范化参数名(去单位括号,如 轴距)",
                  "valueType": "STRING|NUMBER|BOOLEAN|ENUM|LIST",
                  "value": "清洗后的主值",
                  "numericValue": 数值(仅 NUMBER 时,否则 null),
                  "unit": "单位(如 mm/km/kWh,无则 null)",
                  "enumValue": "有|无|可选装(仅 ENUM 时,否则 null)",
                  "listValues": ["多值列表(仅 LIST 时,否则 [])"],
                  "confidence": 0.0到1.0的置信度
                }
                规则:
                - ● 表示"有",○ 表示"可选装",— 表示"无"
                - 复合值如 "4810×1920×1675" 拆成 LIST ["4810","1920","1675"]
                - "190/长续航模式205" 拆成 LIST ["190","长续航模式205"]
                - 纯文本如 "EHS电混系统" 用 STRING
                - 数值带单位用 NUMBER + unit
                只输出 JSON,不要额外文字。
                """;
    }
}
