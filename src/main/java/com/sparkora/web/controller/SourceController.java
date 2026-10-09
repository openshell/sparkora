package com.sparkora.web.controller;

import com.sparkora.common.R;
import com.sparkora.domain.dto.ChannelDTO;
import com.sparkora.domain.dto.PageResult;
import com.sparkora.domain.dto.SourceContentDTO;
import com.sparkora.domain.dto.SourceCreateDTO;
import com.sparkora.domain.dto.SourceUpdateDTO;
import com.sparkora.domain.entity.SourceChannelEntity;
import com.sparkora.domain.entity.SourceEntity;
import com.sparkora.domain.entity.SourceJobEntity;
import com.sparkora.source.service.SourceContentService;
import com.sparkora.source.service.SourceJobService;
import com.sparkora.source.service.SourceService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 信源注册与采集接口(10-05-source-crawl-base)。全部 {@code R<T>} + 方法级 @PreAuthorize。
 * - 读(列表/详情/任务/内容):三角色;
 * - 写(编辑源/手动采集/重试):ADMIN/EDITOR。
 *
 * <p>BYD 内容仍走 {@code /api/news}(只返回 source=byd-news);通用信源内容走 {@code /api/source-contents}。
 */
@RestController
@RequestMapping("/api")
public class SourceController {

    private final SourceService sourceService;
    private final SourceJobService jobService;
    private final SourceContentService contentService;
    /**
     * 10-05 E：通用信源内容的切块/向量重建（对存量已采集内容补嵌入）。字段注入可选，
     * 保持既有 3 参构造器与契约测试不变。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sparkora.source.service.SourceDocService sourceDocService;

    public SourceController(SourceService sourceService, SourceJobService jobService,
                            SourceContentService contentService) {
        this.sourceService = sourceService;
        this.jobService = jobService;
        this.contentService = contentService;
    }

    // ==================== 信源注册表 ====================

    /** 信源列表(含 enabled/栏目数)。 */
    @GetMapping("/sources")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<List<SourceEntity>> list() {
        return R.ok(sourceService.list());
    }

    /** 信源详情(含 channels[])。 */
    @GetMapping("/sources/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<SourceEntity> get(@PathVariable Long id) {
        try {
            return R.ok(sourceService.get(id));
        } catch (IllegalArgumentException e) {
            return R.fail(404, e.getMessage());
        }
    }

    /** 编辑源级字段(触发调度重注册)。 */
    @PutMapping("/sources/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<SourceEntity> update(@PathVariable Long id, @Valid @RequestBody SourceUpdateDTO dto) {
        try {
            return R.ok(sourceService.update(id, dto));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, "操作失败: " + e.getMessage());
        }
    }

    /** 新建信源(含 channels[]);G4 注册闭环。 */
    @PostMapping("/sources")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<SourceEntity> create(@Valid @RequestBody SourceCreateDTO dto) {
        try {
            return R.ok(sourceService.create(dto));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, "操作失败: " + e.getMessage());
        }
    }

    /** 新增栏目(G4);改后调度重注册。 */
    @PostMapping("/sources/{id}/channels")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<SourceChannelEntity> addChannel(@PathVariable Long id, @Valid @RequestBody ChannelDTO dto) {
        try {
            return R.ok(sourceService.addChannel(id, dto));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, "操作失败: " + e.getMessage());
        }
    }

    /** 编辑栏目(G4,部分更新);改后调度重注册。 */
    @PutMapping("/channels/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<SourceChannelEntity> updateChannel(@PathVariable Long id, @Valid @RequestBody ChannelDTO dto) {
        try {
            return R.ok(sourceService.updateChannel(id, dto));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, "操作失败: " + e.getMessage());
        }
    }

    /** 删除栏目(G4,逻辑删);改后调度重注册。 */
    @DeleteMapping("/channels/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> deleteChannel(@PathVariable Long id) {
        try {
            sourceService.deleteChannel(id);
            return R.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, "操作失败: " + e.getMessage());
        }
    }

    /** 手动触发采集(可指定 channelId)。返回 {jobId}。 */
    @PostMapping("/sources/{id}/collect")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> collect(@PathVariable Long id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        try {
            Long channelId = body == null || body.get("channelId") == null
                    ? null : Long.valueOf(String.valueOf(body.get("channelId")));
            Long jobId = jobService.createJob(id, channelId, "MANUAL", null);
            return R.ok(Map.of("jobId", jobId));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, "操作失败: " + e.getMessage());
        }
    }

    // ==================== 采集任务 ====================

    /** 任务历史/进度(?sourceId 可选)。 */
    @GetMapping("/source-jobs")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<List<SourceJobEntity>> listJobs(@RequestParam(required = false) Long sourceId) {
        return R.ok(jobService.list(sourceId));
    }

    /** 任务进度。 */
    @GetMapping("/source-jobs/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<SourceJobEntity> getJob(@PathVariable Long id) {
        SourceJobEntity job = jobService.get(id);
        if (job == null) return R.fail(404, "任务不存在");
        return R.ok(job);
    }

    /** 重试任务的失败项。返回新任务 {jobId}。 */
    @PostMapping("/source-jobs/{id}/retry")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> retryJob(@PathVariable Long id) {
        try {
            return R.ok(Map.of("jobId", jobService.retry(id)));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, "操作失败: " + e.getMessage());
        }
    }

    // ==================== 内容查询(U 前置) ====================

    /** 内容列表(分页 + keyword/category/sourceId 筛选)。 */
    @GetMapping("/source-contents")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<PageResult<SourceContentDTO>> listContents(@RequestParam(defaultValue = "1") long page,
                                                        @RequestParam(defaultValue = "12") long size,
                                                        @RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) String category,
                                                        @RequestParam(required = false) Long sourceId) {
        return R.ok(contentService.list(page, size, keyword, category, sourceId));
    }

    /** 内容详情(含正文/切块数/原文链接)。 */
    @GetMapping("/source-contents/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<SourceContentDTO> getContent(@PathVariable Long id) {
        try {
            return R.ok(contentService.get(id));
        } catch (IllegalArgumentException e) {
            return R.fail(404, e.getMessage());
        }
    }

    /**
     * 重建单条通用信源内容的切块+向量（10-05 E；对存量已采集内容补嵌入，幂等先清后建）。
     * 返回 {@code {total,success,failed}}；源文档不存在（非通用信源）→ 400。开关关闭时不阻断生成。
     */
    @PostMapping("/source-contents/{id}/rebuild")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> rebuildContent(@PathVariable Long id) {
        if (sourceDocService == null) return R.fail(500, "向量重建服务不可用");
        try {
            com.sparkora.ai.EmbedStats s = sourceDocService.rebuildForNews(id);
            return R.ok(Map.of("total", s.total(), "success", s.success(), "failed", s.failed()));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, "操作失败: " + e.getMessage());
        }
    }
}
