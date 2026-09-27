package com.sparkora.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TextChunker 单测（09-27 知识域写入侧统一）。
 *
 * 覆盖 KB（空正文恒保留标题块）与 NEWS（空正文仅标题非空才保留）两种语义，
 * 以及与旧实现一致的切块行为（分段/句读合并/硬切/换行转空格）。
 * 这些断言语义与既有 {@code KbDocServiceTest} / {@code NewsDocServiceTest} 一致，
 * 证明委托共享器后产出逐块不变（AC4）。
 */
class TextChunkerTest {

    private static final String KB_HEADER = "知识：T（通用）";
    private static final String NEWS_HEADER = "新闻：T（2026-09-01）";

    // ==================== 通用切块行为（两域一致） ====================

    @Test
    void 首行固定header_每块都带() {
        List<String> chunks = TextChunker.chunk(KB_HEADER, "第一段。\n\n第二段。", true, true, TextChunker.KB_SEPARATORS);
        assertEquals(2, chunks.size());
        for (String c : chunks) {
            assertTrue(c.startsWith(KB_HEADER + "\n"), () -> "块首行不符: " + c);
        }
    }

    @Test
    void 单段一块_多段多块() {
        List<String> chunks = TextChunker.chunk(KB_HEADER, "第一段。\n\n第二段。\n\n第三段。", true, true, TextChunker.KB_SEPARATORS);
        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).endsWith("第一段。"));
        assertTrue(chunks.get(2).endsWith("第三段。"));
    }

    @Test
    void 超长段按句读切分合并_块体不超限() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) sb.append("这是第").append(i).append("句测试内容,用于验证切分逻辑。");
        List<String> chunks = TextChunker.chunk(KB_HEADER, sb.toString(), true, true, TextChunker.KB_SEPARATORS);
        assertTrue(chunks.size() > 1, "超长段应切成多块");
        for (String c : chunks) {
            String body = c.substring(c.indexOf('\n') + 1);
            assertTrue(body.length() <= TextChunker.MAX_BODY_LEN, () -> "块体超限: " + body.length());
        }
    }

    @Test
    void 段内换行转空格_空段跳过() {
        List<String> chunks = TextChunker.chunk(KB_HEADER, "第一行\n第二行\n\n\n第三行", true, true, TextChunker.KB_SEPARATORS);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).contains("第一行 第二行"));
    }

    @Test
    void 无句读超长尾巴硬切() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 700; i++) sb.append('字');
        List<String> chunks = TextChunker.chunk(KB_HEADER, sb.toString(), true, true, TextChunker.KB_SEPARATORS);
        assertTrue(chunks.size() >= 2);
        for (String c : chunks) {
            assertTrue(c.length() <= (KB_HEADER + "\n").length() + TextChunker.MAX_BODY_LEN);
        }
    }

    @Test
    void KB句读集合不含全角感叹问号_按硬切() {
        // KB 历史句读集合为 。；!?；全角 ！？ 不切 → 超长整段走硬切(块体仍 ≤ 上限)
        String p = "甲".repeat(600) + "！" + "乙".repeat(600) + "？" + "丙".repeat(100);
        List<String> chunks = TextChunker.chunk(KB_HEADER, p, true, true, TextChunker.KB_SEPARATORS);
        assertTrue(chunks.size() >= 2);
        for (String c : chunks) {
            String body = c.substring(c.indexOf('\n') + 1);
            assertTrue(body.length() <= TextChunker.MAX_BODY_LEN, () -> "块体超限: " + body.length());
        }
        // 全角 ！？ 在 KB 中不是切分点：硬切边界会落在 ！ 之后附近，故 ! 不出现在块末尾边界上
        assertTrue(chunks.size() >= 3, "600+600+100 硬切应 ≥3 块: " + chunks.size());
    }

    @Test
    void NEWS句读集合含全角感叹问号_按句读切分() {
        String p = "甲".repeat(600) + "！" + "乙".repeat(600) + "？";
        List<String> chunks = TextChunker.chunk(NEWS_HEADER, p, true, true, TextChunker.NEWS_SEPARATORS);
        assertTrue(chunks.size() >= 2);
    }

    // ==================== AC4：与改造前逐块一致的等价性锁 ====================

    /**
     * KB 等价性：用「改造前 KB 算法」的独立复刻（句读集合 。；!?，空正文恒保留 header）对照。
     * 覆盖 KB 与 NEWS 句读集合的差异字符（全角！？ / 半角;）以防再次误扩集。
     */
    @Test
    void KB_与改造前算法逐块一致() {
        String content = "甲".repeat(300) + "！" + "乙".repeat(300) + "；" + "丙".repeat(300)
                + ";" + "丁".repeat(300) + "。" + "戊".repeat(300) + "？" + "己".repeat(300) + "!";
        List<String> actual = TextChunker.chunk(KB_HEADER, content, true, true, TextChunker.KB_SEPARATORS);
        List<String> legacy = legacyChunk(KB_HEADER, content, "。；!?", true);
        assertEquals(legacy, actual, "KB 切块须与改造前逐块一致");
    }

    /** NEWS 等价性：句读集合 。；;！!？?，空正文仅标题非空才保留 header。 */
    @Test
    void NEWS_与改造前算法逐块一致() {
        String content = "甲".repeat(300) + "！" + "乙".repeat(300) + "；" + "丙".repeat(300)
                + ";" + "丁".repeat(300) + "。" + "戊".repeat(300) + "？" + "己".repeat(300) + "!";
        List<String> actual = TextChunker.chunk(NEWS_HEADER, content, true, false, TextChunker.NEWS_SEPARATORS);
        List<String> legacy = legacyChunk(NEWS_HEADER, content, "。；;！!？?", true);
        assertEquals(legacy, actual, "NEWS 切块须与改造前逐块一致");
    }

    /** 改造前算法复刻（仅用于等价性对照，非生产代码）。 */
    private static List<String> legacyChunk(String header, String content, String seps, boolean keepWhenEmpty) {
        List<String> out = new java.util.ArrayList<>();
        if (content == null || content.strip().isEmpty()) {
            if (keepWhenEmpty) out.add(header);
            return out;
        }
        String[] paragraphs = content.split("\\n\\s*\\n");
        List<String> bodies = new java.util.ArrayList<>();
        StringBuilder carry = null;
        for (String pRaw : paragraphs) {
            String p = pRaw.replaceAll("\\s*\\n\\s*", " ").trim();
            if (p.isEmpty()) continue;
            if (p.length() <= TextChunker.MAX_BODY_LEN) {
                if (carry != null) { bodies.add(carry.toString()); carry = null; }
                bodies.add(p);
                continue;
            }
            for (String s : legacySplit(p, seps)) {
                if (carry == null) carry = new StringBuilder(s);
                else if (carry.length() + s.length() <= TextChunker.MAX_BODY_LEN) carry.append(s);
                else { bodies.add(carry.toString()); carry = new StringBuilder(s); }
            }
        }
        if (carry != null) bodies.add(carry.toString());
        for (String b : bodies) out.add(header + "\n" + b);
        if (out.isEmpty()) out.add(header);
        return out;
    }

    private static List<String> legacySplit(String p, String seps) {
        List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < p.length(); i++) {
            char ch = p.charAt(i);
            cur.append(ch);
            if (seps.indexOf(ch) >= 0) {
                String s = cur.toString();
                if (!s.isBlank()) out.add(s);
                cur.setLength(0);
            }
        }
        if (!cur.isEmpty()) {
            String tail = cur.toString();
            if (tail.length() <= TextChunker.MAX_BODY_LEN) {
                if (!tail.isBlank()) out.add(tail);
            } else {
                for (int i = 0; i < tail.length(); i += TextChunker.MAX_BODY_LEN) {
                    out.add(tail.substring(i, Math.min(tail.length(), i + TextChunker.MAX_BODY_LEN)));
                }
            }
        }
        return out;
    }

    // ==================== KB 语义：空正文恒保留标题块 ====================

    @Test
    void KB空正文_恒保留标题块() {
        List<String> chunks = TextChunker.chunk(KB_HEADER, "   \n  ", true, true, TextChunker.KB_SEPARATORS);
        assertEquals(1, chunks.size());
        assertEquals(KB_HEADER, chunks.get(0));
    }

    @Test
    void KB空正文且无标题_仍保留标题块() {
        // KB keepTitleWhenEmpty=true：即便 titlePresent=false 也保留（KB 校验层已拦空正文，此为防御性兜底）
        List<String> chunks = TextChunker.chunk(KB_HEADER, null, false, true, TextChunker.KB_SEPARATORS);
        assertEquals(List.of(KB_HEADER), chunks);
    }

    // ==================== NEWS 语义：空正文仅标题非空才保留 ====================

    @Test
    void NEWS空正文有标题_保留标题锚点块() {
        List<String> chunks = TextChunker.chunk(NEWS_HEADER, "   \n  ", true, false, TextChunker.NEWS_SEPARATORS);
        assertEquals(1, chunks.size());
        assertEquals(NEWS_HEADER, chunks.get(0));
    }

    @Test
    void NEWS空正文且无标题_返回空列表() {
        assertTrue(TextChunker.chunk(NEWS_HEADER, "", false, false, TextChunker.NEWS_SEPARATORS).isEmpty());
        assertTrue(TextChunker.chunk(NEWS_HEADER, null, false, false, TextChunker.NEWS_SEPARATORS).isEmpty());
    }

    @Test
    void NEWS有正文_无论标题是否为空都追加header() {
        List<String> chunks = TextChunker.chunk(NEWS_HEADER, "正文。", false, false, TextChunker.NEWS_SEPARATORS);
        assertEquals(1, chunks.size());
        assertEquals(NEWS_HEADER + "\n正文。", chunks.get(0));
    }
}
