package com.sparkora.news.service;

import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.domain.entity.NewsDocEntity;
import com.sparkora.domain.entity.NewsEntity;
import com.sparkora.mapper.NewsDocEmbeddingMapper;
import com.sparkora.mapper.NewsDocMapper;
import com.sparkora.mapper.NewsMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 新闻切块 + 向量化服务(C2,仿 CarDocService / KbDocService)。
 *
 * 流程:先物理清旧块+向量,再切块(首行「新闻：<title>（<publishDate>）」)→ 逐块 embedding 入库。
 * embedding 并发化(固定小线程池)+ 单块失败重试 1 次;失败块 warn 计数,不静默。
 * 空正文(图片型新闻)跳过切块(仅元数据入库);若标题存在则保留单块标题锚点,便于按标题检索。
 */
@Slf4j
@Service
public class NewsDocService {

    /** 单块正文目标上限(不含首行标题),与 KbDocService.MAX_BODY_LEN 对齐。 */
    static final int MAX_BODY_LEN = 500;

    private final NewsMapper newsMapper;
    private final NewsDocMapper docMapper;
    private final NewsDocEmbeddingMapper embMapper;
    private final EmbeddingClient embeddingClient;

    public NewsDocService(NewsMapper newsMapper, NewsDocMapper docMapper,
                          NewsDocEmbeddingMapper embMapper, EmbeddingClient embeddingClient) {
        this.newsMapper = newsMapper;
        this.docMapper = docMapper;
        this.embMapper = embMapper;
        this.embeddingClient = embeddingClient;
    }

