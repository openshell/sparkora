package com.sparkora.car.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * S6b 空值防线单测:AI 兜底返回 value 空/全空白 → 视为失败返回 null(不入库)。
 * 通过覆写 AiClient 假实现返回固定 JSON,验证 AiParamCleaner 的空值拦截。
 */
class AiParamCleanerBlankValueTest {

    private AiParamCleaner newCleaner(String aiJsonContent) throws Exception {
        // 用匿名子类覆写 chatJson 返回固定内容(不连真实 AI)
        com.sparkora.ai.AiClient fake = new com.sparkora.ai.AiClient(new com.sparkora.config.AiProperties()) {
            @Override
            public ChatResult chatJson(String system, String user, int maxTokens) {
                return new ChatResult(aiJsonContent, "fake", 0);
            }
        };
        return new AiParamCleaner(fake, new ObjectMapper());
    }

    @Test
    void AI返回空值_视为失败返回null() throws Exception {
        AiParamCleaner cleaner = newCleaner("{\"paramKey\":\"发动机型号\",\"valueType\":\"STRING\",\"value\":\"\"}");
        assertNull(cleaner.clean("发动机型号", "—"));
    }

    @Test
    void AI返回全空白值_视为失败返回null() throws Exception {
        AiParamCleaner cleaner = newCleaner("{\"value\":\"   \"}");
        assertNull(cleaner.clean("变速系统", "—"));
    }

    @Test
    void AI返回正常值_通过() throws Exception {
        AiParamCleaner cleaner = newCleaner("{\"paramKey\":\"整车质保\",\"valueType\":\"ENUM\",\"value\":\"有\"}");
        assertNotNull(cleaner.clean("整车质保", "●"));
    }
}