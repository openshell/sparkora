package com.sparkora.qa.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.car.service.CarRagService;
import com.sparkora.domain.entity.QaMessageEntity;
import com.sparkora.domain.entity.QaSessionEntity;
import com.sparkora.mapper.QaMessageMapper;
import com.sparkora.mapper.QaSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C4 问答服务单测(Mockito,不连库/AI):
 * ① 检索 OK → assistant 消息落库、citations 正确映射、ragStatus=OK;
 * ② LOW_CONFIDENCE/FAILED → 仍返回答案、ragStatus 正确、citations 为空列表序列化;
 * ③ 越权/不存在会话 → IllegalArgumentException;
 * ④ 历史窗口截断(≤12 条 + 总长丢最旧);
 * ⑤ 检索 query 追问拼接(短问题/有历史时拼最近 2 轮 user 问题)。
 */
@ExtendWith(MockitoExtension.class)
class QaServiceTest {

    @Mock QaSessionMapper sessionMapper;
    @Mock QaMessageMapper messageMapper;
    @Mock CarRagService ragService;
    @Mock AiClient aiClient;

    QaService service;

    private static final String USER = "editor";

    @BeforeEach
    void setUp() {
        service = new QaService(sessionMapper, messageMapper, ragService, aiClient, new ObjectMapper());
    }

    private QaSessionEntity ownedSession(Long id) {
        QaSessionEntity s = new QaSessionEntity();
        s.setId(id);
        s.setCreatedBy(USER);
        s.setTitle("已有标题");
        return s;
    }

    private void stubAnswer(String answer) {
        when(aiClient.chatMessages(anyList(), anyInt())).thenReturn(new AiClient.ChatResult(answer, "m", 10));
    }

    private CarRagService.Citation cite(String source) {
        return new CarRagService.Citation(source, "比亚迪", "PARAM_GROUP", 0.9, "块文本");
    }

    @Test
    void 检索OK_citations正确映射_assistant消息落库含引用与状态() throws Exception {
        when(sessionMapper.selectById(1L)).thenReturn(ownedSession(1L));
        when(messageMapper.selectList(any())).thenReturn(List.of());
        CarRagService.RagResult rag = new CarRagService.RagResult(CarRagService.RagStatus.OK,
                "知识来源：车型数据\n---\n【车型数据：比亚迪】续航 600km\n---\n", 1, 0.9,
                "", List.of(cite("CAR"), cite("NEWS")));
        when(ragService.retrieveForGeneration(anyString(), anyInt(), any())).thenReturn(rag);
        stubAnswer("比亚迪续航 600km。");

        Map<String, Object> out = service.ask(1L, "比亚迪续航多少", USER);

        QaMessageEntity user = (QaMessageEntity) out.get("userMessage");
        QaMessageEntity assistant = (QaMessageEntity) out.get("assistantMessage");
        assertEquals("user", user.getRole());
        assertEquals("assistant", assistant.getRole());
        assertEquals("比亚迪续航 600km。", assistant.getContent());
        assertEquals("OK", assistant.getRagStatus());
        assertNotNull(assistant.getCitations());
        assertTrue(assistant.getCitations().contains("\"source\":\"CAR\""));
        assertTrue(assistant.getCitations().contains("\"source\":\"NEWS\""));
        // 两条消息均落库
        ArgumentCaptor<QaMessageEntity> cap = ArgumentCaptor.forClass(QaMessageEntity.class);
        verify(messageMapper, org.mockito.Mockito.times(2)).insert(cap.capture());
        assertEquals("user", cap.getAllValues().get(0).getRole());
        assertEquals("assistant", cap.getAllValues().get(1).getRole());
    }

    @Test
    void 低置信_仍返回答案_status正确_citations序列化为空数组() {
        when(sessionMapper.selectById(2L)).thenReturn(ownedSession(2L));
        when(messageMapper.selectList(any())).thenReturn(List.of());
        CarRagService.RagResult rag = new CarRagService.RagResult(
                CarRagService.RagStatus.LOW_CONFIDENCE, "", 2, 0.4, "", List.of());
        when(ragService.retrieveForGeneration(anyString(), anyInt(), any())).thenReturn(rag);
        stubAnswer("知识库未覆盖该问题。");

        Map<String, Object> out = service.ask(2L, "某个冷门问题", USER);
        QaMessageEntity assistant = (QaMessageEntity) out.get("assistantMessage");
        assertEquals("LOW_CONFIDENCE", assistant.getRagStatus());
        assertEquals("[]", assistant.getCitations());
    }

    @Test
    void 检索失败_仍返回答案_statusFAILED() {
        when(sessionMapper.selectById(3L)).thenReturn(ownedSession(3L));
        when(messageMapper.selectList(any())).thenReturn(List.of());
        CarRagService.RagResult rag = new CarRagService.RagResult(
                CarRagService.RagStatus.FAILED, "", 0, 0, "", List.of());
        when(ragService.retrieveForGeneration(anyString(), anyInt(), any())).thenReturn(rag);
        stubAnswer("知识库暂时不可用。");

        Map<String, Object> out = service.ask(3L, "问题", USER);
        QaMessageEntity assistant = (QaMessageEntity) out.get("assistantMessage");
        assertEquals("FAILED", assistant.getRagStatus());
        assertEquals("[]", assistant.getCitations());
        assertTrue(assistant.getContent().contains("不可用"));
    }

