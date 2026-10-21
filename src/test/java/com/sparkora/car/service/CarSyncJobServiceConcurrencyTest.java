package com.sparkora.car.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.domain.entity.CarSyncJobEntity;
import com.sparkora.mapper.CarModelMapper;
import com.sparkora.mapper.CarSyncJobMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 同步任务可靠性单测(10-21):提交后触发、全局锁冲突、取消、心跳陈旧判定。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CarSyncJobServiceConcurrencyTest {

    @Mock CarSyncJobMapper jobMapper;
    @Mock CarModelMapper modelMapper;
    @Mock CarModelService modelService;

    CarSyncJobService service;

    @BeforeEach
    void setUp() {
        service = new CarSyncJobService(jobMapper, modelMapper, modelService, new ObjectMapper());
        when(jobMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
    }

    @AfterEach
    void clearTx() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 事务提交后才触发异步任务() {
        CarSyncJobService selfMock = mock(CarSyncJobService.class);
        setField(service, "self", selfMock);
        doAnswer(inv -> {
            ((CarSyncJobEntity) inv.getArgument(0)).setId(42L);
            return 1;
        }).when(jobMapper).insert(any(CarSyncJobEntity.class));

        TransactionSynchronizationManager.initSynchronization();
        Long jobId = service.createJob(List.of("156"), "SELECTED");

        assertEquals(42L, jobId);
        verify(selfMock, never()).runJob(anyLong(), anyList());
        // 模拟事务提交
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        verify(selfMock).runJob(eq(42L), eq(List.of("156")));
    }

    @Test
    void 全局锁冲突_任务落FAILED并返回冲突() {
        CarSyncLockService lock = mock(CarSyncLockService.class);
        setField(service, "lockService", lock);
        when(lock.tryLock(any(), any())).thenReturn(null);
        doAnswer(inv -> {
            ((CarSyncJobEntity) inv.getArgument(0)).setId(9L);
            return 1;
        }).when(jobMapper).insert(any(CarSyncJobEntity.class));

        assertThrows(IllegalStateException.class, () -> service.createJob(List.of("156"), "SELECTED"));

        ArgumentCaptor<CarSyncJobEntity> cap = ArgumentCaptor.forClass(CarSyncJobEntity.class);
        verify(jobMapper).updateById(cap.capture());
        assertEquals("FAILED", cap.getValue().getStatus());
        assertTrue(cap.getValue().getErrorMsg().contains("已有同步任务"));
    }

    @Test
    void 取消请求_置cancel_requested() {
        when(jobMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        assertTrue(service.cancel(9L));

        ArgumentCaptor<Wrapper<CarSyncJobEntity>> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(jobMapper).update(isNull(), cap.capture());
        AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) cap.getValue();
        assertTrue(w.getSqlSet().contains("cancel_requested"));
        assertTrue(w.getParamNameValuePairs().containsValue(1));
    }

    @Test
    void 陈旧判定使用心跳时间优先() {
        when(jobMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        service.markStaleRunningAsFailed();

        ArgumentCaptor<Wrapper<CarSyncJobEntity>> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(jobMapper).update(isNull(), cap.capture());
        String sql = ((AbstractWrapper<?, ?, ?>) cap.getValue()).getSqlSegment();
        assertTrue(sql.contains("heartbeat_at"), sql);
        assertTrue(sql.contains("started_at"), sql);
        assertFalse(sql.contains("updated_at"), sql);
    }

    @Test
    void 已有新鲜RUNNING_拒绝创建任务() {
        when(jobMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertThrows(IllegalStateException.class, () -> service.createJob(List.of("156"), "SELECTED"));
        verify(jobMapper, never()).insert(any(CarSyncJobEntity.class));
    }

    @Test
    void 非法goodsId_400语义() {
        assertThrows(IllegalArgumentException.class, () -> service.createJob(List.of("abc"), "SELECTED"));
        assertThrows(IllegalArgumentException.class, () -> service.createJob(List.of(), "SELECTED"));
    }
}
