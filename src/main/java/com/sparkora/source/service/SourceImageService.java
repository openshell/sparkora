package com.sparkora.source.service;

import com.sparkora.service.ImageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 采集信源正文配图转存(10-05-source-crawl-base,B-R10/父 design §5.5)。
 *
 * <p>把采集详情解析出的正文图片逐张经 {@link ImageService#saveExternalImage} 转存图库,使采集信源配图
 * 可作文章配图素材。复用既有图库设施,<b>不新建图库</b>。
 *
 * <ul>
 *   <li><b>相对 URL 按栏目 {@code detail_base_url} 解析</b>(不是硬编码 byd.com)——跨源相对图链才不会拼错域;</li>
 *   <li>图库来源值 {@code source="source"},{@code sourceRef}=派生 news_id,供 E 反查标题;</li>
 *   <li><b>单图失败 warn 跳过,不阻断本条/本任务</b>(照 {@code NewsService} 封面图容错);</li>
 *   <li>不做视频入库/反盗链/水印/版权审核。</li>
 * </ul>
 */
@Slf4j
@Service
public class SourceImageService {

    private final ImageService imageService;

    public SourceImageService(ImageService imageService) {
        this.imageService = imageService;
    }

    /**
     * 批量转存正文配图。
     *
     * @param imageUrls     原始图片链(相对/绝对,可空)
     * @param detailBaseUrl 相对链解析基址(栏目 detail_base_url,可空则退回 byd 域)
     * @param newsId        该内容派生 news_id(sourceRef,供反查标题)
     * @param title         来源标题(标签信号)
     * @param category      来源分类(标签信号,可空)
     * @return 成功转存的图片数
     */
    public int transfer(List<String> imageUrls, String detailBaseUrl, String newsId, String title, String category) {
        if (imageUrls == null || imageUrls.isEmpty()) return 0;
        List<String> tags = buildTags(title, category);
        int ok = 0;
        int i = 0;
        for (String raw : imageUrls) {
            if (raw == null || raw.isBlank()) continue;
            i++;
            try {
                String fileName = "source-" + safeRef(newsId) + "-" + i + ".jpg";
                imageService.saveExternalImage(null, raw, detailBaseUrl, fileName, "source", tags, "system", newsId);
                ok++;
            } catch (Exception e) {
                log.warn("信源配图转存失败(跳过,不阻断) newsId={} url={}: {}", newsId, raw, e.getMessage());
            }
        }
        return ok;
    }

    /** 标签信号:标题 + 来源分类(非空则并入)。 */
    static List<String> buildTags(String title, String category) {
        List<String> tags = new ArrayList<>();
        if (title != null && !title.isBlank()) tags.add(title.trim());
        if (category != null && !category.isBlank()) tags.add(category.trim());
        return tags;
    }

    /** sourceRef 归一(用于文件名,去除路径分隔符避免污染)。 */
    private static String safeRef(String ref) {
        if (ref == null || ref.isBlank()) return "0";
        return ref.replaceAll("[^0-9A-Za-z._:-]", "_");
    }
}
