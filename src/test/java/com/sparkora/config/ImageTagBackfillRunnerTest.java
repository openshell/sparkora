package com.sparkora.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存量新闻图回溯的纯函数单测（09-15 img-classify，不连库）。
 * 重点：文件名 detail<数字> 提取与 news_id 精确后缀匹配（防 detail63 误配 detail632）。
 */
class ImageTagBackfillRunnerTest {

    @Test
    void 文件名提取detail数字() {
        assertEquals("632", ImageTagBackfillRunner.detailNoOf("news-_page_byd-cn_news-2026_detail632.jpg"));
        assertEquals("634", ImageTagBackfillRunner.detailNoOf("news-_page_byd-cn_news-2026_detail634.png"));
        assertEquals("423", ImageTagBackfillRunner.detailNoOf("a_detail423.webp"));
    }

    @Test
    void 文件名无detail返回null() {
        assertNull(ImageTagBackfillRunner.detailNoOf("upload.png"));
        assertNull(ImageTagBackfillRunner.detailNoOf(""));
        assertNull(ImageTagBackfillRunner.detailNoOf(null));
    }

    @Test
    void 精确后缀匹配_防子串误配() {
        assertTrue(ImageTagBackfillRunner.matchesDetail("/page/byd-cn/news-2026/detail632", "632"));
        // 关键回归：detail63 不得命中 detail632（LIKE 子串会误配，精确判定必须为 false）
        assertFalse(ImageTagBackfillRunner.matchesDetail("/page/byd-cn/news-2026/detail632", "63"));
        assertFalse(ImageTagBackfillRunner.matchesDetail("/page/byd-cn/news-2026/detail634", "63"));
        // 反向：detail63 自身可命中
        assertTrue(ImageTagBackfillRunner.matchesDetail("/page/byd-cn/news-2023/detail63", "63"));
    }

    @Test
    void 匹配边界入参() {
        assertFalse(ImageTagBackfillRunner.matchesDetail(null, "1"));
        assertFalse(ImageTagBackfillRunner.matchesDetail("/x/detail1", null));
        assertFalse(ImageTagBackfillRunner.matchesDetail("/x/detail1", "  "));
        assertFalse(ImageTagBackfillRunner.matchesDetail("/x/other1", "1"));   // 无 detail 前缀
    }
}
