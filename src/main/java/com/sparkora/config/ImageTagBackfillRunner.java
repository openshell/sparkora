package com.sparkora.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.domain.entity.CarModelEntity;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.domain.entity.NewsEntity;
import com.sparkora.mapper.CarModelMapper;
import com.sparkora.mapper.ImageAssetMapper;
import com.sparkora.mapper.NewsMapper;
import com.sparkora.news.classify.NewsImageClassifier;
import com.sparkora.service.ImageTagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 存量比亚迪图追溯补标（09-13 image-tags 车型图；09-15 img-classify 新闻图）。
 *
 * 背景：
 *  - 车型介绍图早于标签能力入库（source=byd），无「车型-<车型名>」分类；
 *  - 新闻封面图 09-15 前入库只有「新闻」一个标签，无主题细分、无来源关联（source_ref 空）。
 * 本启动任务幂等可重跑（标签走 merge 只插差集），异常仅告警不阻断启动：
 *  - 车型分支：遍历 car_model.intro_images，逐车型 mergeTags「车型-<名>」；
 *  - 新闻分支（09-15）：文件名解析 detail<数字> → 精确匹配 sparkora_news.news_id 后缀 →
 *    用同一分类器补主题/年份标签 + 回填 source_ref 与 sparkora_news.cover_image_id。
 * 零网络成本：新闻分支只反查库内新闻，不重新下载图片。
 *
 * 顺序（09-15 img-semantic-search）：显式 {@code @Order(10)}，必须**早于** {@link ImageEmbeddingBackfillRunner}
 * （{@code @Order(20)}）——图片嵌入文本依赖标签信号，补标须先完成，否则存量图会拿到「无标签」低质向量。
 */
@Slf4j
@Component
@Order(10)
public class ImageTagBackfillRunner implements ApplicationRunner {

    /** 新闻图文件名形如 news-_page_byd-cn_news-2026_detail632.jpg（数字即官方 detail 序号）。 */
    private static final Pattern DETAIL_NO = Pattern.compile("detail(\\d+)");

    /** 单批处理条数（避免一次性把全部 byd-news 图读进内存）。 */
    private static final int BATCH = 200;

    private final CarModelMapper modelMapper;
    private final ImageTagService tagService;
    private final ObjectMapper json;
    private final ImageAssetMapper imageMapper;
    private final NewsMapper newsMapper;

    public ImageTagBackfillRunner(CarModelMapper modelMapper, ImageTagService tagService, ObjectMapper json,
                                  ImageAssetMapper imageMapper, NewsMapper newsMapper) {
        this.modelMapper = modelMapper;
        this.tagService = tagService;
        this.json = json;
        this.imageMapper = imageMapper;
        this.newsMapper = newsMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        backfillCarImages();
        backfillNewsImages();
    }

    /** 车型分支（09-13 行为不变）。 */
    private void backfillCarImages() {
        try {
            List<CarModelEntity> models = modelMapper.selectList(new QueryWrapper<CarModelEntity>().orderByAsc("id"));
            int tagged = 0;
            for (CarModelEntity m : models) {
                if (m.getIntroImages() == null || m.getIntroImages().isBlank()) continue;
                List<Long> ids = parseAssetIds(m.getIntroImages());
                if (ids.isEmpty()) continue;
                try {
                    tagService.mergeTags(ids, List.of("车型-" + m.getName()), "system");
                    tagged++;
                } catch (Exception e) {
                    log.warn("车型图追溯补标失败(跳过) model={} name={}: {}", m.getId(), m.getName(), e.getMessage());
                }
            }
            if (tagged > 0) log.info("图库标签追溯完成:{} 个车型的介绍图已补「车型-<名>」标签", tagged);
        } catch (Exception e) {
            // 启动任务失败不阻断应用启动(标签能力可后续重跑)
            log.warn("车型图标签追溯任务异常(不阻断启动): {}", e.getMessage());
        }
    }

