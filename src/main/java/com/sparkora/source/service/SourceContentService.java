package com.sparkora.source.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sparkora.domain.dto.PageResult;
import com.sparkora.domain.dto.SourceContentDTO;
import com.sparkora.domain.entity.NewsDocEntity;
import com.sparkora.domain.entity.NewsEntity;
import com.sparkora.mapper.NewsDocMapper;
import com.sparkora.mapper.NewsMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 信源内容查询(10-05-source-crawl-base,B-R9,U 的硬前置)。
 *
 * <p>U 的 {@code SourceContentPanel} 需要内容列表/详情;E 不提供 HTTP 接口,故该读写入口归属 B(拥有
 * {@code sparkora_news} 写入)。列表分页 + keyword/category/sourceId 筛选;详情含正文与切块数。
 * {@code source=byd-news} 条目返回 U 条件渲染所需字段(切块数、原文 url)。
 */
@Service
public class SourceContentService {

    private final NewsMapper newsMapper;
    private final NewsDocMapper docMapper;

    public SourceContentService(NewsMapper newsMapper, NewsDocMapper docMapper) {
        this.newsMapper = newsMapper;
        this.docMapper = docMapper;
    }

    /** 分页列表(不返回正文大字段;回填切块数)。 */
    public PageResult<SourceContentDTO> list(long page, long size, String keyword, String category, Long sourceId) {
        if (page < 1) page = 1;
        if (size < 1 || size > 100) size = 12;
        QueryWrapper<NewsEntity> qw = new QueryWrapper<>();
        if (sourceId != null) qw.eq("source_id", sourceId);
        if (category != null && !category.isBlank()) qw.eq("category", category.trim());
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) qw.like("title", kw);
        qw.orderByDesc("publish_date").orderByDesc("id");
        Page<NewsEntity> p = newsMapper.selectPage(new Page<>(page, size), qw);
        List<SourceContentDTO> rows = new ArrayList<>();
        for (NewsEntity n : p.getRecords()) {
            rows.add(toDto(n, false));
        }
        return new PageResult<>(rows, p.getTotal(), p.getCurrent(), p.getSize());
    }

    /** 详情(含正文与切块数)。不存在抛 IllegalArgumentException(控制器映射 404)。 */
    public SourceContentDTO get(Long id) {
        NewsEntity n = newsMapper.selectById(id);
        if (n == null) throw new IllegalArgumentException("内容不存在");
        return toDto(n, true);
    }

    private SourceContentDTO toDto(NewsEntity n, boolean withContent) {
        SourceContentDTO d = new SourceContentDTO();
        d.setId(n.getId());
        d.setSourceId(n.getSourceId());
        d.setChannelId(n.getChannelId());
        d.setTitle(n.getTitle());
        d.setUrl(n.getUrl());
        d.setPublishDate(n.getPublishDate());
        d.setCategory(n.getCategory());
        // 通用信源 source="source";BYD 存量 source="byd-news"(U 条件渲染依据)
        d.setSource(n.getSource());
        d.setChunkCount(docMapper.selectCount(new QueryWrapper<NewsDocEntity>().eq("news_id", n.getId())));
        if (withContent) d.setContent(n.getContent());
        return d;
    }
}
