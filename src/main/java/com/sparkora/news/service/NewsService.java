package com.sparkora.news.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.domain.dto.PageResult;
import com.sparkora.domain.entity.NewsEntity;
import com.sparkora.mapper.NewsDocMapper;
import com.sparkora.mapper.NewsMapper;
import com.sparkora.news.client.BydNewsClient;
import com.sparkora.config.NewsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新闻编排服务(C2):抓列表 → 逐条抽正文 → 按官方 news_id 幂等 upsert → 重建切块向量。
 *
 * 幂等:已存在的新闻更新元数据+正文后重建切块(先物理清 embedding+doc 再重切)。
 * 增量:列表按 date 倒序,连续 pageSize 条「已存在且正文非空」即提前停止翻页(FULL 遍历全部页)。
 * 容错:单条抽取/向量化失败记 failedItems 不阻断其余(图片型/无正文新闻仍入库元数据)。
 */
@Slf4j
@Service
public class NewsService {

    /** 官方 date 形如 2026-09-01 17:09:29。 */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NewsProperties props;
    private final BydNewsClient client;
    private final NewsMapper newsMapper;
    private final NewsDocMapper docMapper;
    private final NewsDocService docService;
    private final ObjectMapper json;
    /** 图库服务(09-13 image-tags:封面图下载转存入库,source=byd-news)。 */
    private final com.sparkora.service.ImageService imageService;

    public NewsService(NewsProperties props, BydNewsClient client, NewsMapper newsMapper,
                       NewsDocMapper docMapper, NewsDocService docService, ObjectMapper json,
                       com.sparkora.service.ImageService imageService) {
        this.props = props;
        this.client = client;
        this.newsMapper = newsMapper;
        this.docMapper = docMapper;
        this.docService = docService;
        this.json = json;
        this.imageService = imageService;
    }

    /** 同步结果(成功/失败计数 + 失败明细,随任务落库)。 */
    public record SyncOutcome(int success, int failed, List<Map<String, Object>> failedItems) {}

    /** 全量同步:遍历全部 pages,逐条抓取正文并入库。 */
    public SyncOutcome syncFull() {
        return sync(false, null);
    }

    /** 增量同步:列表按 date 倒序,连续一页「已存在且正文非空」即提前停止。 */
    public SyncOutcome syncIncrement() {
        return sync(true, null);
    }

    /** 带进度回调的同步(异步任务每完成一条回报 success/failed,前端轮询可见)。 */
    public SyncOutcome sync(boolean incremental, java.util.function.BiConsumer<Integer, Integer> onProgress) {
        int page = 1;
        int success = 0;
        int failed = 0;
        int skipped = 0;
        List<Map<String, Object>> failedItems = new ArrayList<>();
        while (true) {
            JsonNode data = client.searchPage(page, props.getPageSize());
            JsonNode records = data.path("records");
            if (!records.isArray() || records.isEmpty()) break;
            boolean pageAllKnown = true;
            for (JsonNode rec : records) {
                String newsId = text(rec, "id");
                if (newsId == null || newsId.isBlank()) continue;
                // 增量:已存在且正文非空 → 跳过重抓(省 167 次详情请求);空正文视为需补抓
                if (incremental && existsWithContent(newsId)) {
                    skipped++;
                    continue;
                }
                pageAllKnown = false;
                try {
                    upsertOne(newsId, rec);
                    success++;
                } catch (Exception e) {
                    failed++;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("newsId", newsId);
                    item.put("title", text(rec, "title"));
                    item.put("error", e.getMessage() == null ? "未知错误" : e.getMessage());
                    failedItems.add(item);
                    log.warn("新闻同步失败 newsId={}: {}", newsId, e.getMessage());
                }
                if (onProgress != null) onProgress.accept(success, failed);
            }
            int totalPages = data.path("pages").asInt(0);
            // 增量:本页全部为「已存在且正文非空」→ 后续页更旧,提前退出
            if (incremental && pageAllKnown) break;
            if (totalPages > 0 && page >= totalPages) break;
            if (totalPages <= 0 && records.size() < props.getPageSize()) break;
            page++;
        }
        log.info("新闻同步完成 incremental={} 成功={} 失败={} 跳过(已存在)={}", incremental, success, failed, skipped);
        return new SyncOutcome(success, failed, failedItems);
    }

