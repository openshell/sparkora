package com.sparkora.ai;

import com.sparkora.config.AiProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C1 任务级参数单测（AC-任务级参数）：结构化类与正文类 temperature 不同、可配置、可测。
 */
class TaskChatOptionsFactoryTest {

    @Test
    void 结构化与正文温度不同() {
        AiProperties p = new AiProperties();  // 默认 structured=0.2 / prose=0.7 / qa=0.5
        TaskChatOptionsFactory f = new TaskChatOptionsFactory(p);
        double structured = f.temperature(TaskType.STRUCTURED_EXTRACT);
        double prose = f.temperature(TaskType.ARTICLE_WRITE);
        double qa = f.temperature(TaskType.QA_CHAT);
        assertNotEquals(structured, prose, "结构化类与正文类必须不同温度");
        assertTrue(structured < prose, "结构化应低温、正文应高温");
        assertEquals(0.2, structured, 1e-9);
        assertEquals(0.7, prose, 1e-9);
        assertEquals(0.5, qa, 1e-9);
    }

    @Test
    void 温度可经AiProperties覆盖() {
        AiProperties p = new AiProperties();
        p.setTemperatureStructured(0.05);
        p.setTemperatureProse(0.9);
        p.setTemperatureQa(0.3);
        TaskChatOptionsFactory f = new TaskChatOptionsFactory(p);
        assertEquals(0.05, f.temperature(TaskType.STRUCTURED_EXTRACT), 1e-9);
        assertEquals(0.9, f.temperature(TaskType.ARTICLE_WRITE), 1e-9);
        assertEquals(0.3, f.temperature(TaskType.QA_CHAT), 1e-9);
    }

    @Test
    void null任务回退全局温度() {
        AiProperties p = new AiProperties();
        p.setTemperature(0.42);
        assertEquals(0.42, new TaskChatOptionsFactory(p).temperature(null), 1e-9);
    }

    @Test
    void forTask构建带温度options() {
        AiProperties p = new AiProperties();
        var opts = new TaskChatOptionsFactory(p).forTask(TaskType.STRUCTURED_EXTRACT).build();
        assertEquals(0.2, opts.getTemperature(), 1e-9);
    }
}
