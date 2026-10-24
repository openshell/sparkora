package com.sparkora.car.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.EmbedStats;
import com.sparkora.ai.vector.VectorStoreService;
import com.sparkora.car.client.BydCmsClient;
import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.car.client.SourceIncompleteException;
import com.sparkora.car.client.SourceUnavailableException;
import com.sparkora.car.dto.CleanStats;
import com.sparkora.car.dto.GoodsAttrListDto;
import com.sparkora.car.dto.GoodsInfoDto;
import com.sparkora.car.dto.GoodsParamsDto;
import com.sparkora.domain.entity.CarModelEntity;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.mapper.CarChunkMapper;
import com.sparkora.mapper.CarModelMapper;
import com.sparkora.mapper.CarParamCleanMapper;
import com.sparkora.mapper.CarParamGroupMapper;
import com.sparkora.mapper.CarParamMapper;
import com.sparkora.mapper.CarVersionMapper;
import com.sparkora.mapper.ImageAssetMapper;
import com.sparkora.service.ImageService;
import com.sparkora.storage.ImageStorage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 同步数据安全单测(10-21-car-sync-safety P0):
 * 空/不完整响应零业务写入、字段缺失保留旧值、图片部分失败不缩短图集、
 * 向量失败 PARTIAL 且不前进 last_sync_at、增量只图片/只参数定向分派。
 */
