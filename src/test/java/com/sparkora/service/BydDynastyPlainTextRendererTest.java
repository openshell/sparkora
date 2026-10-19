package com.sparkora.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BYD 小程序纯文本渲染契约测试：只验证 Markdown -> 纯文本/图片块的转换，不接触数据库或 MMKV。
 */
class BydDynastyPlainTextRendererTest {

    private static List<String> texts(String markdown) {
        return BydDynastyPlainTextRenderer.compile(markdown).stream()
                .filter(block -> block instanceof BydDynastyPlainTextRenderer.TextBlock)
                .map(block -> ((BydDynastyPlainTextRenderer.TextBlock) block).text())
                .toList();
    }

    private static String singleText(String markdown) {
        List<String> texts = texts(markdown);
        assertEquals(1, texts.size(), () -> "期望单个文本块，实际: " + texts);
        return texts.get(0);
    }

    @Test
    void 标题粗体斜体删除线行内代码和代码块只保留文字() {
        String text = singleText("""
                # 一级标题

                **粗体** *斜体* ~~删除线~~ `inlineCode`

                ```java
                int a = 1;
                ```
                """);

        assertTrue(text.contains("一级标题"));
        assertTrue(text.contains("粗体"));
        assertTrue(text.contains("斜体"));
        assertTrue(text.contains("删除线"));
        assertTrue(text.contains("inlineCode"));
        assertTrue(text.contains("int a = 1;"));
        assertFalse(text.contains("**"));
        assertFalse(text.contains("~~"));
        assertFalse(text.contains("`"));
        assertFalse(text.contains("# "));
    }

    @Test
    void 代码内容中的Markdown字符原样保留() {
        assertEquals("**不是粗体**", singleText("`**不是粗体**`"));
        assertTrue(singleText("```\n# 不是标题\n- 不是列表\n```").contains("# 不是标题"));
    }

    @Test
    void 链接只保留文字且裸URL不进入正文() {
        String text = singleText("见[官方说明](https://example.com/docs)，裸链接 https://example.com/bare 和 [https://example.com](https://example.com)");

        assertTrue(text.contains("官方说明"));
        assertFalse(text.contains("https://"));
        assertFalse(text.contains("example.com"));
        assertFalse(text.contains("docs"));
    }

    @Test
    void 无序列表使用圆点有序列表保留编号且支持嵌套() {
        String text = singleText("""
                - 第一
                - 第二
                  - 嵌套

                1. 甲
                2. 乙
                """);

        assertTrue(text.contains("• 第一"));
        assertTrue(text.contains("• 第二"));
        assertTrue(text.lines().anyMatch(line -> line.matches("\\s+• 嵌套")));
        assertTrue(text.contains("1. 甲"));
        assertTrue(text.contains("2. 乙"));
        assertFalse(text.contains("- 第一"));
        assertFalse(text.contains("* 第一"));
    }

    @Test
    void 引用不输出大于号() {
        String text = singleText("> 引用第一行\n> 引用第二行");

        assertTrue(text.contains("引用第一行"));
        assertTrue(text.contains("引用第二行"));
        assertFalse(text.contains(">"));
    }

    @Test
    void GFM表格转换为列名和值的中文可读格式() {
        String text = singleText("""
                | 名称 | 价格 |
                | --- | --- |
                | 苹果 | 3 |
                | 香蕉 | 5 |
                """);

        assertTrue(text.contains("名称：苹果；价格：3"), text);
        assertTrue(text.contains("名称：香蕉；价格：5"), text);
        assertFalse(text.contains("|"));
        assertFalse(text.contains("---"));
    }

    @Test
    void HTML标签被剥离实体解码且危险节点不进入正文() {
        String text = singleText("""
                第一行<br>第二行

                <div>块一</div><div>块二</div>

                <script>alert(1)</script><style>.x{color:red}</style>

                A &amp; B
                """);

        assertTrue(text.contains("第一行"));
        assertTrue(text.contains("第二行"));
        assertTrue(text.contains("块一"));
        assertTrue(text.contains("块二"));
        assertTrue(text.contains("A & B"));
        assertFalse(text.contains("<"));
        assertFalse(text.contains("alert"));
        assertFalse(text.contains("color:red"));
    }

