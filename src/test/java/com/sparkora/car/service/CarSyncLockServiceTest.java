package com.sparkora.car.service;

import com.sparkora.mapper.CarSyncLockMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 车型同步锁单测(10-21):抢锁 owner 令牌、失败返回 null、释放按 owner 校验;
 * 过期判定的 now 必须与 expires_at 同源(JVM 时间),避免与 DB 时区不一致导致锁偏移。
 */
@ExtendWith(MockitoExtension.class)
class CarSyncLockServiceTest {

    @Mock CarSyncLockMapper mapper;

    @Test
    void 抢锁成功_返回owner_过期时间为70分钟_jvm时间同源() {
        when(mapper.tryAcquire(eq("car:goods:156"), eq("o1"), any(), any())).thenReturn(1);
        CarSyncLockService service = new CarSyncLockService(mapper);

        LocalDateTime before = LocalDateTime.now();
        assertEquals("o1", service.tryLock("car:goods:156", "o1"));

        ArgumentCaptor<LocalDateTime> expires = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).tryAcquire(eq("car:goods:156"), eq("o1"), expires.capture(), now.capture());
        assertFalse(now.getValue().isBefore(before), "now 必须取 JVM 当前时间");
        assertEquals(Duration.ofMinutes(70), Duration.between(now.getValue(), expires.getValue()),
                "TTL 需略大于 60min 陈旧阈值");
    }

    @Test
    void 锁被未过期持有_返回null() {
        when(mapper.tryAcquire(eq("car:job"), eq("job:9"), any(), any())).thenReturn(0);
        CarSyncLockService service = new CarSyncLockService(mapper);

        assertNull(service.tryLock("car:job", "job:9"));
    }

    @Test
    void 释放按owner校验() {
        CarSyncLockService service = new CarSyncLockService(mapper);

        service.unlock("car:job", "job:9");

        verify(mapper).release("car:job", "job:9");
    }

    @Test
    void 锁表故障降级放行_不阻断同步() {
        when(mapper.tryAcquire(eq("car:job"), eq("job:9"), any(), any()))
                .thenThrow(new RuntimeException("db down"));
        CarSyncLockService service = new CarSyncLockService(mapper);

        String owner = service.tryLock("car:job", "job:9");

        assertTrue(owner != null && owner.startsWith("degraded:"), "DB 故障降级为无锁令牌");
        service.unlock("car:job", owner);   // 释放失败仅告警,不抛
        verify(mapper).release("car:job", owner);
    }
}