@ExtendWith(MockitoExtension.class)
class CarModelServiceSafetyTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Mock BydCmsClient client;
    @Mock CarModelMapper modelMapper;
    @Mock CarVersionMapper versionMapper;
    @Mock CarParamGroupMapper groupMapper;
    @Mock CarParamMapper paramMapper;
    @Mock CarParamCleanMapper cleanMapper;
    @Mock CarChunkService docService;
    @Mock CarCleanService cleanService;
    @Mock CarChunkMapper chunkMapper;
    @Mock ImageAssetMapper imageMapper;
    @Mock ImageStorage imageStorage;
    @Mock ImageService imageService;
    @Mock EmbeddingClient embeddingClient;
    @Mock VectorStoreService vectorStoreService;

    CarModelService service;

    @BeforeEach
    void setUp() {
        service = new CarModelService(client, modelMapper, versionMapper, groupMapper, paramMapper,
                cleanMapper, docService, cleanService, chunkMapper, imageMapper, imageStorage,
                new ObjectMapper(), imageService, embeddingClient, vectorStoreService);
        service.setImageDownloader(CarImageDownloader.permissiveForTest());
    }

    private static CleanStats cleanStats() {
        CleanStats st = new CleanStats();
        st.count("RULE");
        return st;
    }

    private static GoodsInfoDto info(String goodsId, String name) {
        GoodsInfoDto.Item item = new GoodsInfoDto.Item();
        item.setId(goodsId);
        item.setName(name);
        GoodsInfoDto dto = new GoodsInfoDto();
        GoodsInfoDto.GoodsInfoList list = new GoodsInfoDto.GoodsInfoList();
        list.setItems(List.of(item));
        dto.setGoodsinfoList(list);
        return dto;
    }

    private static GoodsParamsDto.ParamsData params() {
        GoodsParamsDto.ParamRow row = new GoodsParamsDto.ParamRow();
        row.setName("长×宽×高(mm)");
        row.setValue(List.of("4780×1890×1670"));
        GoodsParamsDto.Config cfg = new GoodsParamsDto.Config();
        cfg.setName("尺寸参数");
        cfg.setValue(List.of(row));
        GoodsParamsDto.ParamsData data = new GoodsParamsDto.ParamsData();
        data.setConfigs(List.of(cfg));
        return data;
    }

    private static List<GoodsAttrListDto.Version> versions() {
        GoodsAttrListDto.Version v = new GoodsAttrListDto.Version();
        v.setName("205领航版");
        return List.of(v);
    }

    private static CarModelEntity row() {
        CarModelEntity m = new CarModelEntity();
        m.setId(7L);
        m.setGoodsId("156");
        m.setName("旧名");
        m.setPriceRange("旧价格");
        m.setFeatures("[\"旧卖点\"]");
        m.setIntroImages("[9]");
        m.setCarRights("{\"title\":\"权益\"}");
        m.setCoverImageId(5L);
        return m;
    }

    private static List<Wrapper<CarModelEntity>> captureUpdates(CarModelMapper mapper) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<CarModelEntity>> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, atLeastOnce()).update(isNull(), cap.capture());
        return cap.getAllValues();
    }

    private static boolean setsColumn(Wrapper<CarModelEntity> w, String column) {
        String sql = w.getSqlSet();
        return sql != null && sql.contains(column);
    }

    @Test
    void 空goodsInfo_标记EMPTY_零业务写入_不写lastSyncAt() {
        when(client.goodsInfo("156")).thenReturn(new GoodsInfoDto());

        assertThrows(SourceIncompleteException.class, () -> service.syncOne("156"));

        List<Wrapper<CarModelEntity>> updates = captureUpdates(modelMapper);
        assertTrue(updates.stream().anyMatch(w -> setsColumn(w, "source_state")
                && ((AbstractWrapper<?, ?, ?>) w).getParamNameValuePairs().containsValue("EMPTY")));
        assertTrue(updates.stream().noneMatch(w -> setsColumn(w, "last_sync_at")), "空数据不得前进 last_sync_at");
        verifyNoInteractions(versionMapper, groupMapper, paramMapper, cleanMapper, cleanService,
                docService, chunkMapper, vectorStoreService);
        verify(modelMapper, never()).insert(any(CarModelEntity.class));
        verify(modelMapper, never()).updateById(any(CarModelEntity.class));
        verify(imageService, never()).persistOrReuse(any(), anyString(), any(ImageAssetEntity.class));
    }

    /** 删除必须与全局同步任务互斥:否则尚未处理到该车型的任务会用 syncOne 把它复活。 */
    @Test
    void 删除_同步任务运行中_拒绝且不删数据() throws Exception {
        CarModelEntity row = row();
        when(modelMapper.selectById(7L)).thenReturn(row);
        CarSyncLockService lock = org.mockito.Mockito.mock(CarSyncLockService.class);
        when(lock.tryLock(CarSyncLockService.GLOBAL_JOB_KEY)).thenReturn(null);
        setLockService(lock);

        assertThrows(IllegalStateException.class, () -> service.delete(7L));

        verify(modelMapper, never()).deleteById(any(java.io.Serializable.class));
        verify(versionMapper, never()).delete(any(Wrapper.class));
    }

    /** 全局锁已拿到但按车型锁冲突:抛冲突并释放全局锁,避免锁泄漏。 */
    @Test
    void 删除_车型锁冲突_释放已拿到的全局锁() throws Exception {
        CarModelEntity row = row();
        when(modelMapper.selectById(7L)).thenReturn(row);
        CarSyncLockService lock = org.mockito.Mockito.mock(CarSyncLockService.class);
        when(lock.tryLock(CarSyncLockService.GLOBAL_JOB_KEY)).thenReturn("g-owner");
        when(lock.tryLock(CarSyncLockService.goodsKey("156"))).thenReturn(null);
        setLockService(lock);

        assertThrows(IllegalStateException.class, () -> service.delete(7L));

        verify(lock).unlock(CarSyncLockService.GLOBAL_JOB_KEY, "g-owner");
        verify(modelMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    private void setLockService(CarSyncLockService lock) throws Exception {
        java.lang.reflect.Field f = CarModelService.class.getDeclaredField("lockService");
        f.setAccessible(true);
        f.set(service, lock);
    }

    @Test
    void 参数为空_标记INCOMPLETE_不删旧版本参数() {
        when(client.goodsInfo("156")).thenReturn(info("156", "新名"));
        GoodsParamsDto.ParamsData empty = new GoodsParamsDto.ParamsData();
        empty.setConfigs(List.of());
        when(client.goodsParams("156")).thenReturn(empty);
        when(client.goodsAttrList("156")).thenReturn(versions());

        assertThrows(SourceIncompleteException.class, () -> service.syncOne("156"));

        verify(versionMapper, never()).delete(any(Wrapper.class));
        verify(groupMapper, never()).delete(any(Wrapper.class));
        verify(paramMapper, never()).delete(any(Wrapper.class));
        verifyNoInteractions(cleanService, docService, vectorStoreService);
        verify(modelMapper, never()).updateById(any(CarModelEntity.class));
    }

    @Test
    void 版本为空_标记INCOMPLETE_不删旧版本() {
        when(client.goodsInfo("156")).thenReturn(info("156", "新名"));
        when(client.goodsParams("156")).thenReturn(params());
        when(client.goodsAttrList("156")).thenReturn(List.of());

        assertThrows(SourceIncompleteException.class, () -> service.syncOne("156"));

        verify(versionMapper, never()).delete(any(Wrapper.class));
        verify(groupMapper, never()).delete(any(Wrapper.class));
        verify(paramMapper, never()).delete(any(Wrapper.class));
        verify(modelMapper, never()).updateById(any(CarModelEntity.class));
    }

    @Test
    void 部分字段缺失_保留旧name以外的旧值_introImages不被null覆盖() {
        CarModelEntity row = row();
        when(modelMapper.selectOne(any())).thenReturn(row);
        GoodsInfoDto.Item item = info("156", "新名").getGoodsinfoList().getItems().get(0);
        item.setPrice(null);
        item.setFeatures(null);
        item.setCarRights(null);
        item.setIntroduce(null);
        when(client.goodsInfo("156")).thenReturn(infoOf(item));
        when(client.goodsParams("156")).thenReturn(params());
        when(client.goodsAttrList("156")).thenReturn(versions());
        when(cleanService.cleanForModel(any())).thenReturn(cleanStats());
        when(docService.rebuildForModel(any())).thenReturn(new EmbedStats(1, 1, 0));

        service.syncOne("156");

        assertEquals("新名", row.getName());
        assertEquals("旧价格", row.getPriceRange(), "price 缺失保留旧值");
        assertEquals("[\"旧卖点\"]", row.getFeatures(), "features 缺失保留旧值");
        assertEquals("[9]", row.getIntroImages(), "intro_images 不由 persistModel 覆盖");
        assertEquals("{\"title\":\"权益\"}", row.getCarRights(), "carRights 缺失保留旧值");
        assertEquals("SUCCESS", row.getSyncStatus());
    }

    private static GoodsInfoDto infoOf(GoodsInfoDto.Item item) {
        GoodsInfoDto dto = new GoodsInfoDto();
        GoodsInfoDto.GoodsInfoList list = new GoodsInfoDto.GoodsInfoList();
        list.setItems(List.of(item));
        dto.setGoodsinfoList(list);
        return dto;
    }

    @Test
    void 详情图部分失败_旧图集不被缩短_失败数可见() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/ok.png", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, PNG.length);
            try (var os = ex.getResponseBody()) { os.write(PNG); }
            ex.close();
        });
        server.start();
        try {
            String ok = "http://127.0.0.1:" + server.getAddress().getPort() + "/ok.png";
            when(imageService.persistOrReuse(any(), anyString(), any(ImageAssetEntity.class)))
                    .thenAnswer(inv -> {
                        ImageAssetEntity e = new ImageAssetEntity();
                        e.setId(11L);
                        return e;
                    });
            CarModelEntity row = row();
            GoodsInfoDto.Item item = info("156", "汉EV").getGoodsinfoList().getItems().get(0);
            item.setIntroduce(List.of(ok, "http://127.0.0.1:1/broken.png"));

            CarModelService.ImagePersistResult r = service.persistImages(row, item);

            assertEquals(1, r.failed());
            assertTrue(r.errors().stream().anyMatch(s -> s.contains("详情图失败1张")), r.errors().toString());
            assertEquals("[9]", row.getIntroImages(), "部分失败必须保留旧图集");
            verify(modelMapper, never()).updateById(any(CarModelEntity.class));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 封面失败_保留旧封面() {
        CarModelEntity row = row();
        GoodsInfoDto.Item item = info("156", "汉EV").getGoodsinfoList().getItems().get(0);
        item.setImg("http://127.0.0.1:1/cover.png");

        CarModelService.ImagePersistResult r = service.persistImages(row, item);

        assertEquals(1, r.failed());
        assertEquals(5L, row.getCoverImageId());
        verify(imageService, never()).persistOrReuse(any(), anyString(), any(ImageAssetEntity.class));
        verify(modelMapper, never()).updateById(any(CarModelEntity.class));
    }

    @Test
    void 向量部分失败_状态PARTIAL_不前进lastSyncAt() {
        CarModelEntity row = row();
        when(modelMapper.selectOne(any())).thenReturn(row);
        when(client.goodsInfo("156")).thenReturn(info("156", "新名"));
        when(client.goodsParams("156")).thenReturn(params());
        when(client.goodsAttrList("156")).thenReturn(versions());
        when(cleanService.cleanForModel(any())).thenReturn(cleanStats());
        when(docService.rebuildForModel(any())).thenReturn(new EmbedStats(2, 1, 1));

        service.syncOne("156");

        assertEquals("PARTIAL", row.getSyncStatus());
        assertNull(row.getLastSyncAt(), "部分失败不得前进 last_sync_at");
        List<Wrapper<CarModelEntity>> updates = captureUpdates(modelMapper);
        assertTrue(updates.stream().anyMatch(w -> setsColumn(w, "sync_status")
                && ((AbstractWrapper<?, ?, ?>) w).getParamNameValuePairs().containsValue("PARTIAL")));
        assertTrue(updates.stream().noneMatch(w -> setsColumn(w, "last_sync_at")), "PARTIAL 不得写 last_sync_at");
    }

    @Test
    void 清洗失败_状态FAILED_不前进lastSyncAt() {
        CarModelEntity row = row();
        when(modelMapper.selectOne(any())).thenReturn(row);
        when(client.goodsInfo("156")).thenReturn(info("156", "新名"));
        when(client.goodsParams("156")).thenReturn(params());
        when(client.goodsAttrList("156")).thenReturn(versions());
        when(cleanService.cleanForModel(any())).thenThrow(new RuntimeException("清洗失败"));

        assertThrows(RuntimeException.class, () -> service.syncOne("156"));

        assertNull(row.getLastSyncAt());
        List<Wrapper<CarModelEntity>> updates = captureUpdates(modelMapper);
        assertTrue(updates.stream().anyMatch(w -> setsColumn(w, "sync_status")
                && ((AbstractWrapper<?, ?, ?>) w).getParamNameValuePairs().containsValue("FAILED")));
        assertTrue(updates.stream().noneMatch(w -> setsColumn(w, "last_sync_at")));
    }

    /** 向量重建失败时 old chunks 保留:基础/参数指纹不得前进,否则下一次增量跳过、旧块永不补齐。 */
    @Test
    void 向量失败_不推进基础与参数指纹_保留旧块重试() {
        CarModelEntity row = row();
        row.setSyncFingerprint("a".repeat(64));
        row.setParamsFingerprint("b".repeat(64));
        when(modelMapper.selectOne(any())).thenReturn(row);
        when(client.goodsInfo("156")).thenReturn(info("156", "新名"));
        when(client.goodsParams("156")).thenReturn(params());
        when(client.goodsAttrList("156")).thenReturn(versions());
        when(cleanService.cleanForModel(any())).thenReturn(cleanStats());
        when(docService.rebuildForModel(any())).thenReturn(new EmbedStats(2, 1, 1));

        service.syncOne("156");

        assertEquals("a".repeat(64), row.getSyncFingerprint());
        assertEquals("b".repeat(64), row.getParamsFingerprint());
        List<Wrapper<CarModelEntity>> updates = captureUpdates(modelMapper);
        assertTrue(updates.stream().noneMatch(w -> setsColumn(w, "sync_fingerprint")),
                "向量失败时不得推进基础指纹");
        assertTrue(updates.stream().noneMatch(w -> setsColumn(w, "params_fingerprint")),
                "向量失败时不得推进参数指纹");
    }

    /** 版本数据无有效名称 → persistVersions 保留旧版本;指纹不得推进,否则下次增量跳过、旧版本永不补。 */
    @Test
    void 版本无有效名称_保留旧版本_不推进指纹() {
        CarModelEntity row = row();
        row.setSyncFingerprint("a".repeat(64));
        row.setParamsFingerprint("b".repeat(64));
        when(modelMapper.selectOne(any())).thenReturn(row);
        when(client.goodsInfo("156")).thenReturn(info("156", "新名"));
        when(client.goodsParams("156")).thenReturn(params());
        GoodsAttrListDto.Version blank = new GoodsAttrListDto.Version();
        blank.setName("  ");
        when(client.goodsAttrList("156")).thenReturn(List.of(blank));
        when(cleanService.cleanForModel(any())).thenReturn(cleanStats());
        when(docService.rebuildForModel(any())).thenReturn(new EmbedStats(1, 1, 0));

        service.syncOne("156");

        verify(versionMapper, never()).delete(any(Wrapper.class));   // 空数据保留旧版本
        assertEquals("PARTIAL", row.getSyncStatus());
        List<Wrapper<CarModelEntity>> updates = captureUpdates(modelMapper);
        assertTrue(updates.stream().noneMatch(w -> setsColumn(w, "sync_fingerprint")),
                "版本未真正替换时不得推进基础指纹");
        assertTrue(updates.stream().noneMatch(w -> setsColumn(w, "params_fingerprint")),
                "版本未真正替换时不得推进参数指纹");
    }

    /** 429/5xx/超时等瞬态不可用 → STALE(保留旧数据),不是 INCOMPLETE;都不算成功。 */
    @Test
    void 官网瞬态不可用_标记STALE_不写业务表() {
        when(client.goodsInfo("156")).thenThrow(
                new SourceUnavailableException("网络超时或连接失败", "timeout", null, true));

        SourceIncompleteException e = assertThrows(SourceIncompleteException.class,
                () -> service.syncOne("156"));

        assertEquals("STALE", e.sourceState());
        List<Wrapper<CarModelEntity>> updates = captureUpdates(modelMapper);
        assertTrue(updates.stream().anyMatch(w -> setsColumn(w, "source_state")
                && ((AbstractWrapper<?, ?, ?>) w).getParamNameValuePairs().containsValue("STALE")));
        assertTrue(updates.stream().noneMatch(w -> setsColumn(w, "last_sync_at")));
        verifyNoInteractions(versionMapper, groupMapper, paramMapper, cleanMapper, cleanService,
                docService, chunkMapper, vectorStoreService);
        verify(modelMapper, never()).insert(any(CarModelEntity.class));
        verify(modelMapper, never()).updateById(any(CarModelEntity.class));
    }

    /** 详情图多张失败:失败的「张数」必须准确(不是错误分类条数),note 可读。 */
    @Test
    void 详情图多张失败_失败张数准确() {
        CarModelEntity row = row();
        GoodsInfoDto.Item item = info("156", "汉EV").getGoodsinfoList().getItems().get(0);
        item.setIntroduce(List.of("http://127.0.0.1:1/a.png", "http://127.0.0.1:1/b.png"));

        CarModelService.ImagePersistResult r = service.persistImages(row, item);

        assertEquals(2, r.failed());
        assertTrue(r.errors().stream().anyMatch(s -> s.contains("详情图失败2张")), r.errors().toString());
        assertEquals("[9]", row.getIntroImages());
        verify(modelMapper, never()).updateById(any(CarModelEntity.class));
    }

    @Test
    void 增量_只图片变化_只更新图片_不触清洗向量() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/new.png", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, PNG.length);
            try (var os = ex.getResponseBody()) { os.write(PNG); }
            ex.close();
        });
        server.start();
        try {
            String img = "http://127.0.0.1:" + server.getAddress().getPort() + "/new.png";
            CarModelEntity row = row();
            GoodsInfoDto.Item item = info("156", "旧名").getGoodsinfoList().getItems().get(0);
            item.setImg(img);
            row.setSyncFingerprint(CarModelService.computeFingerprint(item));
            row.setParamsFingerprint(CarModelService.computeParamsFingerprint(params(), versions()));
            row.setImageFingerprint("deadbeef".repeat(8));
            when(modelMapper.selectOne(any())).thenReturn(row);
            when(client.goodsInfo("156")).thenReturn(infoOf(item));
            when(client.goodsParams("156")).thenReturn(params());
            when(client.goodsAttrList("156")).thenReturn(versions());
            when(imageService.persistOrReuse(any(), anyString(), any(ImageAssetEntity.class)))
                    .thenAnswer(inv -> {
                        ImageAssetEntity e = new ImageAssetEntity();
                        e.setId(88L);
                        return e;
                    });

            CarModelService.IncrementalOutcome out = service.dispatchIncremental("156");

            assertNotNull(out.outcome());
            assertEquals(88L, row.getCoverImageId());
            assertEquals(CarModelService.computeImageFingerprint(item), row.getImageFingerprint());
            verifyNoInteractions(cleanService, docService, vectorStoreService);
            verify(versionMapper, never()).delete(any(Wrapper.class));
            verify(groupMapper, never()).delete(any(Wrapper.class));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 增量_只参数变化_重建参数清洗向量_不重下图片() {
        CarModelEntity row = row();
        GoodsInfoDto.Item item = info("156", "旧名").getGoodsinfoList().getItems().get(0);
        row.setSyncFingerprint(CarModelService.computeFingerprint(item));
        row.setImageFingerprint(CarModelService.computeImageFingerprint(item));
        row.setParamsFingerprint("deadbeef".repeat(8));
        when(modelMapper.selectOne(any())).thenReturn(row);
        when(client.goodsInfo("156")).thenReturn(infoOf(item));
        when(client.goodsParams("156")).thenReturn(params());
        when(client.goodsAttrList("156")).thenReturn(versions());
        when(cleanService.cleanForModel(any())).thenReturn(cleanStats());
        when(docService.rebuildForModel(any())).thenReturn(new EmbedStats(1, 1, 0));

        CarModelService.IncrementalOutcome out = service.dispatchIncremental("156");

        assertNotNull(out.outcome());
        verify(versionMapper).delete(any(Wrapper.class));
        verify(groupMapper).delete(any(Wrapper.class));
        verify(paramMapper).delete(any(Wrapper.class));
        verify(cleanService).cleanForModel(row.getId());
        verify(docService).rebuildForModel(row.getId());
        verify(imageService, never()).persistOrReuse(any(), anyString(), any(ImageAssetEntity.class));
    }
    @Test
    void 图片失败_不推进图片指纹_下一次增量仍可重试() {
        CarModelEntity row = row();
        row.setImageFingerprint("a".repeat(64));
        row.setSyncStatus("SUCCESS");
        when(modelMapper.selectOne(any())).thenReturn(row);
        GoodsInfoDto.Item item = info("156", "汉EV").getGoodsinfoList().getItems().get(0);
        item.setImg("http://127.0.0.1:1/cover.png");
        when(client.goodsInfo("156")).thenReturn(infoOf(item));
        when(client.goodsParams("156")).thenReturn(params());
        when(client.goodsAttrList("156")).thenReturn(versions());
        when(cleanService.cleanForModel(any())).thenReturn(cleanStats());
        when(docService.rebuildForModel(any())).thenReturn(new EmbedStats(1, 1, 0));

        service.syncOne("156");

        assertEquals("a".repeat(64), row.getImageFingerprint(),
                "图片失败不得推进图片指纹，否则下一次增量会永久跳过补图");
        List<Wrapper<CarModelEntity>> updates = captureUpdates(modelMapper);
        assertTrue(updates.stream().noneMatch(w -> setsColumn(w, "image_fingerprint")),
                "图片失败时 updateFingerprints 不应写 image_fingerprint");
    }

    @Test
    void 解析官网创建时间_兼容空格与ISO_非法返回null() {
        assertEquals(java.time.LocalDateTime.of(2026, 10, 20, 17, 44, 53),
                CarModelService.parseSourceCreatedAt("2026-10-20 17:44:53"));
        assertEquals(java.time.LocalDateTime.of(2026, 10, 20, 17, 44, 53),
                CarModelService.parseSourceCreatedAt("2026-10-20T17:44:53"));
        assertNull(CarModelService.parseSourceCreatedAt(""));
        assertNull(CarModelService.parseSourceCreatedAt("not-a-date"));
    }

    @Test
    void 回填官网创建时间_只更新source_created_at() {
        CarModelEntity row = row();
        GoodsInfoDto info = info("156", "汉EV");
        info.getGoodsinfoList().getItems().get(0).setCreatedAt("2026-10-20 17:44:53");
        when(modelMapper.selectList(any())).thenReturn(List.of(row));
        when(client.goodsInfo("156")).thenReturn(info);

        java.util.Map<String, Object> out = service.backfillSourceCreatedAt();

        assertEquals(1, out.get("updated"));
        List<Wrapper<CarModelEntity>> updates = captureUpdates(modelMapper);
        assertTrue(updates.stream().anyMatch(w -> setsColumn(w, "source_created_at")));
        assertTrue(updates.stream().noneMatch(w -> setsColumn(w, "sync_status")));
    }

    @Test
    void 列表图片URL批量查询_只调用一次selectBatchIds() {
        CarModelEntity m = row();
        when(modelMapper.selectList(any())).thenReturn(List.of(m));
        ImageAssetEntity cover = new ImageAssetEntity();
        cover.setId(5L); cover.setStorageKey("k5");
        ImageAssetEntity intro = new ImageAssetEntity();
        intro.setId(9L); intro.setStorageKey("k9");
        when(imageMapper.selectBatchIds(any())).thenReturn(List.of(cover, intro));
        when(imageStorage.publicUrl("k5")).thenReturn("https://cdn.example/5.png");
        when(imageStorage.publicUrl("k9")).thenReturn("https://cdn.example/9.png");

        List<CarModelEntity> out = service.list();

        assertEquals("https://cdn.example/5.png", out.get(0).getCoverImageUrl());
        assertEquals(List.of("https://cdn.example/9.png"), out.get(0).getIntroImageUrls());
        verify(imageMapper, org.mockito.Mockito.times(1)).selectBatchIds(any());
        verify(imageMapper, never()).selectById(any());
    }
}
