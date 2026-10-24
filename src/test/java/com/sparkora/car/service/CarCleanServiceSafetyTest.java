package com.sparkora.car.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.car.dto.CleanStats;
import com.sparkora.domain.entity.CarParamCleanEntity;
import com.sparkora.domain.entity.CarParamEntity;
import com.sparkora.domain.entity.CarVersionEntity;
import com.sparkora.mapper.CarParamCleanMapper;
import com.sparkora.mapper.CarParamMapper;
import com.sparkora.mapper.CarVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CarCleanServiceSafetyTest {

    @Mock CarParamMapper paramMapper;
    @Mock CarVersionMapper versionMapper;
    @Mock CarParamCleanMapper cleanMapper;
    @Mock AiParamCleaner aiCleaner;

    CarCleanService service;

    @BeforeEach
    void setUp() {
        service = new CarCleanService(paramMapper, versionMapper, cleanMapper, new ParamCleaner(), aiCleaner,
                new ObjectMapper());
    }

    private static CarVersionEntity version(Long id, int order) {
        CarVersionEntity v = new CarVersionEntity();
        v.setId(id);
        v.setVersionName("V" + order);
        v.setSortOrder(order);
        return v;
    }

    private static CarParamEntity param(String name, String valuesJson) {
        CarParamEntity p = new CarParamEntity();
        p.setId(1L);
        p.setModelId(7L);
        p.setParamName(name);
        p.setValuesJson(valuesJson);
        return p;
    }

    @Test
    void 参数为空_不删除旧清洗() {
        when(paramMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(versionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(version(1L, 0)));

        CleanStats stats = service.cleanForModel(7L);

        assertEquals(0, stats.total());
        verify(cleanMapper, never()).delete(any(Wrapper.class));
        verify(cleanMapper, never()).insert(any(CarParamCleanEntity.class));
    }

    @Test
    void 版本为空_不删除旧清洗() {
        when(paramMapper.selectList(any(Wrapper.class))).thenReturn(List.of(param("轴距(mm)", "[\"2820\"]")));
        when(versionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        CleanStats stats = service.cleanForModel(7L);

        assertEquals(0, stats.total());
        verify(cleanMapper, never()).delete(any(Wrapper.class));
        verify(cleanMapper, never()).insert(any(CarParamCleanEntity.class));
    }

    @Test
    void 正常参数_按版本生成清洗行() {
        when(paramMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(param("轴距(mm)", "[\"2820\",\"2830\"]")));
        when(versionMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(version(1L, 0), version(2L, 1)));

        CleanStats stats = service.cleanForModel(7L);

        assertEquals(2, stats.total());
        assertEquals(2, stats.getRule());
        verify(cleanMapper).delete(any(Wrapper.class));
        ArgumentCaptor<CarParamCleanEntity> cap = ArgumentCaptor.forClass(CarParamCleanEntity.class);
        verify(cleanMapper, org.mockito.Mockito.times(2)).insert(cap.capture());
        assertEquals(1L, cap.getAllValues().get(0).getVersionId());
        assertEquals(2L, cap.getAllValues().get(1).getVersionId());
        assertEquals("轴距", cap.getAllValues().get(0).getParamKey());
        assertEquals("2820", cap.getAllValues().get(0).getParamValue());
    }

    @Test
    void 值数量多于版本_只清洗对齐版本_不产生null版本行() {
        when(paramMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(param("轴距(mm)", "[\"2820\",\"2830\",\"2840\"]")));
        when(versionMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(version(1L, 0), version(2L, 1)));

        CleanStats stats = service.cleanForModel(7L);

        assertEquals(2, stats.total());
        ArgumentCaptor<CarParamCleanEntity> cap = ArgumentCaptor.forClass(CarParamCleanEntity.class);
        verify(cleanMapper, org.mockito.Mockito.times(2)).insert(cap.capture());
        assertEquals(List.of(1L, 2L), cap.getAllValues().stream().map(CarParamCleanEntity::getVersionId).toList());
    }
}
