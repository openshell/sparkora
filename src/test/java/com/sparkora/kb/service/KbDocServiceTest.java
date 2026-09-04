package com.sparkora.kb.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KbDocService 切块算法单测(纯函数,不连库)。
 */
class KbDocServiceTest {

    @Test
    void 首行固定知识标题领域() {
        List<String> chunks = KbDocService.chunkContent("家用充电桩选择要点", "充电",
                "功率选择看车型支持的最大充电功率。\n\n安装需要物业同意。");
        assertEquals(2, chunks.size());
        for (String c : chunks) {
            assertTrue(c.startsWith("知识：家用充电桩选择要点（充电）\n"), () -> "块首行不符: " + c);
        }
    }

    @Test
    void 单段一块_多段多块() {
        List<String> chunks = KbDocService.chunkContent("T", "通用", "第一段。\n\n第二段。\n\n第三段。");
        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).endsWith("第一段。"));
        assertTrue(chunks.get(2).endsWith("第三段。"));
    }

    @Test
    void 超长段按句读切分合并_块体不超限() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) sb.append("这是第").append(i).append("句测试内容,用于验证切分逻辑。");
        List<String> chunks = KbDocService.chunkContent("长文", "通用", sb.toString());
        assertTrue(chunks.size() > 1, "超长段应切成多块");
        for (String c : chunks) {
            String body = c.substring(c.indexOf('\n') + 1);
            assertTrue(body.length() <= KbDocService.MAX_BODY_LEN,
                    () -> "块体超限: " + body.length());
        }
    }

    @Test
    void 段内换行转空格_空段跳过() {
        List<String> chunks = KbDocService.chunkContent("T", "通用", "第一行\n第二行\n\n\n第三行");
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).contains("第一行 第二行"));
    }

    @Test
    void 空内容兜底保留标题块() {
        List<String> chunks = KbDocService.chunkContent("T", "通用", "   \n  ");
        assertEquals(1, chunks.size());
        assertEquals("知识：T（通用）", chunks.get(0));
    }

    @Test
    void 无句读超长尾巴硬切() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 700; i++) sb.append('字');
        List<String> chunks = KbDocService.chunkContent("硬切", "通用", sb.toString());
        assertTrue(chunks.size() >= 2);
        for (String c : chunks) {
            assertTrue(c.length() <= "知识：硬切（通用）\n".length() + KbDocService.MAX_BODY_LEN);
        }
    }
}