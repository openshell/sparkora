package com.sparkora.car.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sparkora.ai.EmbeddingBatchRunner;
import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.config.AiProperties;
import com.sparkora.domain.entity.CarChunkEntity;
import com.sparkora.domain.entity.CarModelEntity;
import com.sparkora.mapper.CarDocEmbeddingMapper;
import com.sparkora.mapper.CarChunkMapper;
import com.sparkora.mapper.CarModelMapper;
import com.sparkora.mapper.CarParamCleanMapper;
import com.sparkora.mapper.CarParamGroupMapper;
import com.sparkora.mapper.CarVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAR 写入事务边界单测（09-27 R3/AC6）。
 *
 * 断言：rebuildForModel 的持久化经自注入代理（self）走独立事务方法 {@code persistCarChunk}；
 * 修复此前 {@code @Transactional insertDocWithEmbedding} 由线程池 lambda 内 this 直调、代理不生效的问题。
 * doc 插入与向量插入同在 {@code persistCarChunk} 内（先 doc 后向量），失败则一并回滚。
 */
class CarChunkTransactionTest {

    static class FakeEmbeddingClient extends EmbeddingClient {
        FakeEmbeddingClient() { super(new AiProperties()); }
        @Override public String embed(String text) { return "[0.1,0.2]"; }
    }

    private static void setSelf(CarChunkService target, CarChunkService proxy) {
        try {
            Field f = CarChunkService.class.getDeclaredField("self");
            f.setAccessible(true);
            f.set(target, proxy);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CarChunkService newService(CarModelMapper modelMapper, CarParamGroupMapper groupMapper,
                                     CarVersionMapper versionMapper, CarParamCleanMapper cleanMapper,
                                     CarChunkMapper docMapper, CarDocEmbeddingMapper embMapper,
                                     EmbeddingClient client) {
        return new CarChunkService(modelMapper, groupMapper, cleanMapper, versionMapper,
                docMapper, embMapper, client, new EmbeddingBatchRunner(client), new ObjectMapper());
    }

    @Test
    void rebuild_经自注入代理走独立事务持久化() {
        CarModelMapper modelMapper = mock(CarModelMapper.class);
        CarParamGroupMapper groupMapper = mock(CarParamGroupMapper.class);
        CarVersionMapper versionMapper = mock(CarVersionMapper.class);
        CarParamCleanMapper cleanMapper = mock(CarParamCleanMapper.class);
        CarChunkMapper docMapper = mock(CarChunkMapper.class);
        CarDocEmbeddingMapper embMapper = mock(CarDocEmbeddingMapper.class);
        FakeEmbeddingClient client = new FakeEmbeddingClient();
        CarChunkService service = newService(modelMapper, groupMapper, versionMapper, cleanMapper,
                docMapper, embMapper, client);
        CarChunkService spySelf = spy(service);
        setSelf(service, spySelf);

        CarModelEntity m = new CarModelEntity();
        m.setId(39L);
        m.setName("海狮08EV");
        when(modelMapper.selectById(39L)).thenReturn(m);
        when(groupMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(versionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(docMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.rebuildForModel(39L);

        // 持久化经代理方法（独立事务边界）；MODEL_INFO 块必有一条
        verify(spySelf).persistCarChunk(any(CarChunkEntity.class), eq("[0.1,0.2]"));
    }

    @Test
    void persistCarChunk_先插doc后插向量() {
        CarModelMapper modelMapper = mock(CarModelMapper.class);
        CarParamGroupMapper groupMapper = mock(CarParamGroupMapper.class);
        CarVersionMapper versionMapper = mock(CarVersionMapper.class);
        CarParamCleanMapper cleanMapper = mock(CarParamCleanMapper.class);
        CarChunkMapper docMapper = mock(CarChunkMapper.class);
        CarDocEmbeddingMapper embMapper = mock(CarDocEmbeddingMapper.class);
        FakeEmbeddingClient client = new FakeEmbeddingClient();
        CarChunkService service = newService(modelMapper, groupMapper, versionMapper, cleanMapper,
                docMapper, embMapper, client);

        CarChunkEntity doc = new CarChunkEntity();
        doc.setModelId(39L);
        doc.setChunkType("MODEL_INFO");
        doc.setChunkText("车型：海狮08EV");
        doAnswer(inv -> { ((CarChunkEntity) inv.getArgument(0)).setId(777L); return 1; })
                .when(docMapper).insert(any(CarChunkEntity.class));

        service.persistCarChunk(doc, "[0.1,0.2]");

        var inOrder = inOrder(docMapper, embMapper);
        inOrder.verify(docMapper).insert(doc);
        inOrder.verify(embMapper).insert(eq(777L), eq(39L), eq("[0.1,0.2]"), any());
    }

    @Test
    void 无代理时退化为直写不NPE() {
        CarModelMapper modelMapper = mock(CarModelMapper.class);
        CarParamGroupMapper groupMapper = mock(CarParamGroupMapper.class);
        CarVersionMapper versionMapper = mock(CarVersionMapper.class);
        CarParamCleanMapper cleanMapper = mock(CarParamCleanMapper.class);
        CarChunkMapper docMapper = mock(CarChunkMapper.class);
        CarDocEmbeddingMapper embMapper = mock(CarDocEmbeddingMapper.class);
        FakeEmbeddingClient client = new FakeEmbeddingClient();
        CarChunkService service = newService(modelMapper, groupMapper, versionMapper, cleanMapper,
                docMapper, embMapper, client);

        CarModelEntity m = new CarModelEntity();
        m.setId(39L);
        when(modelMapper.selectById(39L)).thenReturn(m);
        when(groupMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(versionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(docMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.rebuildForModel(39L);   // self == null

        verify(docMapper).insert(any(CarChunkEntity.class));
        verify(embMapper).insert(any(), anyLong(), anyString(), any());
    }
}
