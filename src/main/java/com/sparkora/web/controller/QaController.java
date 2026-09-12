package com.sparkora.web.controller;

import com.sparkora.common.R;
import com.sparkora.domain.entity.QaSessionEntity;
import com.sparkora.qa.service.QaService;
import com.sparkora.security.SecurityUtil;
import com.sparkora.web.dto.QaAskDto;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 多轮对话式知识问答接口(C4,独立入口 /api/qa)。
 * - 三角色可读可写(问答属浏览能力,不受「生成注入」开关 kb_enabled 控制);
 * - 会话仅本人可见,越权/不存在统一 404(不泄露存在性);
 * - 全部 R<T> 包装,业务失败 HTTP 200 + R.fail。
 */
@RestController
@RequestMapping("/api/qa")
public class QaController {

    private final QaService service;

    public QaController(QaService service) {
        this.service = service;
    }

    /** 新建会话(title 可空,首问自动回填)。 */
    @PostMapping("/sessions")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<QaSessionEntity> createSession(@RequestBody(required = false) Map<String, Object> body) {
        Object title = body == null ? null : body.get("title");
        return R.ok(service.create(title == null ? null : String.valueOf(title), username()));
    }

    /** 会话列表(仅本人)。 */
    @GetMapping("/sessions")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<List<QaSessionEntity>> listSessions() {
        return R.ok(service.listByUser(username()));
    }

    /** 会话详情(含消息);不存在/越权 404。 */
    @GetMapping("/sessions/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<Map<String, Object>> getSession(@PathVariable Long id) {
        try {
            return R.ok(service.get(id, username()));
        } catch (IllegalArgumentException e) {
            return R.fail(404, e.getMessage());
        }
    }

    /** 提问并合成答案(返回 user 消息 + assistant 消息含 citations)。 */
    @PostMapping("/sessions/{id}/messages")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<Map<String, Object>> ask(@PathVariable Long id, @Valid @RequestBody QaAskDto dto) {
        try {
            return R.ok(service.ask(id, dto.getQuestion(), username()));
        } catch (IllegalArgumentException e) {
            return R.fail(404, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, "问答失败: " + e.getMessage());
        }
    }

    /** 删除会话(逻辑删,仅本人)。 */
    @DeleteMapping("/sessions/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<Void> deleteSession(@PathVariable Long id) {
        try {
            service.delete(id, username());
            return R.ok();
        } catch (IllegalArgumentException e) {
            return R.fail(404, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, e.getMessage());
        }
    }

    private String username() {
        return SecurityUtil.current() == null ? "system" : SecurityUtil.current().getUsername();
    }
}
