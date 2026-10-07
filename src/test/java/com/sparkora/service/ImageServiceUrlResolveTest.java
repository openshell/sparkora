package com.sparkora.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部图片相对 URL 基址解析单测(10-05-source-crawl-base,AC-B10)。
 * 断言跨源相对图链按调用方传入的 detail_base_url 解析,**不硬编码拼 byd.com**;
 * 且 BYD 旧路径(基址为空退 7 参重载)行为不变。
 */
class ImageServiceUrlResolveTest {

    @Test
    void 相对路径_按传入基址解析_非byd域() {
        String abs = ImageService.resolveUrl("/uploads/a.jpg", "https://www.miit.gov.cn");
        assertEquals("https://www.miit.gov.cn/uploads/a.jpg", abs);
        assertTrue(abs.startsWith("https://www.miit.gov.cn"), "域名应为信源基址");
        assertTrue(!abs.contains("byd.com"), "不得拼成 byd.com");
    }

    @Test
    void 无前导斜杠_也按基址补斜杠() {
        assertEquals("https://www.gasgoo.com/img/x.png",
                ImageService.resolveUrl("img/x.png", "https://www.gasgoo.com"));
    }

    @Test
    void 基址带尾斜杠_归一不出现双斜杠() {
        assertEquals("https://www.cpcaauto.com/img/x.png",
                ImageService.resolveUrl("/img/x.png", "https://www.cpcaauto.com/"));
    }

    @Test
    void 绝对链_原样返回() {
        assertEquals("https://cdn.x.com/a.jpg",
                ImageService.resolveUrl("https://cdn.x.com/a.jpg", "https://www.miit.gov.cn"));
        assertEquals("http://cdn.x.com/a.jpg",
                ImageService.resolveUrl("http://cdn.x.com/a.jpg", "https://www.miit.gov.cn"));
    }

    @Test
    void 协议相对链_补https() {
        assertEquals("https://cdn.x.com/a.jpg",
                ImageService.resolveUrl("//cdn.x.com/a.jpg", "https://www.miit.gov.cn"));
    }

    @Test
    void 基址为空_退回byd域_旧路径不回归() {
        assertEquals("https://www.byd.com/cn/a.jpg", ImageService.resolveUrl("/cn/a.jpg", null));
        assertEquals("https://www.byd.com/cn/a.jpg", ImageService.resolveUrl("/cn/a.jpg", ""));
    }
}
