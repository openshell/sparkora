package com.sparkora.domain.dto;

/**
 * 问答答案配图条目（09-15 qa-auto-illustrate，子D）。
 *
 * 两条来源路径汇入同一结构（见 {@code com.sparkora.qa.service.QaImageRefService}）：
 * <ul>
 *   <li><b>新闻关联图（便宜路径）</b>：答案引用了 NEWS 知识块 → 经 {@code Citation.docId}
 *       （{@code sparkora_news_doc.id}）→ 内部 news_id → {@code sparkora_news.cover_image_id} → 图库资产。
 *       {@code title}=新闻标题，{@code newsId}=官方 news_id 字符串。</li>
 *   <li><b>语义检索图（语义路径）</b>：问题命中图片意图词 → 图片向量检索（子B）。
 *       {@code title}=嵌入原文首段或文件名，{@code newsId}=null。</li>
 * </ul>
 *
 * <b>只读展示</b>：本结构仅用于「答案下方缩略图行」，不参与任何写入用户内容的路径
 * （不插入正文、不改答案文本）；无批准流程（区别于子C「写入正文须批准」）。
 *
 * @param imageId  图库资产 id（{@code sparkora_image_asset.id}）
 * @param url      图床公网原图 URL（预览大图必须用本字段，thumbUrl 是 webp 派生）
 * @param thumbUrl 缩略图 URL（七牛 imageView2/webp 派生；非七牛降级为原图 url）
 * @param title    展示标题（新闻关联图=新闻标题；语义图=嵌入原文首段或文件名）
 * @param newsId   官方新闻 id 字符串（仅新闻关联图非空；语义图为 null）
 * @param source   图片来源（upload/ai-text2img/ai-img2img/byd/byd-news）
 */
public record QaImageRef(Long imageId,
                         String url,
                         String thumbUrl,
                         String title,
                         String newsId,
                         String source) {
}
