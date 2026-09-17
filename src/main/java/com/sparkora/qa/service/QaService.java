package com.sparkora.qa.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.car.service.CarRagService;
import com.sparkora.domain.entity.QaMessageEntity;
import com.sparkora.domain.entity.QaSessionEntity;
import com.sparkora.mapper.QaMessageMapper;
import com.sparkora.mapper.QaSessionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多轮对话式知识问答服务(C4)。
 *
 * 数据流(ask):
 *   载入本会话历史 → 构造检索 query(短问题/追问拼接最近 2 轮 user 问题) →
 *   {@link CarRagService#retrieveForGeneration} 跨三域检索 → 组装多轮 messages(system + 历史 + 本轮) →
 *   {@link AiClient#chatMessages} 合成 → 解析答案配图({@link QaImageRefService},失败仅 warn 不阻断) →
 *   落 user + assistant 消息(citations/rag_status/image_refs)。
 *
 * 开关契约:问答链路**不读** {@code SettingService.kbEnabled}/sparkora_setting,浏览/问答独立于「生成注入」开关;
 * KB 域是否参与由检索层既有 AiProperties.ragKbEnabled 决定(本服务不改)。
 * 归属:会话仅 created_by 本人可见,越权/不存在统一 IllegalArgumentException(控制器映射 404,不泄露存在性)。
 *
 * 09-15 qa-auto-illustrate(子D):配图为**只读附加展示**(新闻关联图 + 图片意图语义检索图),
 * 不写任何用户内容、无批准流程;解析失败落 image_refs=null,答案照常落库。
 */
@Slf4j
@Service
public class QaService {

    /** 送入 LLM 的历史窗口:最近 6 轮(12 条消息)。 */
    static final int HISTORY_MAX_TURNS = 6;
    static final int HISTORY_MAX_MESSAGES = HISTORY_MAX_TURNS * 2;
    /** 单条历史消息截断长度。 */
    static final int HISTORY_MSG_MAX = 2000;
    /** 历史总字符上限,超出丢最旧。 */
    static final int HISTORY_TOTAL_MAX = 12000;
    /** 检索 query 上限(拼接历史后的截断)。 */
    static final int SEARCH_QUERY_MAX = 300;
    /** 短问题判定阈值(疑似指代代词)。 */
    static final int PRONOUN_QUESTION_MAX = 12;
    /** 检索注入块数上限。 */
    static final int RAG_TOPK = 8;
    /** 合成答案 max_tokens。 */
    static final int ANSWER_MAX_TOKENS = 2048;
    /** 会话标题自动生成截断。 */
    static final int TITLE_MAX = 50;
    /** 单条答案配图数上限（09-15 qa-auto-illustrate；产品展示决策，防刷屏——不值得配置化）。 */
    static final int IMAGE_REF_MAX = 3;

    private final QaSessionMapper sessionMapper;
    private final QaMessageMapper messageMapper;
    private final CarRagService ragService;
    private final QaImageRefService imageRefService;
    private final AiClient aiClient;
    private final ObjectMapper json;

    public QaService(QaSessionMapper sessionMapper, QaMessageMapper messageMapper,
                     CarRagService ragService, QaImageRefService imageRefService,
                     AiClient aiClient, ObjectMapper json) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.ragService = ragService;
        this.imageRefService = imageRefService;
        this.aiClient = aiClient;
        this.json = json;
    }

    /** 历史消息(轻量投影,供多轮 messages 组装与窗口截断)。 */
    public record HistoryMessage(String role, String content) {}

    /** 新建会话(title 可空,空标题在首问时回填)。 */
    public QaSessionEntity create(String title, String username) {
        QaSessionEntity s = new QaSessionEntity();
        s.setTitle(blankToNull(title));
        s.setCreatedBy(normalizeUser(username));
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(s);
        return s;
    }

    /** 会话列表(仅本人,按更新时间倒序)。 */
    public List<QaSessionEntity> listByUser(String username) {
        return sessionMapper.selectList(new QueryWrapper<QaSessionEntity>()
                .eq("created_by", normalizeUser(username))
                .orderByDesc("updated_at")
                .orderByDesc("id"));
    }

    /** 会话详情(含消息,按 id 升序);越权/不存在统一抛 IllegalArgumentException。 */
    public Map<String, Object> get(Long id, String username) {
        QaSessionEntity session = requireOwned(id, username);
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("session", session);
        vo.put("messages", listMessages(id));
        return vo;
    }

    /** 逻辑删除会话(仅本人);越权/不存在统一抛 IllegalArgumentException。 */
    public void delete(Long id, String username) {
        requireOwned(id, username);
        sessionMapper.deleteById(id);
    }

    /**
     * 提问并合成答案。返回 {userMessage, assistantMessage}。
     * 前置:会话归属校验(否则 IllegalArgumentException)。
     */
    public Map<String, Object> ask(Long sessionId, String question, String username) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        String q = question.trim();
        QaSessionEntity session = requireOwned(sessionId, username);

        // 1) 本会话既有消息(时间升序);构造检索 query 用其中最近 user 问题
        List<QaMessageEntity> history = listMessages(sessionId);
        List<String> recentUserQuestions = new ArrayList<>();
        for (QaMessageEntity m : history) {
            if ("user".equals(m.getRole()) && m.getContent() != null && !m.getContent().isBlank()) {
                recentUserQuestions.add(m.getContent());
            }
        }
        String searchQuery = buildSearchQuery(q, recentUserQuestions);

        // 2) 跨三域检索(不改检索语义;锚点为 null;不读 kb_enabled)
        CarRagService.RagResult rag = ragService.retrieveForGeneration(searchQuery, RAG_TOPK, null);

        // 3) 组装多轮 messages:system(含知识上下文) + 历史窗口 + 本轮 user
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt(rag)));
        for (HistoryMessage h : windowHistory(history)) {
            messages.add(Map.of("role", h.role(), "content", h.content()));
        }
        messages.add(Map.of("role", "user", "content", q));

        // 4) AI 合成(非 JSON;失败抛 AiException,由控制器映射 500,不落半截消息)
        String answer = aiClient.chatMessages(messages, ANSWER_MAX_TOKENS).content();

        // 4.5) 解析答案配图(只读附加展示;失败仅 warn,答案可用性优先——绝不阻断)
        //      新闻关联图(命中 NEWS 引用)+ 图片意图问法的语义检索图,合并去重后落 image_refs。
        //      空 → 落 null(与历史行一致,前端不展示图片区)。
        List<com.sparkora.domain.dto.QaImageRef> imageRefs = List.of();
        try {
            imageRefs = imageRefService.forAnswer(rag.citations(), q, IMAGE_REF_MAX);
        } catch (Exception e) {
            log.warn("问答配图解析失败(忽略) session={}: {}", sessionId, e.getMessage());
        }

        // 5) 落 user 消息 + assistant 消息(citations JSON / rag_status / image_refs)
        QaMessageEntity userMsg = new QaMessageEntity();
        userMsg.setSessionId(sessionId);
        userMsg.setRole("user");
        userMsg.setContent(q);
        userMsg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(userMsg);

        QaMessageEntity assistantMsg = new QaMessageEntity();
        assistantMsg.setSessionId(sessionId);
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(answer);
        assistantMsg.setCitations(toJson(rag.citations()));
        assistantMsg.setRagStatus(rag.status().name());
        assistantMsg.setImageRefs(imageRefs == null || imageRefs.isEmpty() ? null : toJson(imageRefs));
        assistantMsg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(assistantMsg);

        // 6) 首问回填标题(仅 title 为空时) + 刷新会话 updated_at
        UpdateWrapper<QaSessionEntity> upd = new UpdateWrapper<QaSessionEntity>()
                .eq("id", sessionId)
                .set("updated_at", LocalDateTime.now());
        if (session.getTitle() == null || session.getTitle().isBlank()) {
            upd.set("title", q.length() > TITLE_MAX ? q.substring(0, TITLE_MAX) : q);
        }
        sessionMapper.update(null, upd);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userMessage", userMsg);
        out.put("assistantMessage", assistantMsg);
        return out;
    }

    /** 会话消息(按 id 升序)。 */
    public List<QaMessageEntity> listMessages(Long sessionId) {
        return messageMapper.selectList(new QueryWrapper<QaMessageEntity>()
                .eq("session_id", sessionId)
                .orderByAsc("id"));
    }

    /** 归属校验:不存在或非本人 → IllegalArgumentException(控制器映射 404)。 */
    private QaSessionEntity requireOwned(Long id, String username) {
        QaSessionEntity s = id == null ? null : sessionMapper.selectById(id);
        if (s == null || !normalizeUser(username).equals(s.getCreatedBy())) {
            throw new IllegalArgumentException("会话不存在");
        }
        return s;
    }

    /**
     * 系统提示:角色定位 + 答案铁律 + 知识上下文(OK 时注入;非 OK 标注降级,让模型回答「知识库未覆盖」)。
     */
    static String buildSystemPrompt(CarRagService.RagResult rag) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 Sparkora 的知识问答助手,基于提供的知识库资料回答用户问题。\n")
          .append("要求:\n")
          .append("1. 只依据知识库资料作答,不得编造资料中不存在的数据(价格/参数/日期等);\n")
          .append("2. 回答末尾标注引用来源(资料中的【车型数据:…】/【通用知识:…】/【官方新闻:…】标注);\n")
          .append("3. 资料未覆盖时,明确说明「知识库未覆盖」并给出谨慎建议,不得臆造;\n")
          .append("4. 使用简体中文,条理清晰。\n");
        if (rag != null && rag.ok() && rag.context() != null && !rag.context().isBlank()) {
            sb.append("\n【知识库资料】\n").append(rag.context());
        } else {
            sb.append("\n【知识库资料】\n").append(degradeNote(rag == null ? null : rag.status()));
        }
        return sb.toString();
    }

    /** 非 OK 状态的知识库降级说明(让模型显式回答「未覆盖」而非臆造)。 */
    private static String degradeNote(CarRagService.RagStatus status) {
        if (status == null) return "（本轮无知识库资料）";
        return switch (status) {
            case LOW_CONFIDENCE -> "（知识库有命中但相关性过低,已全部抛弃;请说明知识库未覆盖）";
            case FAILED -> "（知识库检索失败,本轮未注入资料;请说明知识库暂时不可用）";
            default -> "（知识库无相关命中;请说明知识库未覆盖）";
        };
    }

    /**
     * 构造检索 query(解决追问指代):短问题(≤12 字,疑似指代)或会话已有历史时,
     * 拼接最近 2 轮 user 问题 + 当前问题,截断 ≤300 字;否则等同当前问题。
     */
    static String buildSearchQuery(String question, List<String> recentUserQuestions) {
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) return "";
        boolean pronounish = q.length() <= PRONOUN_QUESTION_MAX;
        boolean hasHistory = recentUserQuestions != null && !recentUserQuestions.isEmpty();
        if (!pronounish && !hasHistory) return truncate(q, SEARCH_QUERY_MAX);
        StringBuilder sb = new StringBuilder();
        if (recentUserQuestions != null) {
            int from = Math.max(0, recentUserQuestions.size() - 2);
            for (String p : recentUserQuestions.subList(from, recentUserQuestions.size())) {
                if (p == null || p.isBlank()) continue;
                sb.append(p.trim()).append(' ');
            }
        }
        sb.append(q);
        return truncate(sb.toString().trim(), SEARCH_QUERY_MAX);
    }

    /**
     * 历史窗口:取最近 HISTORY_MAX_MESSAGES 条,单条截断 HISTORY_MSG_MAX,
     * 总长 ≤ HISTORY_TOTAL_MAX(从新到旧累加,超限丢最旧),返回时间升序投影。
     */
    static List<HistoryMessage> windowHistory(List<QaMessageEntity> all) {
        if (all == null || all.isEmpty()) return List.of();
        int from = Math.max(0, all.size() - HISTORY_MAX_MESSAGES);
        List<QaMessageEntity> tail = all.subList(from, all.size());
        java.util.LinkedList<HistoryMessage> out = new java.util.LinkedList<>();
        int total = 0;
        for (int i = tail.size() - 1; i >= 0; i--) {
            QaMessageEntity m = tail.get(i);
            String c = m.getContent() == null ? "" : m.getContent();
            if (c.length() > HISTORY_MSG_MAX) c = c.substring(0, HISTORY_MSG_MAX);
            if (total + c.length() > HISTORY_TOTAL_MAX) break;   // 超限丢最旧(更旧的优先级更低)
            out.addFirst(new HistoryMessage(m.getRole(), c));
            total += c.length();
        }
        return out;
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("问答消息 JSON 序列化失败(citations/image_refs): {}", e.getMessage());
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String normalizeUser(String username) {
        return (username == null || username.isBlank()) ? "system" : username;
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }
}
