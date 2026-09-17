package com.sparkora.qa.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片意图判定单测（09-15 qa-auto-illustrate，子D 语义路径）。
 *
 * 覆盖：图片意图正例（看图/海报/照片/图片/给我看/我想看/看一下）、非意图负例
 * （普通知识提问不得触发检索——无 embedding 浪费）、null/空/空白、query 清洗与空清洗回退。
 */
class QaImageIntentTest {

    // ==================== 正例：命中图片意图 ====================

    @Test
    void 图片意图正例() {
        assertTrue(QaImageIntent.isImageIntent("给我看销量海报"));
        assertTrue(QaImageIntent.isImageIntent("看图"));
        assertTrue(QaImageIntent.isImageIntent("我想看比亚迪出海的照片"));
        assertTrue(QaImageIntent.isImageIntent("有没有技术发布会的图片"));
        assertTrue(QaImageIntent.isImageIntent("看一下这个配图"));
        assertTrue(QaImageIntent.isImageIntent("给我看"));
    }

    // ==================== 负例：非图片意图（不得触发语义检索） ====================

    @Test
    void 非图片意图负例() {
        assertFalse(QaImageIntent.isImageIntent("海狮08的续航是多少？"));
        assertFalse(QaImageIntent.isImageIntent("比亚迪最近和哪些企业合作建闪充生态？"));
        assertFalse(QaImageIntent.isImageIntent("价格区间和配置差异"));
        assertFalse(QaImageIntent.isImageIntent("家用充电桩怎么选"));
    }

    @Test
    void null空空白均为false() {
        assertFalse(QaImageIntent.isImageIntent(null));
        assertFalse(QaImageIntent.isImageIntent(""));
        assertFalse(QaImageIntent.isImageIntent("   "));
    }

    // ==================== query 清洗 ====================

    @Test
    void 清洗剥离意图短语保留检索信号() {
        String q = QaImageIntent.cleanQuery("给我看比亚迪销量海报");
        assertFalse(q.contains("给我看"), () -> "意图短语应被剥离: " + q);
        assertFalse(q.contains("海报"), () -> "意图短语应被剥离: " + q);
        assertTrue(q.contains("比亚迪销量"), () -> "检索信号必须保留: " + q);
    }

    @Test
    void 清洗后为空_回退用原问题() {
        assertEquals("看图", QaImageIntent.cleanQuery("看图"), "全为意图词时回退原问题，不得返回空 query");
        assertEquals("给我看", QaImageIntent.cleanQuery("给我看"));
        assertEquals("", QaImageIntent.cleanQuery(null));
    }

    /** 关键词表必须长的排前：先剥「看图」会把「看图片」切碎成「片」残留。 */
    @Test
    void 清洗长意图词不被短词切碎() {
        String q = QaImageIntent.cleanQuery("看图片 比亚迪");
        assertFalse(q.contains("片"), () -> "「看图片」不得被「看图」切出残片: " + q);
        assertTrue(q.contains("比亚迪"), () -> "检索信号必须保留: " + q);
    }

    @Test
    void 无意图词时原样返回() {
        assertEquals("海狮08续航", QaImageIntent.cleanQuery("海狮08续航"));
    }
}