    @Test
    void 原始HTML图片不会变成图片块() {
        List<BydDynastyPlainTextRenderer.Block> blocks =
                BydDynastyPlainTextRenderer.compile("<img src=\"https://evil.example/x.png\">");

        assertTrue(blocks.stream().noneMatch(block -> block instanceof BydDynastyPlainTextRenderer.ImageBlock));
    }

    @Test
    void emoji组合序列和中文标点原样保留() {
        assertEquals("🚩 完成 ✅ 👨‍👩‍👧‍👦", singleText("🚩 完成 ✅ 👨‍👩‍👧‍👦"));
        assertTrue(singleText("你好，世界！").contains("，"));
    }

    @Test
    void 图片按出现顺序拆成独立块且替代文字不进入正文() {
        List<BydDynastyPlainTextRenderer.Block> blocks = BydDynastyPlainTextRenderer.compile(
                "前\n\n![第一张](https://img.example/a.png)\n\n中\n\n![第二张](https://img.example/b.png)\n\n后");

        assertEquals(5, blocks.size(), blocks.toString());
        assertEquals(new BydDynastyPlainTextRenderer.TextBlock("前"), blocks.get(0));
        assertEquals(new BydDynastyPlainTextRenderer.ImageBlock("https://img.example/a.png"), blocks.get(1));
        assertEquals(new BydDynastyPlainTextRenderer.TextBlock("中"), blocks.get(2));
        assertEquals(new BydDynastyPlainTextRenderer.ImageBlock("https://img.example/b.png"), blocks.get(3));
        assertEquals(new BydDynastyPlainTextRenderer.TextBlock("后"), blocks.get(4));
        assertFalse(blocks.toString().contains("第一张"));
        assertFalse(blocks.toString().contains("第二张"));
    }

    @Test
    void 同一段落中的图片也能保持前后文本顺序() {
        List<BydDynastyPlainTextRenderer.Block> blocks =
                BydDynastyPlainTextRenderer.compile("文字前![图](https://img.example/a.png)文字后");

        assertEquals(3, blocks.size(), blocks.toString());
        assertEquals(new BydDynastyPlainTextRenderer.TextBlock("文字前"), blocks.get(0));
        assertEquals(new BydDynastyPlainTextRenderer.ImageBlock("https://img.example/a.png"), blocks.get(1));
        assertEquals(new BydDynastyPlainTextRenderer.TextBlock("文字后"), blocks.get(2));
    }

    @Test
    void 空输入返回空列表而纯图片输入只返回图片块() {
        assertTrue(BydDynastyPlainTextRenderer.compile("").isEmpty());
        assertTrue(BydDynastyPlainTextRenderer.compile("   \n\n").isEmpty());
        assertEquals(List.of(new BydDynastyPlainTextRenderer.ImageBlock("https://img.example/a.png")),
                BydDynastyPlainTextRenderer.compile("![图](https://img.example/a.png)"));
    }

    @Test
    void 任务列表输出勾选标记() {
        String text = singleText("- [x] 已完成\n- [ ] 未完成");

        assertTrue(text.contains("☑ 已完成"), text);
        assertTrue(text.contains("☐ 未完成"), text);
        assertFalse(text.contains("[x]"));
        assertFalse(text.contains("[ ]"));
    }

    @Test
    void 连续空行压缩为最多一个空行并统一换行符() {
        assertEquals("A\n\nB", singleText("A\r\n\r\n\r\n\r\nB"));
    }

    @Test
    void 标题行内渲染去除标记换行和裸露URL() {
        assertEquals("标题 副标题", BydDynastyPlainTextRenderer.renderInline("# 标题\n\n**副标题**"));
        assertEquals("说明 链接文字", BydDynastyPlainTextRenderer.renderInline("说明 [链接文字](https://example.com)"));
        assertEquals("无URL", BydDynastyPlainTextRenderer.renderInline("无URL https://example.com"));
        assertFalse(BydDynastyPlainTextRenderer.renderInline("![图](https://img.example/a.png)").contains("a.png"));
    }
}