    /**
     * 按官方 id 重试失败项:扫描列表页定位记录后逐条重抓(列表仅 ~167 条,全量扫描可接受)。
     * 列表已不存在的失败项记入 failedItems。
     */
    public SyncOutcome retryFailed(List<String> newsIds) {
        java.util.Set<String> pending = new java.util.HashSet<>(newsIds == null ? List.of() : newsIds);
        int success = 0;
        int failed = 0;
        List<Map<String, Object>> failedItems = new ArrayList<>();
        int page = 1;
        while (!pending.isEmpty()) {
            JsonNode data = client.searchPage(page, props.getPageSize());
            JsonNode records = data.path("records");
            if (!records.isArray() || records.isEmpty()) break;
            for (JsonNode rec : records) {
                String id = text(rec, "id");
                if (id == null || !pending.contains(id)) continue;
                try {
                    upsertOne(id, rec);
                    success++;
                } catch (Exception e) {
                    failed++;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("newsId", id);
                    item.put("title", text(rec, "title"));
                    item.put("error", e.getMessage() == null ? "未知错误" : e.getMessage());
                    failedItems.add(item);
                    log.warn("新闻重试失败 newsId={}: {}", id, e.getMessage());
                }
                pending.remove(id);
            }
            int totalPages = data.path("pages").asInt(0);
            if (totalPages > 0 && page >= totalPages) break;
            if (totalPages <= 0 && records.size() < props.getPageSize()) break;
            page++;
        }
        for (String id : pending) {
            failed++;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("newsId", id);
            item.put("error", "列表中已不存在该新闻");
            failedItems.add(item);
        }
        log.info("新闻重试完成 成功={} 失败={}", success, failed);
        return new SyncOutcome(success, failed, failedItems);
    }

    /** 单条新闻:抓详情正文 → 解析 → upsert 主表 → 重建切块向量。
     *  09-13 image-tags:封面图下载走统一入库管线进图库(source=byd-news,标签「新闻」);
     *  单图下载失败仅告警不阻断新闻入库(同车型图容错先例);sparkora_news.image_url 保留原 URL 留痕。 */
    @Transactional
    protected void upsertOne(String newsId, JsonNode rec) {
        String title = text(rec, "title");
        String url = text(rec, "url");
        String imageUrl = text(rec, "imageUrl");
        LocalDateTime publishDate = parseDate(text(rec, "date"));
        String tags = jsonArray(rec.path("tags"));
        String tagNames = jsonArray(rec.path("tagNames"));

        // 详情正文(失败不阻断:元数据仍入库;解析异常已由 parser 降级)
        String content = null;
        try {
            if (url != null && !url.isBlank()) {
                NewsContentParser.Parsed parsed = NewsContentParser.parse(client.fetchDetailHtml(url));
                if (parsed.title() != null && (title == null || title.isBlank())) title = parsed.title();
                if (parsed.publishDate() != null) publishDate = parseDate(parsed.publishDate());
                content = parsed.content();
            }
        } catch (Exception e) {
            log.warn("新闻正文抽取失败 newsId={} url={}: {}", newsId, url, e.getMessage());
        }

        // 封面图转存入库(09-13):失败仅告警,不阻断新闻记录入库
        if (imageUrl != null && !imageUrl.isBlank()) {
            try {
                String ext = imageUrl.contains(".webp") ? "webp" : "jpg";   // 预命名,实际扩展名由魔数嗅探覆盖
                imageService.saveExternalImage(null, imageUrl,
                        "news-" + newsId + "." + ext, "byd-news", List.of("新闻"), "system");
                log.info("新闻封面图已入库 newsId={} imageUrl={}", newsId, shorten(imageUrl));
            } catch (Exception e) {
                log.warn("新闻封面图转存失败(跳过,不阻断) newsId={} imageUrl={}: {}", newsId, imageUrl, e.getMessage());
            }
        }

        NewsEntity n = newsMapper.selectOne(new QueryWrapper<NewsEntity>().eq("news_id", newsId));
        boolean isNew = (n == null);
        if (isNew) n = new NewsEntity();
        n.setNewsId(newsId);
        n.setTitle(title == null || title.isBlank() ? newsId : title);
        n.setUrl(url);
        n.setImageUrl(imageUrl);
        n.setPublishDate(publishDate);
        n.setTags(tags);
        n.setTagNames(tagNames);
        n.setContent(content);
        n.setSource("byd-news");
        n.setSyncStatus("SUCCESS");
        n.setLastSyncAt(LocalDateTime.now());
        n.setLastSyncError(null);
        n.setUpdatedAt(LocalDateTime.now());
        if (isNew) {
            n.setCreatedAt(LocalDateTime.now());
            newsMapper.insert(n);
        } else {
            newsMapper.updateById(n);
        }
        // 重建切块向量(先物理清 embedding+doc,再重切;空正文跳过/仅标题块)
        docService.rebuildForNews(n.getId());
    }

