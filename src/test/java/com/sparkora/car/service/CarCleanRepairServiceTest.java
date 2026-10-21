package com.sparkora.car.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sparkora.car.dto.CleanStats;
import com.sparkora.domain.entity.CarModelEntity;
import com.sparkora.mapper.CarModelMapper;
import com.sparkora.mapper.CarParamCleanMapper;
import com.sparkora.mapper.CarParamMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CarCleanRepairServiceTest {

    @Mock CarModelMapper modelMapper;
    @Mock CarParamMapper paramMapper;
    @Mock CarParamCleanMapper cleanMapper;
    @Mock CarCleanService cleanService;

    CarCleanRepairService service;

    @BeforeEach
    void setUp() {
        service = new CarCleanRepairService(modelMapper, paramMapper, cleanMapper, cleanService);
    }

    private static CarModelEntity model(Long id, String goodsId, String name) {
        CarModelEntity m = new CarModelEntity();
        m.setId(id);
        m.setGoodsId(goodsId);
        m.setName(name);
        return m;
    }

    private static CleanStats stats() {
        CleanStats s = new CleanStats();
        s.count("RULE");
        s.count("RULE");
        return s;
    }

    @Test
    void 补清洗_只处理有参数无清洗的车型() {
        CarModelEntity target = model(7L, "156", "大唐EV");
        when(modelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(target));
        when(modelMapper.selectById(7L)).thenReturn(target);
        // cleanMissing 预检 + cleanOne 内部读取:params 恒为 2;cleans 首次为 0,清洗后为 2。
        when(paramMapper.selectCount(any(Wrapper.class))).thenReturn(2L);
        when(cleanMapper.selectCount(any(Wrapper.class))).thenReturn(0L, 0L, 2L);
        when(cleanService.cleanForModel(7L)).thenReturn(stats());

        CarCleanRepairService.CleanRepairSummary result = service.cleanMissing();

        assertEquals(1, result.total());
        assertEquals(1, result.cleaned());
        assertEquals(0, result.skipped());
        assertEquals(0, result.failed());
        verify(cleanService).cleanForModel(7L);
    }

    @Test
    void 单车型补清洗_返回前后数量() {
        when(modelMapper.selectById(7L)).thenReturn(model(7L, "156", "大唐EV"));
        when(paramMapper.selectCount(any(Wrapper.class))).thenReturn(2L);
        when(cleanMapper.selectCount(any(Wrapper.class))).thenReturn(0L, 2L);
        when(cleanService.cleanForModel(eq(7L))).thenReturn(stats());

        CarCleanRepairService.CleanRepairResult result = service.cleanOne(7L);

        assertEquals(2, result.params());
        assertEquals(0, result.cleansBefore());
        assertEquals(2, result.cleansAfter());
        assertEquals(2, result.rule());
    }
}
