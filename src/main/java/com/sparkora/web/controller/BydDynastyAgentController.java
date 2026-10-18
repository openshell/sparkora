package com.sparkora.web.controller;

import com.sparkora.common.R;
import com.sparkora.service.BydDynastyPushService;
import com.sparkora.web.dto.BydAgentClaimRequest;
import com.sparkora.web.dto.BydAgentHeartbeatRequest;
import com.sparkora.web.dto.BydAgentRegisterRequest;
import com.sparkora.web.dto.BydAgentResultRequest;
import org.springframework.web.bind.annotation.*;

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

    public BydDynastyAgentController(BydDynastyPushService service) {
        this.service = service;
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
}
