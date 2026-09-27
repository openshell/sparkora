package com.sparkora.web.controller;

import com.sparkora.ai.EmbedStats;
import com.sparkora.common.R;
import com.sparkora.domain.dto.PageResult;
import com.sparkora.domain.entity.NewsEntity;
import com.sparkora.domain.entity.NewsSyncJobEntity;
import com.sparkora.news.service.NewsDocService;
import com.sparkora.news.service.NewsService;
import com.sparkora.news.service.NewsSyncJobService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 新闻知识域接口(C2)。
 * - VIEWER 可读;ADMIN/EDITOR 可同步。
 * - sync:手动触发抓取(异步任务化,创建任务即返回 jobId,前端轮询进度)。
 * - 新闻与车型不关联,作为独立知识域进入统一检索。
 */
@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final NewsService service;
    private final NewsSyncJobService jobService;
    private final NewsDocService docService;

    public NewsController(NewsService service, NewsSyncJobService jobService, NewsDocService docService) {
        this.service = service;
        this.jobService = jobService;
        this.docService = docService;
    }

    /** 分页列表(?page&size&keyword)。 */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<PageResult<NewsEntity>> list(@RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "12") long size,
                                          @RequestParam(required = false) String keyword) {
        return R.ok(service.list(page, size, keyword));
    }

    /** 详情(含正文 content)。 */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<NewsEntity> get(@PathVariable Long id) {
        try {
            return R.ok(service.get(id));
        } catch (IllegalArgumentException e) {
            return R.fail(404, e.getMessage());
        }
    }

    /** 创建同步任务。body: {jobType:"FULL"|"INCREMENT"}(缺省 INCREMENT)。返回 {jobId}。 */
    @PostMapping("/sync/jobs")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> createJob(@RequestBody(required = false) Map<String, Object> body) {
        try {
            String jobType = body == null || body.get("jobType") == null
                    ? "INCREMENT" : String.valueOf(body.get("jobType"));
            Long jobId = jobService.createJob(jobType);
            return R.ok(Map.of("jobId", jobId));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, e.getMessage());
        }
    }

    /** 查询同步任务进度。 */
    @GetMapping("/sync/jobs/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<NewsSyncJobEntity> getJob(@PathVariable Long id) {
        NewsSyncJobEntity job = jobService.get(id);
        if (job == null) return R.fail(404, "任务不存在");
        return R.ok(job);
    }

    /** 同步任务历史列表。 */
    @GetMapping("/sync/jobs")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<List<NewsSyncJobEntity>> listJobs() {
        return R.ok(jobService.list());
    }

    /** 重试任务的失败项。返回新任务 {jobId}。 */
    @PostMapping("/sync/jobs/{id}/retry")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> retryJob(@PathVariable Long id) {
        try {
            Long jobId = jobService.retry(id);
            return R.ok(Map.of("jobId", jobId));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, e.getMessage());
        }
    }

    /**
     * 手动重建单篇新闻切块 + 向量（先清后建，幂等；09-27 R6 补齐 NEWS 缺失的手动重嵌入口）。
     * 返回 {total, success, failed}；新闻不存在返回 404。
     */
    @PostMapping("/{id}/rebuild")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<EmbedStats> rebuild(@PathVariable Long id) {
        try {
            service.get(id);   // 不存在时抛 IllegalArgumentException → 404
            return R.ok(docService.rebuildForNews(id));
        } catch (IllegalArgumentException e) {
            return R.fail(404, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, e.getMessage());
        }
    }
}
