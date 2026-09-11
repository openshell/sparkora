package com.sparkora.news.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新闻切块/日期解析纯函数单测(不连库,参照 KbDocServiceTest)。
 */
class NewsDocServiceTest {

    private static final LocalDateTime PUB = LocalDateTime.of(2026, 9, 1, 17, 9, 29);

    @Test
    void 首行固定新闻标题与日期() {
        List<String> chunks = NewsDocService.chunkContent("比亚迪发布新车型", PUB,
                "第一段。\n\n第二段。");
        assertEquals(2, chunks.size());
        for (String c : chunks) {
            assertTrue(c.startsWith("新闻：比亚迪发布新车型（2026-09-01）\n"), () -> "块首行不符: " + c);
        }
    }

    @Test
    void 单段一块_多段多块() {
        List<String> chunks = NewsDocService.chunkContent("T", PUB, "第一段。\n\n第二段。\n\n第三段。");
        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).endsWith("第一段。"));
        assertTrue(chunks.get(2).endsWith("第三段。"));
    }

    @Test
    void 超长段按句读切分合并_块体不超限() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) sb.append("这是第").append(i).append("句测试内容,用于验证切分逻辑。");
        List<String> chunks = NewsDocService.chunkContent("长文", PUB, sb.toString());
        assertTrue(chunks.size() > 1, "超长段应切成多块");
        for (String c : chunks) {
            String body = c.substring(c.indexOf('\n') + 1);
            assertTrue(body.length() <= NewsDocService.MAX_BODY_LEN, () -> "块体超限: " + body.length());
        }
    }

    @Test
    void 空正文有标题_保留标题锚点块() {
        List<String> chunks = NewsDocService.chunkContent("图片型新闻", PUB, "   \n  ");
        assertEquals(1, chunks.size());
        assertEquals("新闻：图片型新闻（2026-09-01）", chunks.get(0));
    }

    @Test
    void 空正文且无标题_返回空列表() {
        assertTrue(NewsDocService.chunkContent(null, PUB, "").isEmpty());
        assertTrue(NewsDocService.chunkContent("  ", PUB, null).isEmpty());
    }

    @Test
    void 无句读超长尾巴硬切() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 700; i++) sb.append('字');
        List<String> chunks = NewsDocService.chunkContent("硬切", PUB, sb.toString());
        assertTrue(chunks.size() >= 2);
        for (String c : chunks) {
            assertTrue(c.length() <= "新闻：硬切（2026-09-01）\n".length() + NewsDocService.MAX_BODY_LEN);
        }
    }
}
