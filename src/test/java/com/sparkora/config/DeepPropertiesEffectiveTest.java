package com.sparkora.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeepProperties 新增配置单测(10-04-serper-provider A-R2/A-R4/A-R6):
 * 默认值、property 覆盖、末尾斜杠归一(路径段保留)、空白归一。
 *
 * <p>说明:env 分支依赖进程环境，单测只锁定「property 覆盖 > 字段默认」与归一逻辑(env 分支与既有
 * {@code effectiveTavilyKey()} 同款，行为一致)。
 */
class DeepPropertiesEffectiveTest {

    @AfterEach
    void clearProps() {
        System.clearProperty("DEEP_SERPER_API_BASE_URL");
        System.clearProperty("SERPER_API_BASE_URL");
        System.clearProperty("DEEP_SERPER_GL");
        System.clearProperty("DEEP_SERPER_HL");
        System.clearProperty("DEEP_SERPER_API_KEY");
        System.clearProperty("SERPER_API_KEY");
        System.clearProperty("DEEP_TAVILY_API_BASE_URL");
        System.clearProperty("TAVILY_API_BASE_URL");
        System.clearProperty("DEEP_TAVILY_API_KEY_HIKARI");
    }

    @Test
    void 默认值_官方端点与中文地域() {
        DeepProperties p = new DeepProperties();
        assertEquals("https://google.serper.dev", p.effectiveSerperApiBase());
        assertEquals("https://api.tavily.com", p.effectiveTavilyApiBase());
        assertEquals("cn", p.effectiveSerperGl());
        assertEquals("zh-cn", p.effectiveSerperHl());
        assertTrue(p.isWebVerticalNewsEnabled(), "news 垂直默认开(仅时效题走;关闭时强制 web)");
    }

    // ===== 10-05-tavily-endpoint-priority:双端点 / 独立超时 / 质量门默认值 =====

    @Test
    void tavily双端点默认_中转未配置_官方默认() {
        DeepProperties p = new DeepProperties();
        assertEquals("", p.effectiveTavilyRelayBase(), "中转默认未配置(空串,不回退官方)");
        assertEquals("", p.effectiveTavilyRelayKey(), "中转密钥默认空");
        assertEquals("https://api.tavily.com", p.effectiveTavilyApiBase(), "官方默认端点不变");
    }

    @Test
    void tavily中转_DEEP_TAVILY_API_BASE_URL映射中转_末尾斜杠归一() {
        System.setProperty("DEEP_TAVILY_API_BASE_URL", "https://relay.example/tavily/");
        DeepProperties p = new DeepProperties();
        assertEquals("https://relay.example/tavily", p.effectiveTavilyRelayBase(), "DEEP_TAVILY_API_BASE_URL 现指中转");
        // 关键:官方端点不被中转键污染,仍为默认(否则配置中转会把官方地址误置为中转)
        assertEquals("https://api.tavily.com", p.effectiveTavilyApiBase());
    }

    @Test
    void tavily官方端点_裸名TAVILY_API_BASE_URL覆盖() {
        System.setProperty("TAVILY_API_BASE_URL", "https://official.example/");
        assertEquals("https://official.example", new DeepProperties().effectiveTavilyApiBase());
    }

    @Test
    void tavily中转密钥_property与字段回退() {
        DeepProperties p = new DeepProperties();
        p.setTavilyRelayApiKey("field-relay-key");
        assertEquals("field-relay-key", p.effectiveTavilyRelayKey(), "无 property 时用字段");
        System.setProperty("DEEP_TAVILY_API_KEY_HIKARI", "prop-relay-key");
        assertEquals("prop-relay-key", new DeepProperties().effectiveTavilyRelayKey(), "property 优先");
    }

    @Test
    void tavily独立超时_默认值与零值兜底() {
        DeepProperties p = new DeepProperties();
        assertEquals(8000L, p.effectiveTavilyRelayReadTimeoutMs(), "中转默认短超时");
        assertEquals(30000L, p.effectiveTavilyOfficialReadTimeoutMs(), "官方默认长超时");
        assertEquals(15000L, p.effectiveTavilyExtractReadTimeoutMs(), "extract 独立 15s");
        p.setTavilyRelayReadTimeoutMs(0);
        p.setTavilyOfficialReadTimeoutMs(-1);
        p.setTavilyExtractReadTimeoutMs(0);
        assertEquals(8000L, p.effectiveTavilyRelayReadTimeoutMs(), "≤0 兜底默认");
        assertEquals(30000L, p.effectiveTavilyOfficialReadTimeoutMs());
        assertEquals(15000L, p.effectiveTavilyExtractReadTimeoutMs());
    }

