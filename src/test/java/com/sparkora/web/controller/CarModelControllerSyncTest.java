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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 10-21-knowledge-car-sync-ux R2：详情页「同步」是阻塞接口，失败也必须回写车型
 * FAILED + last_sync_error（与异步任务 catch 同口径），且回写异常不覆盖原始错误。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CarModelControllerSyncTest {

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

    @Test
    void 阻塞同步失败_回写车型失败状态_响应保留原始错误() throws Exception {
        CarModelEntity row = new CarModelEntity();
        row.setId(7L);
        row.setGoodsId("156");
        when(service.get(7L)).thenReturn(row);
        when(jobService.syncOneExclusive("156")).thenThrow(new RuntimeException("官网采集失败"));

        mvc.perform(post("/api/car/models/7/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("官网采集失败"));

        verify(service).markSyncFailed("156", "官网采集失败");
    }

    @Test
    void 阻塞同步失败_回写本身异常不覆盖原始错误() throws Exception {
        CarModelEntity row = new CarModelEntity();
        row.setId(7L);
        row.setGoodsId("156");
        when(service.get(7L)).thenReturn(row);
        when(jobService.syncOneExclusive("156")).thenThrow(new RuntimeException("官网采集失败"));
        doThrow(new RuntimeException("db down")).when(service).markSyncFailed(anyString(), anyString());

        mvc.perform(post("/api/car/models/7/sync"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("官网采集失败"));
    }

    @Test
    void 车型不存在_404_不写失败状态() throws Exception {
        when(service.get(9L)).thenReturn(null);

        mvc.perform(post("/api/car/models/9/sync"))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("车型不存在"));

        verify(service, never()).markSyncFailed(anyString(), anyString());
    }
}
