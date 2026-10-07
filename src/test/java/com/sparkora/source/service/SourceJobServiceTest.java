package com.sparkora.source.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.domain.entity.SourceJobEntity;
import com.sparkora.mapper.SourceJobMapper;
import com.sparkora.mapper.SourceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采集任务「陈旧自愈 / 防重叠 / 终态」单测(10-05-source-crawl-base,AC-B4/B5)。Mockito 不连库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SourceJobServiceTest {

    @Mock SourceJobMapper jobMapper;
    @Mock SourceMapper sourceMapper;
    @Mock SourceCollectService collectService;

    SourceJobService service;

    @BeforeEach
    void setUp() {
        service = new SourceJobService(jobMapper, sourceMapper, collectService, new ObjectMapper());
    }

    @Test
    void 陈旧置失败_条件含RUNNING与started_at阈值且置终态() {
        when(jobMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        int updated = service.markStaleRunningAsFailed();

        assertEquals(1, updated);
        ArgumentCaptor<Wrapper<SourceJobEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(jobMapper).update(isNull(), captor.capture());
        AbstractWrapper aw = (AbstractWrapper) captor.getValue();
        String sql = aw.getSqlSegment();
        assertTrue(sql.contains("status") && sql.contains("started_at"), "条件列缺失: " + sql);
        assertTrue(sql.contains("<"), "应按 started_at 早于阈值过滤: " + sql);
        assertTrue(aw.getParamNameValuePairs().containsValue("RUNNING"), "应过滤 RUNNING");
        String set = captor.getValue().getSqlSet();
        assertTrue(set.contains("status") && set.contains("finished_at") && set.contains("error_msg"), "SET 列缺失: " + set);
        assertTrue(aw.getParamNameValuePairs().containsValue("FAILED"), "应置 FAILED");
    }

    @Test
    void hasFreshRunning_全局条件含RUNNING与started_at不早于阈值() {
        when(jobMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertTrue(service.hasFreshRunning());
        ArgumentCaptor<Wrapper<SourceJobEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(jobMapper).selectCount(captor.capture());
        AbstractWrapper aw = (AbstractWrapper) captor.getValue();
        String sql = aw.getSqlSegment();
        assertTrue(sql.contains("status") && sql.contains("started_at"), "条件列缺失: " + sql);
        assertTrue(sql.contains(">="), "应只算未过期: " + sql);
        assertTrue(aw.getParamNameValuePairs().containsValue("RUNNING"));
    }

    @Test
    void hasFreshRunning_按源过滤含source_id() {
        when(jobMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertTrue(service.hasFreshRunning(7L));
        ArgumentCaptor<Wrapper<SourceJobEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(jobMapper).selectCount(captor.capture());
        AbstractWrapper aw = (AbstractWrapper) captor.getValue();
        assertTrue(aw.getSqlSegment().contains("source_id"), "应按源过滤: " + aw.getSqlSegment());
    }

    @Test
    void 终态_全成SUCCESS_部分PARTIAL_全败FAILED() {
        assertFinish("SUCCESS", 2, 0, 0);
        assertFinish("PARTIAL", 2, 1, 0);
        assertFinish("FAILED", 0, 1, 0);
    }

    private void assertFinish(String expected, int success, int failed, int degraded) {
        SourceJobEntity job = new SourceJobEntity();
        job.setSourceId(1L);
        job.setStatus("RUNNING");
        SourceCollectService.CollectOutcome out =
                new SourceCollectService.CollectOutcome(success, failed, degraded, List.<Map<String, Object>>of());
        service.finish(job, out);
        assertEquals(expected, job.getStatus());
        assertEquals(success + failed + degraded, job.getTotal());
        verify(jobMapper).updateById(job);
    }

    @Test
    void 降级也算未完成_PARTIAL不标记批次完成() {
        SourceJobEntity job = new SourceJobEntity();
        job.setSourceId(1L);
        job.setBatchKey("1-2026-10");
        // 有成功 + 有降级 → PARTIAL,不应回写 last_batch_key
        service.finish(job, new SourceCollectService.CollectOutcome(1, 0, 1, List.<Map<String, Object>>of()));
        assertEquals("PARTIAL", job.getStatus());
        verify(sourceMapper, org.mockito.Mockito.never()).update(isNull(), any(Wrapper.class));
    }
}
