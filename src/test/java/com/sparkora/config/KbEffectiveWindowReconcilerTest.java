package com.sparkora.config;

import com.sparkora.ai.vector.VectorStoreService;
import com.sparkora.domain.entity.KbChunkEntity;
import com.sparkora.domain.entity.KbDocEntity;
import com.sparkora.mapper.KbChunkMapper;
import com.sparkora.mapper.KbDocMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KB 生效期对账 Runner 单测（10-03 E3 D-f）：按当前日期重算 active 并经 setActive 同步；
 * 异常不阻断。
 */
class KbEffectiveWindowReconcilerTest {

    @Test
    void reconcile_未来生效文档_active置false() {
        KbDocMapper docMapper = mock(KbDocMapper.class);
        KbChunkMapper chunkMapper = mock(KbChunkMapper.class);
        VectorStoreService store = mock(VectorStoreService.class);
        KbEffectiveWindowReconciler r = new KbEffectiveWindowReconciler(docMapper, chunkMapper, store);

        KbDocEntity d = new KbDocEntity();
        d.setId(1L);
        d.setEnabled(true);
        d.setEffectiveFrom(LocalDate.now().plusDays(3));
        when(docMapper.selectList(any())).thenReturn(List.of(d));
        KbChunkEntity c = new KbChunkEntity();
        c.setId(11L);
        when(chunkMapper.selectList(any())).thenReturn(List.of(c));

        r.reconcile();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Boolean> active = ArgumentCaptor.forClass(Boolean.class);
        verify(store).setActive(eq("KB"), eq(List.of(11L)), active.capture());
        org.junit.jupiter.api.Assertions.assertFalse(active.getValue());
    }

    @Test
    void reconcile_异常仅警告不抛出() {
        KbDocMapper docMapper = mock(KbDocMapper.class);
        KbChunkMapper chunkMapper = mock(KbChunkMapper.class);
        VectorStoreService store = mock(VectorStoreService.class);
        when(docMapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        KbEffectiveWindowReconciler r = new KbEffectiveWindowReconciler(docMapper, chunkMapper, store);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(r::reconcile);
    }
}