    @Test
    void tavily质量门默认_噪声域与长度阈值() {
        DeepProperties p = new DeepProperties();
        assertEquals(java.util.List.of("weixin.sogou.com"), p.effectiveTavilyDenyDomains());
        assertEquals(0, p.effectiveTavilyMinContentChars(), "结果级长度门槛默认 off(零回归)");
        p.setTavilyMinContentChars(-5);
        assertEquals(0, p.effectiveTavilyMinContentChars());
        p.setTavilyMinContentChars(120);
        assertEquals(120, p.effectiveTavilyMinContentChars());
    }

    @Test
    void serper端点_property覆盖且支持路径段() {
        System.setProperty("DEEP_SERPER_API_BASE_URL", "https://search.604020.xyz/serper");
        DeepProperties p = new DeepProperties();
        assertEquals("https://search.604020.xyz/serper", p.effectiveSerperApiBase(), "中转路径段 /serper 必须保留");
    }

    @Test
    void serper端点_末尾斜杠归一() {
        System.setProperty("DEEP_SERPER_API_BASE_URL", "https://search.604020.xyz/serper/");
        assertEquals("https://search.604020.xyz/serper", new DeepProperties().effectiveSerperApiBase());
    }

    @Test
    void serper端点_裸名serper兼容且DEEP优先() {
        System.setProperty("SERPER_API_BASE_URL", "https://bare.example/serper");
        assertEquals("https://bare.example/serper", new DeepProperties().effectiveSerperApiBase());
        System.setProperty("DEEP_SERPER_API_BASE_URL", "https://deep.example/serper");
        assertEquals("https://deep.example/serper", new DeepProperties().effectiveSerperApiBase(), "DEEP_ 前缀优先");
    }

    @Test
    void serper端点_字段空白_回退官方默认() {
        DeepProperties p = new DeepProperties();
        p.setSerperApiBase("  ");
        assertEquals("https://google.serper.dev", p.effectiveSerperApiBase());
    }

    @Test
    void tavily端点_property覆盖与末尾斜杠归一() {
        System.setProperty("DEEP_TAVILY_API_BASE_URL", "https://relay.example/tavily/");
        // 10-05 起 DEEP_TAVILY_API_BASE_URL 指中转端点(官方端点改由裸名 TAVILY_API_BASE_URL)
        assertEquals("https://relay.example/tavily", new DeepProperties().effectiveTavilyRelayBase());
        assertEquals("https://api.tavily.com", new DeepProperties().effectiveTavilyApiBase());
    }

    @Test
    void gl_hl_property覆盖与空白归一() {
        System.setProperty("DEEP_SERPER_GL", "us");
        System.setProperty("DEEP_SERPER_HL", "  ");
        DeepProperties p = new DeepProperties();
        assertEquals("us", p.effectiveSerperGl());
        // 空白 property 视同未配置 → 落到字段默认 zh-cn(空白不下发由字段本身置空表达)
        assertEquals("zh-cn", p.effectiveSerperHl());
    }

    @Test
    void gl_hl_字段置空_生效为空串() {
        DeepProperties p = new DeepProperties();
        p.setSerperGl("  ");
        p.setSerperHl("");
        assertEquals("", p.effectiveSerperGl(), "字段置空 → 生效空串(调用方据此不下发该键)");
        assertEquals("", p.effectiveSerperHl());
    }

    @Test
    void serperKey_DEEP优先_裸名兼容_字段回退() {
        DeepProperties p = new DeepProperties();
        p.setSerperApiKey("field-key");
        assertEquals("field-key", p.effectiveSerperKey(), "无 property/env 时用字段");

        System.setProperty("SERPER_API_KEY", "bare-key");
        assertEquals("bare-key", p.effectiveSerperKey(), "裸名 SERPER_API_KEY 次优先");

        System.setProperty("DEEP_SERPER_API_KEY", "deep-key");
        assertEquals("deep-key", p.effectiveSerperKey(), "DEEP_SERPER_API_KEY 最优先");

        System.setProperty("DEEP_SERPER_API_KEY", "   ");
        assertEquals("bare-key", p.effectiveSerperKey(), "空白 property 跳过,落到裸名");
    }

    @Test
    void webVerticalNewsEnabled_可关闭() {
        DeepProperties p = new DeepProperties();
        p.setWebVerticalNewsEnabled(false);
        assertFalse(p.isWebVerticalNewsEnabled());
    }
}
