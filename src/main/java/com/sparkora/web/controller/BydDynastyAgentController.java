package com.sparkora.web.controller;

import com.sparkora.common.R;
import com.sparkora.service.BydDynastyMediaService;
import com.sparkora.service.BydDynastyPushService;
import com.sparkora.web.dto.BydAgentClaimRequest;
import com.sparkora.web.dto.BydAgentHeartbeatRequest;
import com.sparkora.web.dto.BydAgentRegisterRequest;
import com.sparkora.web.dto.BydAgentResultRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * BYD 王朝本机 Agent API。
 *
 * <p>该路径在 SecurityConfig 中放行 JWT 认证，由 Controller 内检查专属 {@code X-Agent-Token}。
 * Agent Token 与用户 JWT 隔离，防止浏览器 JWT 直接领取本机任务。</p>
 */
@RestController
@RequestMapping("/api/agent/byd-dynasty")
public class BydDynastyAgentController {

    private final BydDynastyPushService service;
    private final BydDynastyMediaService mediaService;

    public BydDynastyAgentController(BydDynastyPushService service,
                                     BydDynastyMediaService mediaService) {
        this.service = service;
        this.mediaService = mediaService;
    }

    @PostMapping("/register")
    public R<Map<String, Object>> register(@RequestHeader(value = "X-Agent-Token", required = false) String token,
                                           @RequestBody BydAgentRegisterRequest req) {
        if (!service.agentAuthorized(token)) return R.fail(401, "Agent Token 无效或未配置");
        try {
            return R.ok(service.registerAgent(req));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "Agent 注册失败: " + ex.getMessage());
        }
    }

    @PostMapping("/heartbeat")
    public R<Map<String, Object>> heartbeat(@RequestHeader(value = "X-Agent-Token", required = false) String token,
                                            @RequestBody BydAgentHeartbeatRequest req) {
        if (!service.agentAuthorized(token)) return R.fail(401, "Agent Token 无效或未配置");
        try {
            return R.ok(service.heartbeat(req));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "Agent 心跳失败: " + ex.getMessage());
        }
    }

    @PostMapping("/claim-job")
    public R<Map<String, Object>> claimJob(@RequestHeader(value = "X-Agent-Token", required = false) String token,
                                           @RequestBody BydAgentClaimRequest req) {
        if (!service.agentAuthorized(token)) return R.fail(401, "Agent Token 无效或未配置");
        try {
            return R.ok(service.claimJob(req));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "领取任务失败: " + ex.getMessage());
        }
    }

    @PostMapping("/jobs/{jobId}/result")
    public R<Map<String, Object>> reportResult(@RequestHeader(value = "X-Agent-Token", required = false) String token,
                                               @PathVariable String jobId,
                                               @RequestBody BydAgentResultRequest req) {
        if (!service.agentAuthorized(token)) return R.fail(401, "Agent Token 无效或未配置");
        try {
            return R.ok(service.reportResult(jobId, req));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "回传推送结果失败: " + ex.getMessage());
        }
    }

    /** 任务范围内流式下载暂存媒体；只返回 payload 实际引用且属于当前 Agent 任务的文件。 */
    @GetMapping("/jobs/{jobId}/media/{mediaId}")
    public ResponseEntity<?> downloadMedia(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @PathVariable String jobId,
            @PathVariable String mediaId) {
        if (!service.agentAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail(401, "Agent Token 无效或未配置"));
        }
        try {
            BydDynastyMediaService.MediaDownload download =
                    mediaService.loadAgentDownload(jobId, mediaId, agentId);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(download.mimeType()));
            headers.setContentLength(download.byteSize());
            headers.set(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(safeDownloadName(download.fileName()), StandardCharsets.UTF_8)
                    .build().toString());
            headers.setCacheControl("no-store");
            Resource body = download.resource();
            return new ResponseEntity<>(body, headers, HttpStatus.OK);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(R.fail(400, ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(R.fail(500, "下载任务媒体失败，请稍后重试"));
        }
    }

    private static String safeDownloadName(String value) {
        String name = value == null ? "" : value.trim()
                .replaceAll("[\\r\\n\\t\\x00-\\x1f\\x7f]", "_")
                .replace('\\', '_')
                .replace('/', '_')
                .replace('"', '_');
        if (name.isBlank()) return "byd-media";
        return name.length() <= 180 ? name : name.substring(name.length() - 180);
    }
}
