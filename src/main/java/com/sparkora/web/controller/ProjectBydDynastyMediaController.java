package com.sparkora.web.controller;

import com.sparkora.common.R;
import com.sparkora.security.SecurityUtil;
import com.sparkora.service.BydDynastyMediaService;
import com.sparkora.web.dto.BydMediaFromImageRequest;
import com.sparkora.web.dto.BydMediaReservationRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** BYD 小程序正文媒体暂存接口；用户侧接口始终走 JWT，Agent 不能调用。 */
@RestController
@RequestMapping("/api/projects/{projectId}/byd-dynasty/media")
public class ProjectBydDynastyMediaController {

    private final BydDynastyMediaService mediaService;

    public ProjectBydDynastyMediaController(BydDynastyMediaService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> reserve(@PathVariable Long projectId,
                                          @RequestBody(required = false) BydMediaReservationRequest req) {
        try {
            return R.ok(mediaService.reserve(projectId, req, SecurityUtil.require().getUserId()));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "创建媒体预留失败，请稍后重试");
        }
    }

    /** 原始请求体流式写入，不得改用 MultipartFile，避免 300MB 视频整体进入 JVM 内存。 */
    @PutMapping("/{mediaId}/content")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> upload(@PathVariable Long projectId,
                                         @PathVariable String mediaId,
                                         HttpServletRequest request) {
        try {
            return R.ok(mediaService.completeUpload(
                    projectId, mediaId, request.getContentLengthLong(), request.getInputStream()));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "保存媒体文件失败，请稍后重试");
        }
    }

    @PostMapping("/from-image")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> fromImage(@PathVariable Long projectId,
                                            @RequestBody(required = false) BydMediaFromImageRequest req) {
        try {
            return R.ok(mediaService.registerFromImage(projectId, req, SecurityUtil.require().getUserId()));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "登记图库媒体失败，请稍后重试");
        }
    }

    @GetMapping("/{mediaId}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<Map<String, Object>> get(@PathVariable Long projectId, @PathVariable String mediaId) {
        try {
            return R.ok(mediaService.get(projectId, mediaId));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "查询媒体状态失败，请稍后重试");
        }
    }

    @DeleteMapping("/{mediaId}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> delete(@PathVariable Long projectId, @PathVariable String mediaId) {
        try {
            mediaService.delete(projectId, mediaId);
            return R.ok();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "删除媒体失败，请稍后重试");
        }
    }
}