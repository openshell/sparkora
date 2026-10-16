package com.sparkora.web.controller;

import com.sparkora.domain.entity.SettingEntity;
import com.sparkora.service.SettingService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SettingController HTTP 契约测试(09-25-brief-web-search AC-13/AC-14):
 * 策略字段非法 → R.fail(400) 中文提示;合法 → 透传 SettingService;
 * 既有字段(总开关)增量兼容。10-15-car-image-backfill R3:扩车型库同步字段透传 + cron 非法 400。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettingControllerTest {

    @Mock SettingService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SettingController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void 非法策略_400中文提示_不落库() throws Exception {
        String body = "{\"webProviderOrder\":\"BOTH\"}";
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("外部搜索策略仅支持 TAVILY/SEARXNG/SERPER 的顺序组合"));
        verify(service, never()).update(any(), any(), any(), any(), any(), any());
    }

    /** 10-04-serper-provider A-R9:含 SERPER 的顺序组合应被接受并透传。 */
    @Test
    void 含SERPER顺序_合法_透传并落库() throws Exception {
        SettingEntity row = new SettingEntity();
        row.setId(1L);
        row.setWebProviderOrder("TAVILY,SERPER,SEARXNG");
        when(service.update(isNull(), isNull(), eq("TAVILY,SERPER,SEARXNG"), isNull(), isNull(), isNull())).thenReturn(row);

        String body = "{\"webProviderOrder\":\"TAVILY,SERPER,SEARXNG\"}";
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.webProviderOrder").value("TAVILY,SERPER,SEARXNG"));
        verify(service).update(isNull(), isNull(), eq("TAVILY,SERPER,SEARXNG"), isNull(), isNull(), isNull());
    }

    /** A-R9:仅 SERPER 优先的两源顺序也应合法。 */
    @Test
    void SERPER优先两源_合法_透传() throws Exception {
        SettingEntity row = new SettingEntity();
        row.setId(1L);
        row.setWebProviderOrder("SERPER,TAVILY");
        when(service.update(isNull(), isNull(), eq("SERPER,TAVILY"), isNull(), isNull(), isNull())).thenReturn(row);

        String body = "{\"webProviderOrder\":\"SERPER,TAVILY\"}";
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.webProviderOrder").value("SERPER,TAVILY"));
    }

    @Test
    void 合法策略_透传并返回统一响应() throws Exception {
        SettingEntity row = new SettingEntity();
        row.setId(1L);
        row.setWebProviderOrder("SEARXNG,TAVILY");
        when(service.update(isNull(), isNull(), eq("SEARXNG,TAVILY"), isNull(), isNull(), isNull())).thenReturn(row);

        String body = "{\"webProviderOrder\":\"SEARXNG,TAVILY\"}";
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.webProviderOrder").value("SEARXNG,TAVILY"));
    }

    @Test
    void 空策略_视为不改_仍透传其余字段() throws Exception {
        SettingEntity row = new SettingEntity();
        row.setId(1L);
        row.setKbEnabled(true);
        row.setWebSearchEnabled(true);
        when(service.update(eq(true), eq(true), isNull(), isNull(), isNull(), isNull())).thenReturn(row);

        String body = "{\"kbEnabled\":true,\"webSearchEnabled\":true}";
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(service).update(eq(true), eq(true), isNull(), isNull(), isNull(), isNull());
    }

    /** 10-15-car-image-backfill R3:车型库同步开关+cron 透传(运行时设置,写仅 ADMIN)。 */
    @Test
    void 车型库同步字段_透传服务() throws Exception {
        SettingEntity row = new SettingEntity();
        row.setId(1L);
        row.setCarSyncEnabled(true);
        row.setCarSyncCron("0 0 4 * * ?");
        when(service.update(isNull(), isNull(), isNull(), eq(true), eq("0 0 4 * * ?"), isNull())).thenReturn(row);

        String body = "{\"carSyncEnabled\":true,\"carSyncCron\":\"0 0 4 * * ?\"}";
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.carSyncEnabled").value(true))
                .andExpect(jsonPath("$.data.carSyncCron").value("0 0 4 * * ?"));
        verify(service).update(isNull(), isNull(), isNull(), eq(true), eq("0 0 4 * * ?"), isNull());
    }

    /** 10-15-car-image-backfill R3:车型库 cron 非法 → 服务层 IllegalArgumentException → R.fail(400, 中文提示)。 */
    @Test
    void 车型库cron非法_400中文提示() throws Exception {
        when(service.update(isNull(), isNull(), isNull(), isNull(), eq("abc"), isNull()))
                .thenThrow(new IllegalArgumentException("车型库定时同步 cron 表达式非法: abc"));

        String body = "{\"carSyncCron\":\"abc\"}";
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("车型库定时同步 cron 表达式非法: abc"));
    }

    /** DTO @Size 兜底:超列宽(VARCHAR(64))的 cron 在参数校验层 400,不落库。 */
    @Test
    void 车型库cron超长_参数校验400() throws Exception {
        StringBuilder longCron = new StringBuilder();
        for (int i = 0; i < 65; i++) longCron.append('0');
        String body = "{\"carSyncCron\":\"" + longCron + "\"}";
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("车型库定时同步 cron 表达式过长"));
        verify(service, never()).update(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 非法JSON_统一400() throws Exception {
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