    /** 增量判定:该官方 id 已存在且正文非空(空正文视为需重试,不提前退出)。 */
    public boolean existsWithContent(String newsId) {
        if (newsId == null || newsId.isBlank()) return false;
        NewsEntity n = newsMapper.selectOne(new QueryWrapper<NewsEntity>().eq("news_id", newsId));
        return n != null && n.getContent() != null && !n.getContent().isBlank();
    }

    /** 分页列表(标题模糊;填充块数)。 */
    public PageResult<NewsEntity> list(long page, long size, String keyword) {
        if (page < 1) page = 1;
        if (size < 1 || size > 100) size = 12;
        QueryWrapper<NewsEntity> qw = new QueryWrapper<>();
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) qw.like("title", kw);
        qw.orderByDesc("publish_date").orderByDesc("id");
        Page<NewsEntity> p = newsMapper.selectPage(new Page<>(page, size), qw);
        for (NewsEntity n : p.getRecords()) {
            n.setChunkCount(docMapper.selectCount(new QueryWrapper<com.sparkora.domain.entity.NewsDocEntity>()
                    .eq("news_id", n.getId())));
            n.setContent(null);   // 列表不返回正文大字段(详情接口返回;content 字段 NON_NULL,置空后不出现在 JSON)
        }
        return new PageResult<>(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize());
    }

    /** 详情(含 content)。不存在抛 IllegalArgumentException(控制器映射 404)。 */
    public NewsEntity get(Long id) {
        NewsEntity n = newsMapper.selectById(id);
        if (n == null) throw new IllegalArgumentException("新闻不存在");
        n.setChunkCount(docMapper.selectCount(new QueryWrapper<com.sparkora.domain.entity.NewsDocEntity>()
                .eq("news_id", id)));
        return n;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return s == null || s.isBlank() ? null : s;
    }

    /** 日志用 URL 截断(过长截 100 字符)。 */
    private static String shorten(String s) {
        if (s == null) return "null";
        return s.length() > 100 ? s.substring(0, 100) + "…" : s;
    }

    /** 数组字段序列化为 JSON 字符串;非数组返回 null。 */
    private String jsonArray(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) return null;
        try {
            return json.writeValueAsString(node);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析官方 date(「yyyy-MM-dd HH:mm:ss」或「yyyy-MM-dd」);失败返回 null。 */
    static LocalDateTime parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        try {
            if (s.length() == 10) return LocalDateTime.parse(s + " 00:00:00", DATE_FMT);
            return LocalDateTime.parse(s, DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }
}
