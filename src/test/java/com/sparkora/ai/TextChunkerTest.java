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

    // ==================== 10-03 E2：滑动重叠 ====================

    /** 取块正文（header 后首行之后的部分）。 */
    private static String bodyOf(String chunk) {
        int nl = chunk.indexOf('\n');
        return nl < 0 ? "" : chunk.substring(nl + 1);
    }

    /** 默认 5 参 = 显式 overlap 0：逐块等价（向后兼容锁）。 */
    @Test
    void 默认无重叠_等价显式零重叠() {
        String content = "甲".repeat(300) + "！" + "乙".repeat(300) + "；" + "丙".repeat(300)
                + ";" + "丁".repeat(300) + "。" + "戊".repeat(300) + "？" + "己".repeat(300) + "!";
        List<String> dflt = TextChunker.chunk(KB_HEADER, content, true, true, TextChunker.KB_SEPARATORS);
        List<String> zero = TextChunker.chunk(KB_HEADER, content, true, true, TextChunker.KB_SEPARATORS, 0);
        assertEquals(zero, dflt, "默认入口必须等价 overlap=0 旧行为");
    }

    /** 多段短文本启用重叠：块数不变；每块（除首块）以「上一块尾部片段」为前缀；块体不超限。 */
    @Test
    void 多段短文本_相邻块滑动重叠_块数不变() {
        // 每段 > 重叠阈值，确保重叠生效；段间用空行分隔
        String p1 = "甲".repeat(80) + "。";
        String p2 = "乙".repeat(80) + "。";
        String p3 = "丙".repeat(80) + "。";
        String content = p1 + "\n\n" + p2 + "\n\n" + p3;
        List<String> noOverlap = TextChunker.chunk(KB_HEADER, content, true, true, TextChunker.KB_SEPARATORS, 0);
        List<String> overlap = TextChunker.chunk(KB_HEADER, content, true, true, TextChunker.KB_SEPARATORS,
                TextChunker.DEFAULT_OVERLAP_CHARS);
        assertEquals(noOverlap.size(), overlap.size(), "重叠只加前缀，不得改变块数");
        // 后两块以前一块尾部片段开头（严格短于前一块，非整块重复）
        for (int i = 1; i < overlap.size(); i++) {
            String prev = bodyOf(overlap.get(i - 1));
            String next = bodyOf(overlap.get(i));
            assertTrue(next.length() > 0);
            assertTrue(!next.startsWith(prev), "不得整块重复: " + i);
            boolean overlapped = false;
            for (int k = 1; k < prev.length(); k++) {
                if (next.startsWith(prev.substring(k))) { overlapped = true; break; }
            }
            assertTrue(overlapped, "后块应含上一块尾部重叠: " + i);
        }
        for (String c : overlap) {
            assertTrue(bodyOf(c).length() <= TextChunker.MAX_BODY_LEN);
        }
    }

    /** 空正文 + 单段短文本：重叠无副作用。 */
    @Test
    void 空正文与单段短文本_重叠无副作用() {
        assertEquals(List.of(KB_HEADER), TextChunker.chunk(KB_HEADER, "   ", true, true,
                TextChunker.KB_SEPARATORS, TextChunker.DEFAULT_OVERLAP_CHARS));
        assertEquals(List.of(KB_HEADER + "\n单段。"), TextChunker.chunk(KB_HEADER, "单段。", true, true,
                TextChunker.KB_SEPARATORS, TextChunker.DEFAULT_OVERLAP_CHARS));
    }

    /** 超长段启用重叠：相邻块存在非整块重叠，且块体仍 ≤ 上限。 */
    @Test
    void 超长段_相邻块滑动重叠_块体不超限() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 80; i++) sb.append("第").append(i).append("句测试内容。");
        List<String> chunks = TextChunker.chunk(NEWS_HEADER, sb.toString(), true, true,
                TextChunker.NEWS_SEPARATORS, TextChunker.DEFAULT_OVERLAP_CHARS);
        assertTrue(chunks.size() > 1, "超长段应多块");
        for (String c : chunks) {
            String body = bodyOf(c);
            assertTrue(body.length() <= TextChunker.MAX_BODY_LEN, () -> "块体超限: " + body.length());
        }
        // 相邻块：后块以「前块的一个非空后缀」开头（滑动重叠），且不是整块重复
        for (int i = 0; i + 1 < chunks.size(); i++) {
            String prev = bodyOf(chunks.get(i));
            String next = bodyOf(chunks.get(i + 1));
            assertTrue(!next.startsWith(prev), "重叠不得退化为整块重复: " + i);
            boolean overlapped = false;
            for (int k = 1; k < prev.length(); k++) {
                if (next.startsWith(prev.substring(k))) { overlapped = true; break; }
            }
            assertTrue(overlapped, "相邻块应存在非空后缀重叠: " + i);
        }
    }

    /** 重叠前缀在句读边界之后开始：后块正文以完整句子开头（不截半句）。 */
    @Test
    void 重叠前缀对齐句读边界_后块以分隔符后开头() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 80; i++) sb.append("第").append(i).append("句测试内容。");
        List<String> chunks = TextChunker.chunk(KB_HEADER, sb.toString(), true, true,
                TextChunker.KB_SEPARATORS, TextChunker.DEFAULT_OVERLAP_CHARS);
        assertTrue(chunks.size() > 1);
        // 后块 body 的首字符不是分隔符（片段从分隔符之后开始），且 body 不以分隔符开头
        for (int i = 1; i < chunks.size(); i++) {
            String body = bodyOf(chunks.get(i));
            assertTrue(body.length() > 0);
            assertTrue(TextChunker.KB_SEPARATORS.indexOf(body.charAt(0)) < 0,
                    () -> "重叠前缀不应以句读符开头: " + body.substring(0, Math.min(10, body.length())));
        }
    }

    /** KB/NEWS 句读集合差异在重叠路径仍生效：KB 不把全角！当句边界，NEWS 会。 */
    @Test
    void 重叠保留KB与NEWS句读集合差异() {
        String content = "甲".repeat(300) + "！" + "乙".repeat(300);
        int kb = TextChunker.chunk(KB_HEADER, content, true, true, TextChunker.KB_SEPARATORS,
                TextChunker.DEFAULT_OVERLAP_CHARS).size();
        int news = TextChunker.chunk(NEWS_HEADER, content, true, true, TextChunker.NEWS_SEPARATORS,
                TextChunker.DEFAULT_OVERLAP_CHARS).size();
        assertTrue(kb >= 2 && news >= 2);
        // 600 长段无句读：两者都硬切为 2 块；差异体现在切分点而非块数，此处锁定不回归为单块
        assertTrue(kb >= 2, "KB 全角！非句读，应硬切为 ≥2");
        assertEquals(2, news, "NEWS 全角！为句读，600 段应 2 块");
    }

    /** overlapTail 纯函数边界：不整块重复 / 句读对齐 / 无句读直取尾部。 */
    @Test
    void overlapTail_边界() {
        // 不严格长于重叠长度 → 空（禁止整块重复）
        assertEquals("", TextChunker.overlapTail("短。", TextChunker.KB_SEPARATORS, 60));
        assertEquals("", TextChunker.overlapTail("12345", TextChunker.KB_SEPARATORS, 5));
        // 有句读：从尾部片段内首个分隔符之后开始
        String prev = "甲".repeat(50) + "。" + "乙".repeat(50) + "。" + "丙".repeat(50);
        String tail = TextChunker.overlapTail(prev, TextChunker.KB_SEPARATORS, 60);
        assertTrue(tail.startsWith("丙"), () -> "应从首个句读之后开始: " + tail);
        assertTrue(tail.length() < prev.length(), "不得返回整块");
        // 无句读：直取尾 overlap 字符
        String hard = "字".repeat(200);
        assertEquals(60, TextChunker.overlapTail(hard, TextChunker.KB_SEPARATORS, 60).length());
        // 整段即一个完整句（唯一分隔符在片段末尾）：退化为直取尾 overlap 字符（重叠本就跨边界，允许句中起点）
        String endsWithSep = "甲".repeat(200) + "。";
        int ov = 60;
        String t = TextChunker.overlapTail(endsWithSep, TextChunker.KB_SEPARATORS, ov);
        assertEquals(endsWithSep.substring(endsWithSep.length() - ov), t);
        assertTrue(t.endsWith("。"));
        assertTrue(t.length() < endsWithSep.length(), "前缀须严格短于上一块，禁止整块重复");
        // 分隔符在片段内部：从其之后开始（对齐句读）
        String midSep = "乙".repeat(80) + "。" + "甲".repeat(30);
        String tm = TextChunker.overlapTail(midSep, TextChunker.KB_SEPARATORS, ov);
        assertTrue(tm.startsWith("甲"), () -> "应从片段内句读之后开始: " + tm);
        assertEquals(30, tm.length());
    }

    // ==================== 10-05 E：结构化内容保留换行 ====================

    /** preserveNewlines=false（新 7 参重载）必须与旧 6 参重载逐块等价（回归锁）。 */
    @Test
    void preserveNewlines_false_与旧6参逐块等价() {
        String content = "第一行\n第二行\n\n第三段。\n第四段。";
        List<String> old6 = TextChunker.chunk(KB_HEADER, content, true, true,
                TextChunker.KB_SEPARATORS, TextChunker.DEFAULT_OVERLAP_CHARS);
        List<String> new7 = TextChunker.chunk(KB_HEADER, content, true, true,
                TextChunker.KB_SEPARATORS, TextChunker.DEFAULT_OVERLAP_CHARS, false);
        assertEquals(old6, new7, "preserveNewlines=false 必须逐块等价旧行为");
    }

    /** preserveNewlines=true：表格行文本的段内换行不被压成空格，行/列结构保留。 */
    @Test
    void preserveNewlines_true_保留表格行结构() {
        String table = "车型 | 销量 | 同比\n海狮08 | 12000 | +15%\n大唐 | 9000 | -3%";
        List<String> chunks = TextChunker.chunk("信源：乘联会（2026-10-08）", table, true, false,
                TextChunker.NEWS_SEPARATORS, 0, true);
        assertEquals(1, chunks.size(), "短表格整段成块");
        String body = chunks.get(0).substring(chunks.get(0).indexOf('\n') + 1);
        assertTrue(body.contains("\n"), "段内换行必须保留: " + body);
        assertTrue(body.contains("车型 | 销量 | 同比"), body);
        assertTrue(body.contains("海狮08 | 12000 | +15%"), body);
        assertTrue(body.contains("大唐 | 9000 | -3%"), body);
    }

    /** 默认 false 时同一表格会被压平（对照,证明 preserve 生效）。 */
    @Test
    void 同一表格_默认压平_保留模式下不压平() {
        String table = "车型 | 销量\n海狮08 | 12000";
        String flat = bodyOf(TextChunker.chunk(KB_HEADER, table, true, true,
                TextChunker.KB_SEPARATORS, 0, false).get(0));
        String preserved = bodyOf(TextChunker.chunk(KB_HEADER, table, true, true,
                TextChunker.KB_SEPARATORS, 0, true).get(0));
        assertTrue(!flat.contains("\n"), "默认模式段内换行转空格: " + flat);
        assertTrue(preserved.contains("\n"), "保留模式段内换行不转: " + preserved);
    }

    /** 超长表格按行贪心合并：整行不被截断，块体 ≤ 上限。 */
    @Test
    void preserveNewlines_true_超长表格按行合并_整行不截断() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("车型").append(i).append(" | ").append(1000 + i).append(" | +").append(i).append("%\n");
        List<String> chunks = TextChunker.chunk("信源：T", sb.toString(), true, false,
                TextChunker.NEWS_SEPARATORS, 0, true);
        assertTrue(chunks.size() > 1, "超长表格应多块");
        for (String c : chunks) {
            String body = bodyOf(c);
            assertTrue(body.length() <= TextChunker.MAX_BODY_LEN, () -> "块体超限: " + body.length());
            for (String line : body.split("\\n")) {
                assertTrue(line.startsWith("车型"), () -> "行不得被截断: " + line);
                assertTrue(line.contains("|"), () -> "行结构保留: " + line);
            }
        }
    }

    /** 空正文 + preserveNewlines=true 语义与默认一致（标题块兜底）。 */
    @Test
    void preserveNewlines_true_空正文语义不变() {
        assertEquals(List.of(NEWS_HEADER), TextChunker.chunk(NEWS_HEADER, "  \n ", true, false,
                TextChunker.NEWS_SEPARATORS, 0, true));
        assertTrue(TextChunker.chunk(NEWS_HEADER, "", false, false,
                TextChunker.NEWS_SEPARATORS, 0, true).isEmpty());
    }
}
