package com.sparkora.source.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sparkora.domain.entity.NewsEntity;
import com.sparkora.domain.entity.SourceChannelEntity;
import com.sparkora.domain.entity.SourceEntity;
import com.sparkora.mapper.NewsMapper;
import com.sparkora.mapper.SourceChannelMapper;
import com.sparkora.mapper.SourceMapper;
import com.sparkora.source.client.SourceClient;
import com.sparkora.source.client.SourceContent;
import com.sparkora.source.client.SourceItem;
import com.sparkora.source.fetch.FetchOptions;
import com.sparkora.source.fetch.FetchResult;
import com.sparkora.source.fetch.FetchTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用信源采集编排(10-05-source-crawl-base)。遍历源的 enabled channels → 列表抓取 → 详情抓取 →
 * 规范化去重 → 幂等 upsert 到 {@code sparkora_news}(共享 domain=NEWS id 空间,父 design §2.1)。
 *
 * <p><b>幂等键</b> = {@code (sourceId, channelId, externalId)}:非 BYD {@code news_id} 由
 * {@code <sourceId>:<channelId>:<externalId>} 派生,满足既有 UNIQUE 且同站不同栏目独立去重。
 * <b>抓取经 C 的 {@link FetchTransport}</b>(HTTP/Crawl4AI);B 级栏目未就绪(Crawl4AI 未配置)时
 * 降级跳过并记原因,不 fallback 到 HTTP 硬闯。
 * <b>容错</b>:单条详情失败进 failed_items 不阻断;正文空仍入库元数据。
 */
@Slf4j
@Service
public class SourceCollectService {

    /** A 级站点直连 UA(调研报告:curl 真实浏览器 UA 可直连)。 */
    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/148.0 Safari/537.36";

    private final SourceMapper sourceMapper;
    private final SourceChannelMapper channelMapper;
    private final NewsMapper newsMapper;
    private final Map<FetchTransport.Kind, FetchTransport> transports = new HashMap<>();
    private final Map<String, SourceClient> clients = new HashMap<>();
    private final SourceImageService imageService;

    public SourceCollectService(SourceMapper sourceMapper, SourceChannelMapper channelMapper, NewsMapper newsMapper,
                               List<FetchTransport> transportList, List<SourceClient> clientList,
                               SourceImageService imageService) {
        this.sourceMapper = sourceMapper;
        this.channelMapper = channelMapper;
        this.newsMapper = newsMapper;
        this.imageService = imageService;
        for (FetchTransport t : transportList) transports.put(t.kind(), t);
        for (SourceClient c : clientList) clients.put(c.type().toUpperCase(), c);
    }

    /** 采集结果(成功/失败/降级计数 + 失败明细)。 */
    public record CollectOutcome(int success, int failed, int degraded, List<Map<String, Object>> failedItems) {}

    /**
     * 采集指定源(可限定单个栏目)。
     *
     * @param sourceId  源 id
     * @param channelId 栏目 id(可空=该源全部 enabled 栏目)
     * @return 采集结果(不抛:源不存在抛 IllegalArgumentException 供控制器映射)
     */
    public CollectOutcome collect(Long sourceId, Long channelId) {
        SourceEntity source = sourceMapper.selectById(sourceId);
        if (source == null) throw new IllegalArgumentException("信源不存在");
        List<SourceChannelEntity> channels = enabledChannels(sourceId, channelId);
        if (channels.isEmpty()) {
            log.info("信源 {} 无启用的栏目,跳过采集", sourceId);
            return new CollectOutcome(0, 0, 0, List.of());
        }
        int success = 0;
        int failed = 0;
        int degraded = 0;
        List<Map<String, Object>> failedItems = new ArrayList<>();
        for (SourceChannelEntity ch : channels) {
            ChannelOutcome r = collectChannel(source, ch);
            success += r.success();
            failed += r.failed();
            degraded += r.degraded();
            failedItems.addAll(r.failedItems());
        }
        log.info("信源采集完成 sourceId={} 栏目数={} 成功={} 失败={} 降级={}",
                sourceId, channels.size(), success, failed, degraded);
        return new CollectOutcome(success, failed, degraded, failedItems);
    }

