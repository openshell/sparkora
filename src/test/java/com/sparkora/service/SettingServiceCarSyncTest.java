package com.sparkora.service;

import com.sparkora.domain.entity.SettingEntity;
import com.sparkora.mapper.SettingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 车型库定时同步运行时设置(10-15-car-image-backfill R3/C1)单测:
 * 两字段 update 覆写(null 不改)、cron 非法拒绝落库(→ 400 路径)、首次初始化种子默认值与 V22 列默认一致。
 */
@ExtendWith(MockitoExtension.class)
class SettingServiceCarSyncTest {

    @Mock SettingMapper mapper;

    SettingService service;

    @BeforeEach
    void setUp() {
        service = new SettingService(mapper);
    }

    private static SettingEntity existingRow(Boolean enabled, String cron) {
        SettingEntity row = new SettingEntity();
        row.setId(1L);
        row.setKbEnabled(false);
        row.setWebSearchEnabled(true);
        row.setWebProviderOrder("TAVILY,SEARXNG");
        row.setCarSyncEnabled(enabled);
        row.setCarSyncCron(cron);
        return row;
    }

    @Test
    void update_覆写两字段_其余字段不动() {
        SettingEntity row = existingRow(false, "0 0 3 * * ?");
        when(mapper.selectById(1L)).thenReturn(row);
        when(mapper.updateById(any(SettingEntity.class))).thenReturn(1);

        SettingEntity out = service.update(null, null, null, true, "0 0 5 * * ?", 9L);

        assertEquals(Boolean.TRUE, out.getCarSyncEnabled());
        assertEquals("0 0 5 * * ?", out.getCarSyncCron());
        assertEquals(9L, out.getUpdatedBy());
        // 既有字段未被本次更新波及
        assertEquals("TAVILY,SEARXNG", out.getWebProviderOrder());
        assertEquals(Boolean.FALSE, out.getKbEnabled());
        verify(mapper).updateById(row);
    }

    @Test
    void update_cron非法_拒绝落库_IllegalStateException路径() {
        // cron 合法性兜底在读取行之前(不落库、不触 mapper);无需 stub 行读取
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update(null, null, null, null, "not-a-cron", 1L));

        assertTrue(ex.getMessage().contains("cron 表达式非法"));
        verify(mapper, never()).updateById(any(SettingEntity.class));
        verify(mapper, never()).insert(any(SettingEntity.class));
    }

    @Test
    void update_cron空串_视为不改_开关null不动() {
        SettingEntity row = existingRow(false, "0 0 7 * * ?");
        when(mapper.selectById(1L)).thenReturn(row);
        when(mapper.updateById(any(SettingEntity.class))).thenReturn(1);

        SettingEntity out = service.update(null, null, null, null, "  ", 1L);

        assertEquals("0 0 7 * * ?", out.getCarSyncCron());
        assertEquals(Boolean.FALSE, out.getCarSyncEnabled());
    }

    @Test
    void update_null_均不改_两字段保持原值() {
        SettingEntity row = existingRow(true, "0 0 3 * * ?");
        when(mapper.selectById(1L)).thenReturn(row);
        when(mapper.updateById(any(SettingEntity.class))).thenReturn(1);

        SettingEntity out = service.update(null, null, null, null, null, 1L);

        assertEquals(Boolean.TRUE, out.getCarSyncEnabled());
        assertEquals("0 0 3 * * ?", out.getCarSyncCron());
    }

    @Test
    void get_首次初始化种子_与V22列默认一致() {
        when(mapper.selectById(1L)).thenReturn(null);
        when(mapper.insert(any(SettingEntity.class))).thenReturn(1);

        SettingEntity row = service.get();

        assertNotNull(row);
        assertEquals(Boolean.FALSE, row.getCarSyncEnabled());
        assertEquals(SettingService.DEFAULT_CAR_SYNC_CRON, row.getCarSyncCron());
        assertEquals("0 0 3 * * ?", row.getCarSyncCron());
        verify(mapper).insert(any(SettingEntity.class));
    }

    @Test
    void isCarSyncEnabled_null安全_视为关闭() {
        SettingEntity row = existingRow(null, null);
        when(mapper.selectById(1L)).thenReturn(row);

        assertFalse(service.get().getCarSyncEnabled() != null && service.get().getCarSyncEnabled());
        assertNull(row.getCarSyncCron());
        // 调度侧判空口径:Boolean.TRUE.equals —— null 即关闭
        assertFalse(Boolean.TRUE.equals(service.get().getCarSyncEnabled()));
    }
}