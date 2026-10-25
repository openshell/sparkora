package com.sparkora.web.controller;

import com.sparkora.car.service.CarCleanRepairService;
import com.sparkora.car.service.CarModelService;
import com.sparkora.car.service.CarRagService;
import com.sparkora.car.service.CarSyncJobService;
import com.sparkora.domain.entity.CarModelEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 10-24 车型运维 P0 接口层契约:重建向量任务 / 单车型清洗任务 / backfill 定向 body。
 * 错误映射 400(参数)/404(车型不存在)/409(任务冲突)与旧 createCleanJob 同款。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CarModelControllerOpsJobTest {

    @Mock CarModelService service;
    @Mock CarRagService ragService;
    @Mock CarSyncJobService jobService;
    @Mock CarCleanRepairService cleanRepairService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new CarModelController(service, ragService, jobService, cleanRepairService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static CarModelEntity model(Long id, String goodsId) {
        CarModelEntity m = new CarModelEntity();
        m.setId(id);
        m.setGoodsId(goodsId);
        return m;
    }

    @Test
    void 创建重建向量任务_不传body_全库返回jobId() throws Exception {
        when(jobService.createRebuildJob((List<String>) null)).thenReturn(42L);
        mvc.perform(post("/api/car/models/rebuild-jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.jobId").value(42));
        // 无 body = 全库重建(传 null 给 service,旧行为不变)
        verify(jobService).createRebuildJob((List<String>) null);
    }

    @Test
    void 创建重建向量任务_带goodsIds_定向透传() throws Exception {
        when(jobService.createRebuildJob(List.of("156"))).thenReturn(7L);
        mvc.perform(post("/api/car/models/rebuild-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsIds\":[\"156\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.jobId").value(7));
        verify(jobService).createRebuildJob(List.of("156"));
    }

    @Test
    void 创建重建向量任务_定向未知车型_400() throws Exception {
        when(jobService.createRebuildJob(List.of("999")))
                .thenThrow(new IllegalArgumentException("车型不存在: 999"));
        mvc.perform(post("/api/car/models/rebuild-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsIds\":[\"999\"]}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("车型不存在: 999"));
    }

    @Test
    void 创建重建向量任务_参数错误_400() throws Exception {
        when(jobService.createRebuildJob((List<String>) null))
                .thenThrow(new IllegalArgumentException("库内没有可重建向量的车型"));
        mvc.perform(post("/api/car/models/rebuild-jobs"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("库内没有可重建向量的车型"));
    }

    @Test
    void 创建重建向量任务_任务冲突_409() throws Exception {
        when(jobService.createRebuildJob((List<String>) null))
                .thenThrow(new IllegalStateException("已有同步任务运行中,请稍后重试"));
        mvc.perform(post("/api/car/models/rebuild-jobs"))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value("已有同步任务运行中,请稍后重试"));
    }

    @Test
    void 创建单车型清洗任务_车型不存在_404() throws Exception {
        when(service.get(9L)).thenReturn(null);
        mvc.perform(post("/api/car/models/9/clean-jobs"))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("车型不存在"));
        verify(jobService, never()).createCleanOneJob(anyString());
    }

    @Test
    void 创建单车型清洗任务_缺goodsId_400() throws Exception {
        when(service.get(7L)).thenReturn(model(7L, null));
        mvc.perform(post("/api/car/models/7/clean-jobs"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("该车型缺少官网 goodsId,无法任务化清洗"));
        verify(jobService, never()).createCleanOneJob(anyString());
    }

    @Test
    void 创建单车型清洗任务_正常_返回jobId() throws Exception {
        when(service.get(7L)).thenReturn(model(7L, "156"));
        when(jobService.createCleanOneJob("156")).thenReturn(42L);
        mvc.perform(post("/api/car/models/7/clean-jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.jobId").value(42));
        verify(jobService).createCleanOneJob("156");
    }

    @Test
    void 创建单车型清洗任务_任务冲突_409() throws Exception {
        when(service.get(7L)).thenReturn(model(7L, "156"));
        when(jobService.createCleanOneJob("156"))
                .thenThrow(new IllegalStateException("已有同步任务运行中,请稍后重试"));
        mvc.perform(post("/api/car/models/7/clean-jobs"))
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void backfill_带goodsIds_定向透传() throws Exception {
        when(jobService.createBackfillJob(false, List.of("156"))).thenReturn(7L);
        mvc.perform(post("/api/car/sync/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsIds\":[\"156\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(7));
        verify(jobService).createBackfillJob(false, List.of("156"));
    }

    @Test
    void backfill_未知goodsIds_400() throws Exception {
        when(jobService.createBackfillJob(false, List.of("999")))
                .thenThrow(new IllegalArgumentException("车型不存在: 999"));
        mvc.perform(post("/api/car/sync/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsIds\":[\"999\"]}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("车型不存在: 999"));
    }

    @Test
    void backfill_不传body_行为不变() throws Exception {
        when(jobService.createBackfillJob(true, null)).thenReturn(7L);
        mvc.perform(post("/api/car/sync/backfill?mode=FULL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(7));
        verify(jobService).createBackfillJob(true, null);
    }
}