    /** 单栏目采集:列表抓取 → 逐条详情 → upsert。 */
    private ChannelOutcome collectChannel(SourceEntity source, SourceChannelEntity ch) {
        int success = 0;
        int failed = 0;
        int degraded = 0;
        List<Map<String, Object>> failedItems = new ArrayList<>();

        boolean needCrawl = needCrawl4ai(source, ch);
        FetchTransport transport = needCrawl
                ? transports.get(FetchTransport.Kind.CRAWL4AI)
                : transports.get(FetchTransport.Kind.HTTP);
        if (transport == null) {
            return new ChannelOutcome(0, 0, 1, List.of(degradedItem(ch, "抓取通道未装配")));
        }
        if (!transport.configured()) {
            log.warn("信源栏目 {} 需 Crawl4AI 但通道未配置,降级跳过", ch.getId());
            return new ChannelOutcome(0, 0, 1, List.of(degradedItem(ch, "Crawl4AI 未配置")));
        }

        SourceClient client = clients.get(source.getType() == null ? "" : source.getType().toUpperCase());
        if (client == null) {
            return new ChannelOutcome(0, 1, 0, List.of(failedItem(ch, null, "不支持的源类型: " + source.getType())));
        }

        FetchResult listRes = fetch(transport, ch.getListUrl());
        if (!listRes.ok()) {
            // 被限流/未配置 → 降级(不失败整个任务);其余抓取失败记失败明细
            if (listRes.limited() || "UNCONFIGURED".equals(listRes.error())) {
                return new ChannelOutcome(0, 0, 1, List.of(degradedItem(ch, "列表抓取降级: " + listRes.error())));
            }
            return new ChannelOutcome(0, 1, 0, List.of(failedItem(ch, null, "列表抓取失败: " + listRes.error())));
        }

        List<SourceItem> items = client.list(listRes.html(), ch);
        for (SourceItem item : items) {
            try {
                upsertOne(source, ch, client, transport, item);
                success++;
            } catch (Exception e) {
                failed++;
                failedItems.add(failedItem(ch, item.externalId(), e.getMessage() == null ? "未知错误" : e.getMessage()));
                log.warn("信源条目采集失败 source={} channel={} externalId={}: {}",
                        source.getId(), ch.getId(), item.externalId(), e.getMessage());
            }
        }
        return new ChannelOutcome(success, failed, degraded, failedItems);
    }

    /** 单条:抓详情 → 解析正文(表格转行文本) → 配图转存 → 幂等 upsert。 */
    private void upsertOne(SourceEntity source, SourceChannelEntity ch, SourceClient client,
                           FetchTransport transport, SourceItem item) {
        String content = null;
        SourceContent parsed = null;
        String detailHtml = null;
        if (item.url() != null && !item.url().isBlank()) {
            FetchResult detailRes = fetch(transport, item.url());
            if (detailRes.ok()) {
                detailHtml = detailRes.html();
            } else {
                log.warn("信源详情抓取失败(元数据仍入库) externalId={} error={}", item.externalId(), detailRes.error());
            }
        }
        // 解析详情:RSS 用条目自带 summary(detailHtml 可为 null);SITE 用抓到的详情 HTML
        parsed = client.detail(detailHtml, ch, item);
        content = parsed.text();

        String newsId = derivedNewsId(source.getId(), ch.getId(), item.externalId());
        LocalDateTime publishDate = parseDate(item.publishDate());
        LocalDateTime now = LocalDateTime.now();

        // 配图转存(单图失败 warn 跳过,不阻断本条)
        if (parsed != null && !parsed.imageUrls().isEmpty()) {
            imageService.transfer(parsed.imageUrls(), ch.getDetailBaseUrl(), newsId, item.title(), ch.getCategory());
        }

        NewsEntity existing = newsMapper.selectOne(new QueryWrapper<NewsEntity>().eq("news_id", newsId));
        boolean isNew = existing == null;
        NewsEntity n = isNew ? new NewsEntity() : existing;
        n.setNewsId(newsId);
        n.setTitle(item.title() == null || item.title().isBlank() ? item.externalId() : item.title());
        n.setUrl(item.url());
        n.setPublishDate(publishDate);
        n.setContent(content);
        n.setSource("source");
        n.setSourceId(source.getId());
        n.setChannelId(ch.getId());
        n.setCategory(ch.getCategory());
        n.setSyncStatus("SUCCESS");
        n.setLastSyncAt(now);
        n.setLastSyncError(null);
        n.setUpdatedAt(now);
        if (isNew) {
            n.setCreatedAt(now);
            newsMapper.insert(n);
        } else {
            newsMapper.updateById(n);
        }
    }

