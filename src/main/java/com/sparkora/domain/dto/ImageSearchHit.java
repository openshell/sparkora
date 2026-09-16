package com.sparkora.domain.dto;

import java.util.List;

/**
 * 图片语义检索命中项（09-15 img-semantic-search，子B）。
 *
 * 由「向量检索行（imageId/score/sourceText）」+「图库主表（fileName/source/sourceRef）」+
 * 「标签服务（tags）」+「图床派生（url/thumbUrl）」四路拼成，供子C（文章自动配图）与子D（问答配图）消费。
 *
 * @param imageId    图库资产 id（sparkora_image_asset.id）
 * @param score      余弦相似度（越大越相关；已按门槛过滤）
 * @param sourceText 该图入库时的嵌入原文（调试/可解释性用）
 * @param fileName   文件名（原始文件名或 prompt 摘要命名）
 * @param source     图片来源（upload/ai-text2img/ai-img2img/byd/byd-news）
 * @param sourceRef  来源引用串（新闻图 = 官方 news_id；其他来源可空）
 * @param url        图床公网原图 URL
 * @param thumbUrl   缩略图 URL（七牛 imageView2/webp 派生；非七牛降级为原图 url）
 * @param tags       标签名列表（按名称排序；无标签为空列表）
 */
public record ImageSearchHit(Long imageId,
                             double score,
                             String sourceText,
                             String fileName,
                             String source,
                             String sourceRef,
                             String url,
                             String thumbUrl,
                             List<String> tags) {
}