    @Test
    void 越权会话_get与ask均抛IllegalArgumentException_不落消息() {
        QaSessionEntity other = new QaSessionEntity();
        other.setId(9L);
        other.setCreatedBy("someone-else");
        when(sessionMapper.selectById(9L)).thenReturn(other);

        assertThrows(IllegalArgumentException.class, () -> service.get(9L, USER));
        assertThrows(IllegalArgumentException.class, () -> service.ask(9L, "问题", USER));
        verify(messageMapper, never()).insert(any(QaMessageEntity.class));
    }

    @Test
    void 会话不存在_抛IllegalArgumentException() {
        when(sessionMapper.selectById(404L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.get(404L, USER));
    }

    @Test
    void 历史窗口_最多12条_且按时间升序() {
        List<QaMessageEntity> all = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            QaMessageEntity m = new QaMessageEntity();
            m.setId((long) i);
            m.setRole(i % 2 == 0 ? "user" : "assistant");
            m.setContent("消息" + i);
            all.add(m);
        }
        List<QaService.HistoryMessage> win = QaService.windowHistory(all);
        assertEquals(QaService.HISTORY_MAX_MESSAGES, win.size());
        // 保留最近 12 条 → 首条应为 id=8,末条 id=19,升序
        assertEquals("消息8", win.get(0).content());
        assertEquals("消息19", win.get(win.size() - 1).content());
    }

    @Test
    void 历史窗口_总长超限_丢最旧_保留最新() {
        List<QaMessageEntity> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            QaMessageEntity m = new QaMessageEntity();
            m.setId((long) i);
            m.setRole("user");
            m.setContent("x".repeat(2000));
            all.add(m);
        }
        List<QaService.HistoryMessage> win = QaService.windowHistory(all);
        assertTrue(win.size() < 10, "总长超 12000 时必须丢最旧");
        assertEquals("x".repeat(2000), win.get(win.size() - 1).content());
    }

    @Test
    void 单条历史消息截断到2000字() {
        QaMessageEntity m = new QaMessageEntity();
        m.setRole("user");
        m.setContent("y".repeat(5000));
        List<QaService.HistoryMessage> win = QaService.windowHistory(List.of(m));
        assertEquals(QaService.HISTORY_MSG_MAX, win.get(0).content().length());
    }

    @Test
    void 检索query_短问题拼最近2轮user问题_长问题无历史不拼() {
        String shortQ = "那续航呢";
        List<String> recent = List.of("海狮08怎么样", "价格多少", "充电快吗");
        String merged = QaService.buildSearchQuery(shortQ, recent);
        assertTrue(merged.contains("价格多少") && merged.contains("充电快吗") && merged.contains("那续航呢"));
        assertTrue(!merged.contains("海狮08怎么样"), "只拼最近 2 轮");
        // 长问题且已有历史 → 仍拼接上下文
        String longQ = "这款车的智能驾驶辅助系统表现到底如何";
        assertTrue(QaService.buildSearchQuery(longQ, recent).contains(longQ));
        // 长问题无历史 → 原样
        assertEquals(longQ, QaService.buildSearchQuery(longQ, List.of()));
    }

    @Test
    void 检索query_截断不超过300字() {
        String q = "长".repeat(500);
        assertTrue(QaService.buildSearchQuery(q, List.of()).length() <= QaService.SEARCH_QUERY_MAX);
    }

    @Test
    void 首问自动回填标题_已有标题不覆盖() {
        when(sessionMapper.selectById(5L)).thenReturn(ownedSession(5L));   // title=已有标题
        when(messageMapper.selectList(any())).thenReturn(List.of());
        lenient().when(ragService.retrieveForGeneration(anyString(), anyInt(), any()))
                .thenReturn(CarRagService.RagResult.EMPTY);
        stubAnswer("答案");

        service.ask(5L, "新的问题", USER);

        // 已有标题时 update 只刷新 updated_at,不改 title
        ArgumentCaptor<Wrapper> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(sessionMapper).update(any(), cap.capture());
        String setSql = ((com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<?>) cap.getValue()).getSqlSet();
        assertTrue(!setSql.contains("title"), () -> "已有标题不应被覆盖: " + setSql);
        assertTrue(setSql.contains("updated_at"), () -> "updated_at 必须刷新: " + setSql);
    }

    @Test
    void 首问标题为空_回填问题前50字() {
        QaSessionEntity blank = ownedSession(6L);
        blank.setTitle(null);
        when(sessionMapper.selectById(6L)).thenReturn(blank);
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(ragService.retrieveForGeneration(anyString(), anyInt(), any()))
                .thenReturn(CarRagService.RagResult.EMPTY);
        stubAnswer("答案");
        String q = "这是一个超过五十个字的很长的问题用于验证标题回填是否按前五十字截断处理确实够长了";

        service.ask(6L, q, USER);

        ArgumentCaptor<Wrapper> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(sessionMapper).update(any(), cap.capture());
        String setSql = ((com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<?>) cap.getValue()).getSqlSet();
        assertTrue(setSql.contains("title"), () -> "首问应回填标题: " + setSql);
    }
}
