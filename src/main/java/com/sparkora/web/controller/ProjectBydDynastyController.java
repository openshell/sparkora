package com.sparkora.web.controller;

import com.sparkora.common.R;
import com.sparkora.security.SecurityUtil;
import com.sparkora.service.BydDynastyPushService;
import com.sparkora.web.dto.BydPushJobCreateRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 项目 BYD 王朝推送子域接口。
 *
 * <p>与公众号发布字段隔离：本控制器只创建/查询 BYD 任务，不调用公众号状态机。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ProjectBydDynastyController {

    private final BydDynastyPushService pushService;

    public ProjectBydDynastyController(BydDynastyPushService pushService) {
        this.pushService = pushService;
    }

    /** BYD 推送准备状态（本地 Agent、预检摘要、项目最近结果）。 */
    @GetMapping("/byd-dynasty/state")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<Map<String, Object>> state(@PathVariable Long projectId) {
        try {
            return R.ok(pushService.state(projectId));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "查询 BYD 推送状态失败: " + ex.getMessage());
        }
    }

    /** 创建推送任务（ADMIN/EDITOR）。Server 编译 payload，本机 Agent 稍后领取执行。 */
    @PostMapping("/byd-dynasty/push-jobs")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> createJob(@PathVariable Long projectId,
                                            @RequestBody(required = false) BydPushJobCreateRequest req) {
        try {
            return R.ok(pushService.createJob(projectId, req, SecurityUtil.require().getUserId()));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "创建 BYD 推送任务失败: " + ex.getMessage());
        }
    }

    /** 查询单个任务状态和结果。 */
    @GetMapping("/byd-dynasty/push-jobs/{jobId}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<Map<String, Object>> getJob(@PathVariable Long projectId, @PathVariable String jobId) {
        try {
            return R.ok(pushService.getJob(projectId, jobId));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "查询 BYD 推送任务失败: " + ex.getMessage());
        }
    }
}