    /** 重建某新闻的全部切块 + 向量(先清后建,幂等)。 */
    public void rebuildForNews(Long newsId) {
        deleteByNews(newsId);
        NewsEntity n = newsMapper.selectById(newsId);
        if (n == null) return;
        List<String> chunks = chunkContent(n.getTitle(), n.getPublishDate(), n.getContent());
        if (chunks.isEmpty()) {
            log.info("新闻无正文且无标题,跳过切块 newsId={}", newsId);
            return;
        }
        List<NewsDocEntity> docs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            NewsDocEntity d = new NewsDocEntity();
            d.setNewsId(newsId);
            d.setSeq(i);
            d.setChunkType(chunkTypeOf(chunks));
            d.setChunkText(chunks.get(i));
            docs.add(d);
        }
        // embedding 并发化(固定小线程池,不随新闻数膨胀)+ 单块失败重试 1 次
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, Math.max(1, docs.size())));
        List<Callable<Boolean>> tasks = new ArrayList<>();
        List<NewsDocEntity> failedDocs = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger okCount = new AtomicInteger();
        for (NewsDocEntity doc : docs) {
            tasks.add(() -> {
                try {
                    try {
                        insertDocWithEmbedding(doc);
                    } catch (Exception first) {
                        log.warn("新闻块向量化失败将重试 newsId={} seq={} err={}", newsId, doc.getSeq(), first.getMessage());
                        insertDocWithEmbedding(doc);
                    }
                    okCount.incrementAndGet();
                    return Boolean.TRUE;
                } catch (Exception e) {
                    failedDocs.add(doc);
                    log.warn("新闻块向量化失败(已重试) newsId={} seq={} err={}", newsId, doc.getSeq(), e.getMessage());
                    return Boolean.FALSE;
                }
            });
        }
        try {
            pool.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
        }
        if (!failedDocs.isEmpty()) {
            log.warn("新闻向量重建完成(有缺失) newsId={} 成功 {}/{} 失败块 seq={}",
                    newsId, okCount.get(), docs.size(), failedDocs.stream().map(NewsDocEntity::getSeq).toList());
        } else {
            log.info("新闻向量重建完成 newsId={} 成功 {}/{}", newsId, okCount.get(), docs.size());
        }
    }

    /** 物理清块与向量(重建/删除共用)。 */
    @Transactional
    public void deleteByNews(Long newsId) {
        embMapper.deleteByNewsId(newsId);
        docMapper.deleteByNewsId(newsId);
    }

    /** 插入文档块并向量化(先插 doc 拿 id,再插 embedding)。 */
    @Transactional
    protected void insertDocWithEmbedding(NewsDocEntity doc) {
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        docMapper.insert(doc);
        String vec = embeddingClient.embed(doc.getChunkText());
        embMapper.insert(doc.getId(), doc.getNewsId(), vec);
    }

    /**
     * 块类型:仅当整篇只产出一个块且该块无换行(即纯标题锚点块,正文为空)时为 NEWS_TITLE,其余为 NEWS_BODY。
     * 提取为纯函数便于单测覆盖两分支。
     */
    static String chunkTypeOf(List<String> chunks) {
        return chunks.size() == 1 && isTitleOnly(chunks.get(0)) ? "NEWS_TITLE" : "NEWS_BODY";
    }

    private static boolean isTitleOnly(String chunk) {
        return chunk != null && chunk.indexOf('\n') < 0;
    }

    /**
     * 切块算法(纯函数,便于单测;仿 KbDocService.chunkContent):
     * 1) 每块首行固定「新闻:<title>(<publishDate>)」(跨域检索主题锚点);
     * 2) 正文按空行分段;单段 ≤MAX_BODY_LEN 直接成块;
     * 3) 超长段按句读(。;;!?)切分并合并至 ≤MAX_BODY_LEN;
     * 4) 正文为空(图片型新闻)时:有标题保留标题块(NEWS_TITLE),无标题返回空列表。
     */
    static List<String> chunkContent(String title, LocalDateTime publishDate, String content) {
        String header = "新闻：" + (title == null ? "" : title.trim())
                + "（" + (publishDate == null ? "" : publishDate.toLocalDate().toString()) + "）";
        List<String> out = new ArrayList<>();
        if (content == null || content.strip().isEmpty()) {
            if (title != null && !title.isBlank()) out.add(header);
            return out;
        }
        String[] paragraphs = content.split("\\n\\s*\\n");
        List<String> bodies = new ArrayList<>();
        StringBuilder carry = null;   // 超长段切分后的合并中转
        for (String pRaw : paragraphs) {
            String p = pRaw.replaceAll("\\s*\\n\\s*", " ").trim();   // 段内换行转空格
            if (p.isEmpty()) continue;
            if (p.length() <= MAX_BODY_LEN) {
                if (carry != null) { bodies.add(carry.toString()); carry = null; }
                bodies.add(p);
                continue;
            }
            for (String s : splitSentences(p)) {
                if (carry == null) {
                    carry = new StringBuilder(s);
                } else if (carry.length() + s.length() <= MAX_BODY_LEN) {
                    carry.append(s);
                } else {
                    bodies.add(carry.toString());
                    carry = new StringBuilder(s);
                }
            }
        }
        if (carry != null) bodies.add(carry.toString());
        for (String b : bodies) {
            out.add(header + "\n" + b);
        }
        if (out.isEmpty()) out.add(header);   // 双保险:正文全为符号等极端情况
        return out;
    }

    /** 按句读切分(。;;!?),保留分隔符;无句读的长段按 MAX_BODY_LEN 硬切。 */
    private static List<String> splitSentences(String p) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < p.length(); i++) {
            char ch = p.charAt(i);
            cur.append(ch);
            if (ch == '。' || ch == '；' || ch == ';' || ch == '！' || ch == '!' || ch == '？' || ch == '?') {
                String s = cur.toString();
                if (!s.isBlank()) out.add(s);
                cur.setLength(0);
            }
        }
        if (!cur.isEmpty()) {
            String tail = cur.toString();
            if (tail.length() <= MAX_BODY_LEN) {
                if (!tail.isBlank()) out.add(tail);
            } else {
                for (int i = 0; i < tail.length(); i += MAX_BODY_LEN) {
                    out.add(tail.substring(i, Math.min(tail.length(), i + MAX_BODY_LEN)));
                }
            }
        }
        return out;
    }
}
