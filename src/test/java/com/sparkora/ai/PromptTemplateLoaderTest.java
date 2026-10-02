package com.sparkora.ai;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C1 {@link PromptTemplateLoader} 单测：模板加载/版本行剥离/占位符替换/缺参显式失败。
 */
class PromptTemplateLoaderTest {

    @Test
    void 加载模板_剥离首行版本注释() {
        String t = PromptTemplateLoader.load("brief/deep-brief-system.st");
        // 首行 # version: vN 不应出现在渲染结果
        assertFalse(t.startsWith("# version:"), "版本注释必须被剥离");
        assertTrue(t.contains("你是新媒体内容策划专家"), "模板正文应加载");
        assertTrue(t.contains("titleCandidates"), "JSON 字段应保留(字面花括号不受占位符影响)");
    }

    @Test
    void 替换占位符_逐项变量注入() {
        String rendered = PromptTemplateLoader.render("version/topic-user.st", Map.of(
                "topic", "比亚迪闪充", "contentDescription", "围绕落成写", "audience", "行业分析师",
                "wordCount", "2200", "titleCandidates", "TC", "coreViewpoints", "CV", "outline", "OL"));
        assertTrue(rendered.contains("主题：比亚迪闪充"));
        assertTrue(rendered.contains("目标字数：2200"));
        assertTrue(rendered.contains("标题候选：TC"));
        assertFalse(rendered.contains("{{"), "不得残留占位符");
    }

    @Test
    void 多占位符顺序替换_不越界() {
        String rendered = PromptTemplateLoader.render("version/imitation-user.st", Map.of(
                "wordCount", "1500", "outline", "结构A", "coreViewpoints", "观点B", "imitationText", "原文C"));
        assertTrue(rendered.indexOf("结构A") < rendered.indexOf("观点B"), "顺序保持");
        assertTrue(rendered.contains("原文C"));
        assertFalse(rendered.contains("{{"));
    }

    @Test
    void 缺占位符_显式抛IllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> PromptTemplateLoader.render("version/topic-user.st", Map.of("topic", "x")));
    }

    @Test
    void 变量值null_视为空串() {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("noSourcesRule", null);   // 显式 null 视为空串
        String rendered = PromptTemplateLoader.render("deep/subagent-system.st", params);
        assertFalse(rendered.contains("{{noSourcesRule}}"), "null 变量应替换为空串");
    }

    @Test
    void 双关降级块_作为变量注入() {
        String noSrc = PromptTemplateLoader.render("deep/subagent-nosources.st", Map.of());
        String sys = PromptTemplateLoader.render("deep/subagent-system.st", Map.of("noSourcesRule", noSrc));
        assertTrue(sys.contains("本次未启用任何外部资料检索"), "双关时降级说明应注入");
        assertTrue(sys.contains("你是研究子代理"), "主体指令仍在");
    }

    /**
     * C1 逐字等价回归：{@code shared/style-enforce.st} 由 VersionService 与 DeepWriterService 共用，
     * 但两者改造前的前导空白不同——VersionService 用 {@code "\n\n"+句子}，DeepWriter 用裸句子。
     * 模板正文须为**无前导/尾随空白**的裸句；VersionService 侧显式补 {@code "\n\n"}。
     * 此处锁定模板形态，防止再度把 {@code "\n\n"} 写回模板（会让 DeepWriter 也多出前导换行、并令
     * VersionService 出现四个换行）。
     */
    @Test
    void 风格强化句模板_为无前导换行裸句() {
        assertEquals("以上语气、句式、结构与用词特征必须在正文中充分体现,不得只在部分段落贴合。",
                PromptTemplateLoader.load("shared/style-enforce.st"),
                "模板须为裸句;前导 \\n\\n 归 VersionService 调用点负责");
    }
}