    /**
     * 新闻分支（09-15 img-classify）：只处理 source=byd-news 且 source_ref 为空的图（增量已打标的不重复处理）。
     * 文件名 detail<数字> → news_id 精确后缀匹配（先 LIKE 粗筛候选，再 Java 端精确比对，
     * 防 detail63 误配 detail632）→ 反查新闻 → 分类补标 + 回填 source_ref/cover_image_id。
     */
    private void backfillNewsImages() {
        try {
            int processed = 0;
            int skipped = 0;
            int failed = 0;
            long lastId = 0L;
            while (true) {
                List<ImageAssetEntity> batch = imageMapper.selectList(new QueryWrapper<ImageAssetEntity>()
                        .eq("source", "byd-news")
                        .isNull("source_ref")
                        .gt("id", lastId)
                        .orderByAsc("id")
                        .last("LIMIT " + BATCH));
                if (batch.isEmpty()) break;
                for (ImageAssetEntity img : batch) {
                    lastId = img.getId();
                    try {
                        if (backfillOneNewsImage(img)) processed++;
                        else skipped++;
                    } catch (Exception e) {
                        failed++;
                        log.warn("新闻图追溯失败(跳过) image={} file={}: {}", img.getId(), img.getFileName(), e.getMessage());
                    }
                }
                if (batch.size() < BATCH) break;
            }
            if (processed > 0 || skipped > 0 || failed > 0) {
                log.info("新闻图标签追溯完成:处理={} 跳过={} 失败={}", processed, skipped, failed);
            }
        } catch (Exception e) {
            log.warn("新闻图标签追溯任务异常(不阻断启动): {}", e.getMessage());
        }
    }

    /** 单张新闻图追溯：解析 detail 号 → 精确匹配新闻 → 补主题/年份标签 + 回填来源与封面 id。 */
    private boolean backfillOneNewsImage(ImageAssetEntity img) {
        String detail = detailNoOf(img.getFileName());
        if (detail == null) return false;                 // 文件名无 detail<数字>：跳过（如官方图床命名异常）
        NewsEntity news = findNewsByDetail(detail);
        if (news == null) return false;                   // 库内无对应新闻：跳过（新闻未同步/已删）
        List<String> tags = NewsImageClassifier.toTagsFrom(news.getTitle(), news.getPublishDate());
        tagService.mergeTags(img.getId(), tags, "system");
        imageMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ImageAssetEntity>()
                .eq("id", img.getId())
                .isNull("source_ref")                      // 并发安全：仅当仍为空才写，幂等重跑不覆盖
                .set("source_ref", news.getNewsId()));
        // 回填新闻封面图库 id（仅当新闻侧仍为空，不覆盖已同步的有效值）
        if (news.getCoverImageId() == null) {
            newsMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<NewsEntity>()
                    .eq("id", news.getId())
                    .isNull("cover_image_id")
                    .set("cover_image_id", img.getId()));
        }
        log.debug("新闻图追溯 image={} newsId={} tags={}", img.getId(), news.getNewsId(), tags);
        return true;
    }

    /** 文件名 detail<数字> 提取；无则 null。 */
    static String detailNoOf(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        Matcher m = DETAIL_NO.matcher(fileName);
        return m.find() ? m.group(1) : null;
    }

    /**
     * news_id 是否**精确**以 detail<数字> 结尾（纯函数，可单测）。
     * LIKE 子串匹配会把 detail63 误配 detail632，故必须先粗筛再精确判定后缀。
     */
    static boolean matchesDetail(String newsId, String detail) {
        if (newsId == null || detail == null || detail.isBlank()) return false;
        return newsId.endsWith("detail" + detail);
    }

    /**
     * 按 detail 号反查新闻：先 LIKE 粗筛（news_id 以 %detail<数字> 结尾的候选），
     * 再 Java 端精确比对后缀——LIKE 子串匹配会把 detail63 误配 detail632，
     * 必须精确判定（同 ImageService.delete 的引用检查教训）。
     */
    private NewsEntity findNewsByDetail(String detail) {
        String suffix = "detail" + detail;
        List<NewsEntity> candidates = newsMapper.selectList(new QueryWrapper<NewsEntity>()
                .like("news_id", suffix));
        for (NewsEntity n : candidates) {
            if (matchesDetail(n.getNewsId(), detail)) return n;
        }
        return null;
    }

    /**
     * 解析 car_model.intro_images 为 asset id 列表。
     * 兼容存量：旧格式可能是 URL 字符串数组（非数字），解析不出数字的项跳过。
     */
    private List<Long> parseAssetIds(String raw) {
        List<Long> ids = new ArrayList<>();
        try {
            JsonNode arr = json.readTree(raw);
            if (!arr.isArray()) return ids;
            for (JsonNode node : arr) {
                String s = node.asText();
                if (s == null || s.isBlank()) continue;
                try {
                    ids.add(Long.valueOf(s.trim()));
                } catch (NumberFormatException ignore) {
                    // 存量 URL 字符串：非 asset id，跳过（不做下载追溯，范围外）
                }
            }
        } catch (Exception e) {
            log.debug("intro_images 解析失败(跳过) raw={}: {}", raw, e.getMessage());
        }
        return ids;
    }
}