    /** 派生 news_id(满足 UNIQUE):同源同栏目同 externalId 幂等,跨源/跨栏目不撞。 */
    static String derivedNewsId(Long sourceId, Long channelId, String externalId) {
        return sourceId + ":" + channelId + ":" + externalId;
    }

    private List<SourceChannelEntity> enabledChannels(Long sourceId, Long channelId) {
        QueryWrapper<SourceChannelEntity> qw = new QueryWrapper<SourceChannelEntity>()
                .eq("source_id", sourceId).eq("enabled", true);
        if (channelId != null) qw.eq("id", channelId);
        qw.orderByAsc("id");
        return channelMapper.selectList(qw);
    }

    /** 栏目是否需 Crawl4AI:栏目级非空则覆盖源级默认。 */
    static boolean needCrawl4ai(SourceEntity source, SourceChannelEntity ch) {
        if (ch.getNeedCrawl4ai() != null) return ch.getNeedCrawl4ai();
        return source.getNeedCrawl4ai() != null && source.getNeedCrawl4ai();
    }

    private FetchResult fetch(FetchTransport transport, String url) {
        if (url == null || url.isBlank()) return FetchResult.failure(url, "EMPTY_URL");
        return transport.fetch(url, new FetchOptions("GET", Map.of("User-Agent", UA), true, 0L));
    }

    private static Map<String, Object> failedItem(SourceChannelEntity ch, String externalId, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channelId", ch.getId());
        m.put("channelName", ch.getName());
        m.put("externalId", externalId);
        m.put("error", error);
        return m;
    }

    private static Map<String, Object> degradedItem(SourceChannelEntity ch, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channelId", ch.getId());
        m.put("channelName", ch.getName());
        m.put("externalId", null);
        m.put("error", reason);
        m.put("degraded", true);
        return m;
    }

    private record ChannelOutcome(int success, int failed, int degraded, List<Map<String, Object>> failedItems) {}

    /**
     * 发布日期解析(尽最大努力,失败返回 null):支持
     * {@code yyyy-MM-dd[ HH:mm[:ss]]} / {@code yyyy/MM/dd} / ISO_OFFSET / RFC_1123(RSS)。
     */
    static LocalDateTime parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        try {
            if (s.length() == 10 && s.charAt(4) == '-') return LocalDate.parse(s).atStartOfDay();
            if (s.length() == 10 && s.charAt(4) == '/') {
                return LocalDate.parse(s, DateTimeFormatter.ofPattern("yyyy/MM/dd")).atStartOfDay();
            }
            for (DateTimeFormatter f : new DateTimeFormatter[]{
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
                    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")}) {
                try {
                    return LocalDateTime.parse(s, f);
                } catch (Exception ignore) {
                    // 继续尝试
                }
            }
            try {
                // RSS(RFC_1123):先剥可选的开头星期(部分 feed 星期与日期不自洽,RFC 解析器会因冲突拒绝)
                String rss = s.replaceFirst("^[A-Za-z]{3},\\s*", "");
                DateTimeFormatter rfc = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss zzz",
                        java.util.Locale.ENGLISH);
                return java.time.ZonedDateTime.parse(rss, rfc).toLocalDateTime();
            } catch (Exception ignore) {
                // 继续尝试
            }
            // ISO_OFFSET(如 2026-10-08T09:00:00Z / +08:00)
            try {
                return java.time.OffsetDateTime.parse(s).toLocalDateTime();
            } catch (Exception ignore) {
                // 落到 null
            }
        } catch (Exception e) {
            // 忽略
        }
        return null;
    }
}
