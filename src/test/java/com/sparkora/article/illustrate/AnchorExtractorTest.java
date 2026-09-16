package com.sparkora.article.illustrate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配图锚点切分器单测（09-15 article-auto-illustrate，子C）。
 * 纯静态无 Spring/AI 依赖；覆盖分段、跳过规则、上限截断、指纹稳定性与边界。
 */
class AnchorExtractorTest {

    /** 构造一段足够长（>= MIN_TEXT_LEN 字，去空白）的中文正文。 */
    private static String para(String lead) {
        return lead + "消费税属于流转税，征收环节在生产和进口端，企业缴纳后通常会将税负计入产品定价，最终沿产业链向下传导。";
    }

    // ==================== 分段 ====================

    @Test
    void 多标题分段_前言段与headingPath拼接() {
        String md = String.join("\n",
                "这是标题之前的前言段落，描述文章的背景与写作动机，长度足够参与配图建议的语义检索。",
                "",
                "## 续航实测",
                "",
                para("实测高速工况下的续航表现，"),
                "",
                "### 高速工况",
                "",
                para("高速工况下空调全开的电耗变化，"),
                "",
                "## 价格分析",
                "",
                para("十五万元级别的车型成本分摊，"));

        List<AnchorExtractor.Anchor> anchors = AnchorExtractor.extract(md, 5);

        assertEquals(4, anchors.size());
        assertEquals("", anchors.get(0).headingPath());
        assertEquals("续航实测", anchors.get(1).headingPath());
        assertEquals("续航实测 > 高速工况", anchors.get(2).headingPath());
        assertEquals("价格分析", anchors.get(3).headingPath());
        // anchorIndex 为返回列表序号（保序 0..n-1）
        for (int i = 0; i < anchors.size(); i++) assertEquals(i, anchors.get(i).anchorIndex());
        assertTrue(anchors.get(1).text().startsWith("实测高速工况下"), anchors.get(1).text());
    }

    @Test
    void 同一标题下的多段落合并为一个锚点() {
        String md = "## 电池税\n\n" + para("第一段讲税负传导，") + "\n\n" + para("第二段讲终端定价，");

        List<AnchorExtractor.Anchor> anchors = AnchorExtractor.extract(md, 5);

        assertEquals(1, anchors.size());
        String text = anchors.get(0).text();
        assertTrue(text.contains("第一段讲税负传导"), text);
        assertTrue(text.contains("第二段讲终端定价"), text);
    }

    @Test
    void 无标题退化_按空行切分段落() {
        String md = para("第一段讲背景，") + "\n\n" + para("第二段讲结论，");

        List<AnchorExtractor.Anchor> anchors = AnchorExtractor.extract(md, 5);

        assertEquals(2, anchors.size());
        assertTrue(anchors.stream().allMatch(a -> a.headingPath().isEmpty()));
    }

    @Test
    void H1标题不计入正文且不产生锚点() {
        String md = "# 文章大标题\n\n" + para("正文第一段内容，");

        List<AnchorExtractor.Anchor> anchors = AnchorExtractor.extract(md, 5);

        assertEquals(1, anchors.size());
        assertTrue(anchors.get(0).text().startsWith("正文第一段内容"), anchors.get(0).text());
        assertFalse(anchors.get(0).text().contains("文章大标题"));
    }

    // ==================== 跳过规则 ====================

    @Test
    void 纯列表段落被跳过() {
        String md = String.join("\n",
                "## 参数清单",
                "",
                "- 16核 CPU 与更高带宽的片上互联架构设计说明补充文字",
                "- 273GB/S 内存带宽的实测表现与官方标称数值对照说明",
                "- 单颗搭载三颗芯片的算力冗余设计思路与整车能耗关系",
                "",
                para("这段是普通句式，应该被保留下来参与配图建议，"));

        List<AnchorExtractor.Anchor> anchors = AnchorExtractor.extract(md, 5);

        assertEquals(1, anchors.size());
        assertTrue(anchors.get(0).text().startsWith("这段是普通句式"), anchors.get(0).text());
    }

    @Test
    void 引用块被跳过() {
        String md = String.join("\n",
                "## 官方说法",
                "",
                "> 比亚迪官方表示，该项技术将在明年实现规模化量产并逐步下放到更多车型。",
                "> 这段话是引用，不该配图也不该参与检索。",
                "",
                para("这段是点评文字，应该保留，"));

        List<AnchorExtractor.Anchor> anchors = AnchorExtractor.extract(md, 5);

        assertEquals(1, anchors.size());
        assertFalse(anchors.get(0).text().contains("比亚迪官方表示"), anchors.get(0).text());
    }

    @Test
    void 代码块内容被跳过() {
        String md = String.join("\n",
                "## 代码示例",
                "",
                "```java",
                "public class Foo { /* 这段代码很长但不应参与配图建议检索 */ }",
                "```",
                "",
                para("代码块之后的普通段落，应该保留，"));

        List<AnchorExtractor.Anchor> anchors = AnchorExtractor.extract(md, 5);

        assertEquals(1, anchors.size());
        assertTrue(anchors.get(0).text().startsWith("代码块之后的普通段落"), anchors.get(0).text());
    }

