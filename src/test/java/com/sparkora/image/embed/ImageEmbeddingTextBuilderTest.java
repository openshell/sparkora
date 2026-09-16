package com.sparkora.image.embed;

import com.sparkora.domain.entity.ImageAssetEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片嵌入文本构造器纯函数单测（09-15 img-semantic-search，不连库）。
 * 覆盖：四来源分派、标签拼接、新闻标题缺失退化、全空兜底、超长截断、null 安全。
 */
class ImageEmbeddingTextBuilderTest {

    private static ImageAssetEntity img(String source, String fileName, String prompt) {
        ImageAssetEntity e = new ImageAssetEntity();
        e.setId(37L);
        e.setSource(source);
        e.setFileName(fileName);
        e.setPromptText(prompt);
        return e;
    }

    @Test
    void 新闻图_标题优先并拼标签() {
        ImageAssetEntity e = img("byd-news", "news-_page_byd-cn_news-2026_detail632.jpg", null);
        String text = ImageEmbeddingTextBuilder.build(e,
                List.of("新闻", "主题/销量", "主题/出海", "年份/2026"), "比亚迪7月份销售41.9万辆");
        assertEquals("比亚迪7月份销售41.9万辆 新闻 主题/销量 主题/出海 年份/2026", text);
    }

    @Test
    void ai图_prompt加标签() {
        assertEquals("赛博朋克城市夜景 主题/技术发布",
                ImageEmbeddingTextBuilder.build(img("ai-text2img", "cyber-.png", "赛博朋克城市夜景"),
                        List.of("主题/技术发布"), null));
        assertEquals("水墨山水 中国风",
                ImageEmbeddingTextBuilder.build(img("ai-img2img", "ink.webp", "水墨山水"),
                        List.of("中国风"), null));
    }

    @Test
    void 上传图_文件名去扩展名加标签() {
        assertEquals("出海签约现场 主题/合作签约",
                ImageEmbeddingTextBuilder.build(img("upload", "出海签约现场.jpg", null),
                        List.of("主题/合作签约"), null));
    }

    @Test
    void 车型图_文件名加车型标签() {
        assertEquals("海豹-1 车型-海豹",
                ImageEmbeddingTextBuilder.build(img("byd", "海豹-1.png", null),
                        List.of("车型-海豹"), null));
    }

    @Test
    void 新闻标题缺失_退化为只用标签() {
        ImageAssetEntity e = img("byd-news", "news-...detail632.jpg", null);
        assertEquals("新闻 主题/销量 年份/2026",
                ImageEmbeddingTextBuilder.build(e, List.of("新闻", "主题/销量", "年份/2026"), null));
        // 标题为空白字符串同样退化（不产生空段）
        assertEquals("主题/销量",
                ImageEmbeddingTextBuilder.build(e, List.of("主题/销量"), "   "));
    }

    @Test
    void 全空兜底_仍产出非空文本() {
        ImageAssetEntity e = img("upload", null, null);
        assertEquals("(图片 37)", ImageEmbeddingTextBuilder.build(e, List.of(), null));
        assertEquals("(图片 37)", ImageEmbeddingTextBuilder.build(e, null, null));
        assertEquals("(图片 37)", ImageEmbeddingTextBuilder.build(e, List.of("  ", ""), " "));
        // 纯数字文件名（去扩展名后非空）不触发兜底——由检索侧门槛过滤低质向量
        assertEquals("123456",
                ImageEmbeddingTextBuilder.build(img("upload", "123456.png", null), null, null));
    }

    @Test
    void 超长截断到2000字符() {
        String longTitle = "销".repeat(3000);
        String text = ImageEmbeddingTextBuilder.build(img("byd-news", "a.jpg", null),
                List.of("主题/销量"), longTitle);
        assertEquals(ImageEmbeddingTextBuilder.MAX_TEXT_LEN, text.length());
        assertTrue(text.startsWith("销"));
    }

    @Test
    void null入参安全() {
        assertEquals("(图片 )", ImageEmbeddingTextBuilder.build(null, null, null));
        // 标签含 null 项被丢弃
        assertEquals("a 标签",
                ImageEmbeddingTextBuilder.build(img("upload", "a.png", null),
                        java.util.Arrays.asList(null, "标签", "  "), null));
        assertFalse(ImageEmbeddingTextBuilder.build(img("other", "x.png", null), List.of(), null).isEmpty());
    }

    @Test
    void 文件名去扩展名边界() {
        assertEquals("a", ImageEmbeddingTextBuilder.baseName("a.png"));
        assertEquals("noext", ImageEmbeddingTextBuilder.baseName("noext"));
        assertEquals("b", ImageEmbeddingTextBuilder.baseName("dir/sub/b.jpg"));
        assertEquals(".hidden", ImageEmbeddingTextBuilder.baseName(".hidden"));
        assertEquals("a.b", ImageEmbeddingTextBuilder.baseName("a.b.png"));
        org.junit.jupiter.api.Assertions.assertNull(ImageEmbeddingTextBuilder.baseName(null));
        org.junit.jupiter.api.Assertions.assertNull(ImageEmbeddingTextBuilder.baseName("   "));
    }
}