    @Test
    void 图片段落被跳过_不给配图建议区自己推荐() {
        String md = String.join("\n",
                "## 配图",
                "",
                "![](https://pic.example.com/a.jpg)",
                "",
                para("图片之后的普通段落，应该保留，"));

        List<AnchorExtractor.Anchor> anchors = AnchorExtractor.extract(md, 5);

        assertEquals(1, anchors.size());
        assertTrue(anchors.get(0).text().startsWith("图片之后的普通段落"), anchors.get(0).text());
    }

    @Test
    void 过短段落被跳过() {
        String md = String.join("\n",
                "## 短句",
                "",
                "太短了。",
                "",
                para("这段足够长，应该保留，"));

        List<AnchorExtractor.Anchor> anchors = AnchorExtractor.extract(md, 5);

        assertEquals(1, anchors.size());
        assertTrue(anchors.get(0).text().startsWith("这段足够长"), anchors.get(0).text());
    }

    // ==================== 上限与清洗 ====================

    @Test
    void 上限截断_保序取前N个() {
        String md = String.join("\n",
                "## 一", "", para("第一段内容，"),
                "", "## 二", "", para("第二段内容，"),
                "", "## 三", "", para("第三段内容，"),
                "", "## 四", "", para("第四段内容，"));

        List<AnchorExtractor.Anchor> anchors = AnchorExtractor.extract(md, 2);

        assertEquals(2, anchors.size());
        assertEquals("一", anchors.get(0).headingPath());
        assertEquals("二", anchors.get(1).headingPath());
        assertEquals(0, anchors.get(0).anchorIndex());
        assertEquals(1, anchors.get(1).anchorIndex());
    }

    @Test
    void 剔除markdown标记_保留可读文字() {
        String md = "## 加粗标题\n\n这段含有 **加粗重点** 与 [链接文字](https://example.com/a) 以及 `行内代码`，长度足够参与配图建议检索。";

        List<AnchorExtractor.Anchor> anchors = AnchorExtractor.extract(md, 5);

        assertEquals(1, anchors.size());
        String text = anchors.get(0).text();
        assertTrue(text.contains("加粗重点"), text);
        assertTrue(text.contains("链接文字"), text);
        assertTrue(text.contains("行内代码"), text);
        assertFalse(text.contains("**"), text);
        assertFalse(text.contains("https://example.com"), text);
        assertFalse(text.contains("]("), text);
    }

    // ==================== 指纹 ====================

    @Test
    void 指纹稳定_同文本同key() {
        String a = AnchorExtractor.fingerprint("续航实测", para("实测高速工况，"));
        String b = AnchorExtractor.fingerprint("续航实测", para("实测高速工况，"));
        assertEquals(a, b);
        assertEquals(12, a.length());
    }

    @Test
    void 指纹对纯格式调整稳定_加粗与空白不影响() {
        String plain = para("实测高速工况，");
        // 加粗 / 多空格 / 换行（stripMarkdown + 去空白 归一化后内容一致）→ 指纹不变
        String formatted = plain.replace("实测", "**实测**").replace("，", " ， ");
        assertEquals(AnchorExtractor.fingerprint("续航实测", plain),
                AnchorExtractor.fingerprint("续航实测", formatted));
        assertEquals(12, AnchorExtractor.fingerprint("续航实测", plain).length());
    }

    @Test
    void 指纹随文本编辑变化_不同锚点不同key() {
        String base = para("实测高速工况，");
        String edited = para("实测城市工况，");
        assertNotEquals(AnchorExtractor.fingerprint("续航实测", base),
                AnchorExtractor.fingerprint("续航实测", edited));
        assertNotEquals(AnchorExtractor.fingerprint("续航实测", base),
                AnchorExtractor.fingerprint("价格分析", base));
    }

    @Test
    void 指纹对超长文本只取前80字符_尾部编辑不改变指纹() {
        // 前缀归一化后需 > 80 字符，尾部差异才落在窗口之外
        String head = para("实测高速工况，") + "补充说明：电池成本占整车物料成本的三成到四成，税负沿产业链向下传导。" + "再补一句凑足长度。";
        String long1 = head + "尾部内容一：".repeat(30) + "甲";
        String long2 = head + "尾部内容二：".repeat(30) + "乙";
        assertEquals(AnchorExtractor.fingerprint("续航实测", long1),
                AnchorExtractor.fingerprint("续航实测", long2));
        // 反向断言：窗口内（前 80 字符）的差异必须改变指纹
        assertNotEquals(AnchorExtractor.fingerprint("续航实测", head),
                AnchorExtractor.fingerprint("续航实测", "不同开头" + head));
    }

    // ==================== 边界 ====================

    @Test
    void 边界_null空纯空白与非法上限_返回空列表() {
        assertTrue(AnchorExtractor.extract(null, 5).isEmpty());
        assertTrue(AnchorExtractor.extract("", 5).isEmpty());
        assertTrue(AnchorExtractor.extract("   \n\n  \t ", 5).isEmpty());
        assertTrue(AnchorExtractor.extract(para("正文，"), 0).isEmpty());
        assertTrue(AnchorExtractor.extract(para("正文，"), -1).isEmpty());
    }
}
