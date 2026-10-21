package com.sparkora.car.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.vector.VectorStoreService;
import com.sparkora.car.client.BydCmsClient;
import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.car.dto.CleanStats;
import com.sparkora.car.dto.GoodsAttrListDto;
import com.sparkora.car.dto.GoodsInfoDto;
import com.sparkora.car.dto.GoodsParamsDto;
import com.sparkora.domain.entity.CarModelEntity;
import com.sparkora.domain.entity.CarSyncJobEntity;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.mapper.CarChunkMapper;
import com.sparkora.mapper.CarModelMapper;
import com.sparkora.mapper.CarParamCleanMapper;
import com.sparkora.mapper.CarParamGroupMapper;
import com.sparkora.mapper.CarParamMapper;
import com.sparkora.mapper.CarSyncJobMapper;
import com.sparkora.mapper.CarVersionMapper;
import com.sparkora.mapper.ImageAssetMapper;
import com.sparkora.service.ImageService;
import com.sparkora.storage.ImageStorage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 增量分派(dispatchIncremental 四分支)+ 任务框架分派(image-backfill B 阶段)单测(Mockito,不连官网)。
 * 分派表:行不存在 → 全量 syncOne;指纹 NULL → 轻回填;指纹一致 → 跳过(note 标注);指纹不一致 → 全量 syncOne。
 * 跳过分支断言「零触碰」:不拉参数/版本接口、不写车型行;备注条目不进 retry 候选。
 */
@ExtendWith(MockitoExtension.class)
class CarSyncIncrementsTest {

    // ---- dispatchIncremental 用真实 CarModelService(依赖全 mock) ----
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

    // ---- runJob/createBackfillJob 用 mock CarModelService(任务框架层单测) ----
    @Mock CarModelService modelServiceMock;
    @Mock CarSyncJobMapper jobMapper;

    CarModelService service;
    CarSyncJobService jobService;

    @BeforeEach
    void setUp() {
        service = new CarModelService(client, modelMapper, versionMapper, groupMapper, paramMapper,
                cleanMapper, docService, cleanService, chunkMapper, imageMapper, imageStorage,
                new ObjectMapper(), imageService, embeddingClient, vectorStoreService);
        // 单测用内嵌 127.0.0.1 HttpServer 模拟官网图;生产默认下载器只允许 HTTPS + 白名单
        service.setImageDownloader(CarImageDownloader.permissiveForTest());
        lenient().when(cleanMapper.selectCount(any())).thenReturn(1L);
        // self 为空 → createJob 直调 runJob(同步);jobMapper.selectById 未命中时任务体早退,用例可控
        jobService = new CarSyncJobService(jobMapper, modelMapper, modelServiceMock, new ObjectMapper());
    }

    private static CleanStats cleanStats() {
        CleanStats st = new CleanStats();
        st.count("RULE");
        return st;
    }

    /** 构造单 item 的 goodsInfo 响应(空 introduce = 官网无图语义)。 */
    private static GoodsInfoDto infoWithItem(String goodsId, String name, List<String> introduce) {
        GoodsInfoDto.Item item = new GoodsInfoDto.Item();
        item.setId(goodsId);
        item.setName(name);
        item.setPrice("239,900 - 309,900");
        item.setIntroduce(introduce);
        GoodsInfoDto dto = new GoodsInfoDto();
        GoodsInfoDto.GoodsInfoList list = new GoodsInfoDto.GoodsInfoList();
        list.setItems(List.of(item));
        dto.setGoodsinfoList(list);
        return dto;
    }

    private static GoodsInfoDto infoOf(GoodsInfoDto.Item item) {
        GoodsInfoDto dto = new GoodsInfoDto();
        GoodsInfoDto.GoodsInfoList list = new GoodsInfoDto.GoodsInfoList();
        list.setItems(List.of(item));
        dto.setGoodsinfoList(list);
        return dto;
    }

    private static CarModelEntity rowFor(Long id, String goodsId, String name) {
        CarModelEntity m = new CarModelEntity();
        m.setId(id);
        m.setGoodsId(goodsId);
        m.setName(name);
        return m;
    }

    /** 构造空条目的 goodsInfo 响应(10-15 真机:无条目 ≠ 无图,需走快照指纹比对)。 */
    private static GoodsInfoDto infoEmpty() {
        return new GoodsInfoDto();
    }

    private static CarSyncJobEntity runningJob(Long id, String type) {
        CarSyncJobEntity job = new CarSyncJobEntity();
        job.setId(id);
        job.setJobType(type);
        job.setStatus("RUNNING");
        return job;
    }

    // ================= dispatchIncremental 四分支 =================

    @Test
    void 分派_行不存在_全量syncOne() {
        when(modelMapper.selectOne(any())).thenReturn(null);          // dispatch 查无行;persistModel 内同判
        when(client.goodsInfo("156")).thenReturn(infoWithItem("156", "新车型", List.of()));
        when(client.goodsParams("156")).thenReturn(params());
        when(client.goodsAttrList("156")).thenReturn(versions());

        CarModelService.IncrementalOutcome out = service.dispatchIncremental("156");

        assertNull(out.note());
        assertNotNull(out.outcome());
        // 新行分支:分派本身不拉 goodsInfo,全量触达发生在 syncOne 内部(goodsInfo/params/attrs 各一次)
        verify(client, times(1)).goodsInfo("156");
        verify(client, times(1)).goodsParams("156");
        verify(client).goodsAttrList("156");
        verify(cleanService).cleanForModel(any());
        verify(docService).rebuildForModel(any());
        verify(modelMapper).insert(any(CarModelEntity.class));
    }

    @Test
    void 分派_指纹NULL_轻回填_零触参数与清洗_顺带初始化指纹() {
        CarModelEntity row = rowFor(26L, "26", "2026款海豹07DM-i");   // syncFingerprint=null(存量) + 官网空图
        when(modelMapper.selectOne(any())).thenReturn(row);
        when(client.goodsInfo("26")).thenReturn(infoWithItem("26", "2026款海豹07DM-i", List.of()));

        CarModelService.IncrementalOutcome out = service.dispatchIncremental("26");

        assertEquals("官网无图", out.note());
        assertNotNull(out.outcome());
        assertEquals("SUCCESS", out.outcome().model().getSyncStatus());
        assertNotNull(row.getSyncFingerprint());
        // 轻回填边界:仅一次 goodsInfo,不拉参数/版本接口,不触清洗,不写模型行以外域
        verify(client, times(1)).goodsInfo("26");
        verify(client, never()).goodsParams(anyString());
        verify(client, never()).goodsAttrList(anyString());
        verifyNoTouchParamsAndCleanAndVector();
        verify(modelMapper).updateById(row);
    }

    @Test
    void 分派_指纹一致_跳过_零写入() {
        GoodsInfoDto dto = infoWithItem("156", "大唐EV", List.of());
        CarModelEntity row = rowFor(156L, "156", "大唐EV");
        row.setSyncFingerprint(CarModelService.computeFingerprint(dto.getGoodsinfoList().getItems().get(0)));
        row.setImageFingerprint(CarModelService.computeImageFingerprint(dto.getGoodsinfoList().getItems().get(0)));
        row.setParamsFingerprint(CarModelService.computeParamsFingerprint(params(), versions()));
        when(modelMapper.selectOne(any())).thenReturn(row);
        when(client.goodsInfo("156")).thenReturn(dto);
        when(client.goodsParams("156")).thenReturn(params());
        when(client.goodsAttrList("156")).thenReturn(versions());

        CarModelService.IncrementalOutcome out = service.dispatchIncremental("156");

        assertEquals("跳过:指纹一致", out.note());
        assertNull(out.outcome());
        // 10-21:跳过分支仍需拉三接口比对三类指纹(只读),零下载/零清洗/零下游重建;
        // PRD R3 允许只更新 last_checked_at/sync_note,但不得写业务列(状态/指纹/图集)。
        verify(client, times(1)).goodsInfo("156");
        verify(client, times(1)).goodsParams("156");
        verify(client, times(1)).goodsAttrList("156");
        verifyNoTouchParamsAndCleanAndVector();
        verify(modelMapper, never()).updateById(any(CarModelEntity.class));
        ArgumentCaptor<Wrapper<CarModelEntity>> cap = (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
        verify(modelMapper).update(isNull(), cap.capture());
        String sqlSet = ((AbstractWrapper<?, ?, ?>) cap.getValue()).getSqlSet();
        assertTrue(sqlSet.contains("last_checked_at"), sqlSet);
        assertTrue(sqlSet.contains("sync_note"), sqlSet);
        assertFalse(sqlSet.contains("sync_status"), sqlSet);
        assertFalse(sqlSet.contains("sync_fingerprint"), sqlSet);
        assertFalse(sqlSet.contains("image_fingerprint"), sqlSet);
        assertFalse(sqlSet.contains("params_fingerprint"), sqlSet);
        assertFalse(sqlSet.contains("intro_images"), sqlSet);
        assertFalse(sqlSet.contains("cover_image_id"), sqlSet);
    }

    @Test
    void 分派_指纹不一致_全量syncOne_并重写指纹() {
        GoodsInfoDto dto = infoWithItem("156", "大唐EV(调价后)", List.of());
        CarModelEntity row = rowFor(156L, "156", "大唐EV");
        row.setSyncFingerprint("deadbeef".repeat(8));   // 64 位旧指纹(≠ 当前 goodsInfo 指纹)
        when(modelMapper.selectOne(any())).thenReturn(row);
        when(client.goodsInfo("156")).thenReturn(dto);
        when(client.goodsParams("156")).thenReturn(params());
        when(client.goodsAttrList("156")).thenReturn(versions());

        CarModelService.IncrementalOutcome out = service.dispatchIncremental("156");

        assertNull(out.note());
        assertNotNull(out.outcome());
        // 分派比对 + syncOne 全量
        verify(client, times(2)).goodsInfo("156");
        verify(client, times(2)).goodsParams("156");
        verify(cleanService).cleanForModel(any());
        // 全量路径回写新指纹(persistModel 统一写)
        assertEquals(CarModelService.computeFingerprint(dto.getGoodsinfoList().getItems().get(0)),
                row.getSyncFingerprint());
    }

    /**
     * 10-15 真机修订:goodsInfo 当前返回空条目(非 introduce 空)且库存快照指纹已固化 → 跳过。
     * 跳过分支零写入零触碰;官网恢复数据后 goodsInfo 指纹必不匹配快照 → 自动走全量收敛。
     */
    @Test
    void 分派_无条目且快照指纹一致_跳过且如实标EMPTY() {
        CarModelEntity row = rowFor(13L, "10029", "海豹智驾版");
        row.setIntroImages("[\"https://cache.bydauto.com.cn/legacy.jpg\"]");   // 存量 URL 已兜底入库
        row.setSyncFingerprint(CarModelService.computeRowSnapshotFingerprint(row));   // 上次快照指纹
        when(modelMapper.selectOne(any())).thenReturn(row);
        when(client.goodsInfo("10029")).thenReturn(infoEmpty());   // 官网当前空条目

        CarModelService.IncrementalOutcome out = service.dispatchIncremental("10029");

        assertEquals("跳过:官网无数据(与上次快照一致)", out.note());
        assertNull(out.outcome());
        // 跳过分支:仅一次 goodsInfo 比对;不下载图片、不动参数/清洗/向量;
        // 但必须只更新状态列如实标 EMPTY(存量老口径行不能继续显示已同步)。
        verify(client, times(1)).goodsInfo("10029");
        verify(client, never()).goodsParams(anyString());
        verify(client, never()).goodsAttrList(anyString());
        verify(modelMapper, never()).updateById(any(CarModelEntity.class));
        verify(cleanService, never()).cleanForModel(any());
        ArgumentCaptor<Wrapper<CarModelEntity>> cap = (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
        verify(modelMapper).update(isNull(), cap.capture());
        AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) cap.getValue();
        assertTrue(w.getSqlSet().contains("source_state"));
        assertTrue(w.getParamNameValuePairs().containsValue("EMPTY"));
    }

    /** 指纹 NULL + 无条目 → 兜底回填:用库存行计算快照指纹初始化,note 说明数据源状态。 */
    @Test
    void 分派_指纹NULL且无条目_走快照兜底回填() {
        CarModelEntity row = rowFor(37L, "10033", "海狮07DM-i");
        row.setIntroImages("[]");   // 无存量 URL
        when(modelMapper.selectOne(any())).thenReturn(row);
        when(client.goodsInfo("10033")).thenReturn(infoEmpty());

        CarModelService.IncrementalOutcome out = service.dispatchIncremental("10033");

        assertEquals("官网无数据,且无存量图可兜底", out.note());
        assertNotNull(out.outcome());
        assertEquals("FAILED", row.getSyncStatus());   // 10-21:官网无数据不算成功
        assertEquals("EMPTY", row.getSourceState());
        assertNotNull(row.getSyncFingerprint());   // 库存行快照指纹已初始化
        // 指纹必须是 64 位 hex(SHA-256 摘要)——超长值会撞 sync_fingerprint VARCHAR(64) 写库失败
        assertTrue(row.getSyncFingerprint().matches("[0-9a-f]{64}"));
        verify(client, times(1)).goodsInfo("10033");
        verify(client, never()).goodsParams(anyString());
        verify(modelMapper).updateById(row);   // 收敛写回(快照指纹/状态);无多余写
    }

    /**
     * 图片指纹未初始化(存量)且有官网图片:必须真正补图,失败时保持 NULL 让下一次增量重试;
     * 只写 hash 不补图会造成「图未补、指纹已收敛」的永久跳过。
     */
    @Test
    void 分派_图片指纹未初始化_补图失败不推进指纹_恢复后补图并收敛() throws Exception {
        String dead = "http://127.0.0.1:1/cover.png";
        GoodsInfoDto.Item item = infoWithItem("156", "汉EV", List.of())
                .getGoodsinfoList().getItems().get(0);
        item.setImg(dead);
        CarModelEntity row = rowFor(156L, "156", "汉EV");
        row.setSyncFingerprint(CarModelService.computeFingerprint(item));
        row.setParamsFingerprint(CarModelService.computeParamsFingerprint(params(), versions()));
        row.setImageFingerprint(null);   // 存量未初始化
        when(modelMapper.selectOne(any())).thenReturn(row);
        when(client.goodsInfo("156")).thenReturn(infoOf(item));
        when(client.goodsParams("156")).thenReturn(params());
        when(client.goodsAttrList("156")).thenReturn(versions());

        CarModelService.IncrementalOutcome failed = service.dispatchIncremental("156");

        assertEquals("PARTIAL", row.getSyncStatus());
        assertNull(row.getImageFingerprint(), "补图失败不得推进图片指纹");
        assertTrue(failed.note() != null && failed.note().contains("图片失败1张"), failed.note());
        ArgumentCaptor<Wrapper<CarModelEntity>> cap = (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
        verify(modelMapper, atLeastOnce()).update(isNull(), cap.capture());
        assertTrue(cap.getAllValues().stream()
                        .noneMatch(w -> String.valueOf(((AbstractWrapper<?, ?, ?>) w).getSqlSet()).contains("image_fingerprint")),
                "失败时不得写 image_fingerprint");
        verifyNoInteractions(cleanService, docService, vectorStoreService);

        // 官网图片恢复可达 → 下一次增量补图成功并收敛指纹
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/cover.png", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            try (var os = ex.getResponseBody()) { os.write(png); }
            ex.close();
        });
        server.start();
        try {
            String ok = "http://127.0.0.1:" + server.getAddress().getPort() + "/cover.png";
            item.setImg(ok);
            when(client.goodsInfo("156")).thenReturn(infoOf(item));
            when(imageService.persistOrReuse(any(), anyString(), any(ImageAssetEntity.class)))
                    .thenAnswer(inv -> {
                        ImageAssetEntity e = new ImageAssetEntity();
                        e.setId(66L);
                        return e;
                    });

            CarModelService.IncrementalOutcome okOut = service.dispatchIncremental("156");

            assertEquals(CarModelService.computeImageFingerprint(item), row.getImageFingerprint());
            assertEquals(66L, row.getCoverImageId());
            assertNull(okOut.note());
            assertEquals("SUCCESS", row.getSyncStatus());
            verifyNoInteractions(cleanService, docService, vectorStoreService);
        } finally {
            server.stop(0);
        }
    }

    /** 参数变化但向量重建失败:不得推进 params_fingerprint,否则下次增量跳过、旧块永不补齐。 */
    @Test
    void 分派_参数变化但向量失败_不推进参数指纹_下次仍重试() {
        GoodsInfoDto dto = infoWithItem("156", "旧名", List.of());
        CarModelEntity row = rowFor(156L, "156", "旧名");
        row.setSyncFingerprint(CarModelService.computeFingerprint(dto.getGoodsinfoList().getItems().get(0)));
        row.setImageFingerprint(CarModelService.computeImageFingerprint(dto.getGoodsinfoList().getItems().get(0)));
        row.setParamsFingerprint("deadbeef".repeat(8));
        when(modelMapper.selectOne(any())).thenReturn(row);
        when(client.goodsInfo("156")).thenReturn(dto);
        when(client.goodsParams("156")).thenReturn(params());
        when(client.goodsAttrList("156")).thenReturn(versions());
        when(cleanService.cleanForModel(any())).thenReturn(cleanStats());
        when(docService.rebuildForModel(any())).thenReturn(new com.sparkora.ai.EmbedStats(2, 1, 1));

        CarModelService.IncrementalOutcome out = service.dispatchIncremental("156");

        assertEquals("deadbeef".repeat(8), row.getParamsFingerprint(), "向量失败不得推进参数指纹");
        assertEquals("PARTIAL", row.getSyncStatus());
        assertNotNull(out.outcome());
        ArgumentCaptor<Wrapper<CarModelEntity>> cap = (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
        verify(modelMapper, atLeastOnce()).update(isNull(), cap.capture());
        assertTrue(cap.getAllValues().stream()
                        .noneMatch(w -> String.valueOf(((AbstractWrapper<?, ?, ?>) w).getSqlSet()).contains("params_fingerprint")),
                "向量失败时不得写 params_fingerprint");
    }

    @Test
    void 分派跳过备注条目_不进retry候选() {
        // 明细含「跳过/官网无图」备注条目(无 error 键)与真实失败条目(含 error)混合,只重试失败项
        CarSyncJobEntity job = runningJob(3L, "INCREMENTAL");
        job.setFailedItems("[{\"goodsId\":\"156\",\"name\":\"A\",\"note\":\"跳过:指纹一致\"},"
                + "{\"goodsId\":\"99\",\"name\":\"B\",\"error\":\"官网采集失败\"}]");
        when(jobMapper.selectById(3L)).thenReturn(job);
        doAnswer(inv -> {
            ((CarSyncJobEntity) inv.getArgument(0)).setId(5L);
            return 1;
        }).when(jobMapper).insert(any(CarSyncJobEntity.class));

        Long newJobId = jobService.retry(3L);

        assertEquals(5L, newJobId);
        ArgumentCaptor<CarSyncJobEntity> cap = ArgumentCaptor.forClass(CarSyncJobEntity.class);
        verify(jobMapper).insert(cap.capture());
        assertEquals("RETRY", cap.getValue().getJobType());
        assertEquals(1, cap.getValue().getTotal());   // 仅 error 条目;note 条目剔除
    }

    // ================= 任务框架分派(runJob 按 job_type) =================

    @Test
    void 任务分派_IMAGE_BACKFILL_走轻回填且不触全量与增量() {
        CarSyncJobEntity job = runningJob(1L, "IMAGE_BACKFILL");
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(jobMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);   // 并发锁(RUNNING→RUNNING)
        CarModelEntity row = rowFor(39L, "39", "海狮08EV");
        when(modelServiceMock.backfillImages("39"))
                .thenReturn(new CarModelService.IncrementalOutcome(null, new CarModelService.SyncOutcome(row, null)));

        jobService.runJob(1L, List.of("39"));

        verify(modelServiceMock).backfillImages("39");
        verify(modelServiceMock, never()).syncOne(anyString());
        verify(modelServiceMock, never()).dispatchIncremental(anyString());
        // 收尾:SUCCESS + 明细 JSON 落库
        assertEquals("SUCCESS", job.getStatus());
        assertEquals(1, job.getSuccess());
        assertEquals(0, job.getFailed());
    }

    @Test
    void 任务分派_INCREMENTAL_走增量分派() {
        CarSyncJobEntity job = runningJob(2L, "INCREMENTAL");
        when(jobMapper.selectById(2L)).thenReturn(job);
        when(jobMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(modelServiceMock.dispatchIncremental("156"))
                .thenReturn(new CarModelService.IncrementalOutcome("跳过:指纹一致", null));

        jobService.runJob(2L, List.of("156"));

        verify(modelServiceMock).dispatchIncremental("156");
        verify(modelServiceMock, never()).syncOne(anyString());
        verify(modelServiceMock, never()).backfillImages(anyString());
        // 10-21:跳过计入 skipped,不冒充 success;明细带 note 条目(可观测)
        assertEquals("SUCCESS", job.getStatus());
        assertEquals(0, job.getSuccess());
        assertEquals(1, job.getSkipped());
        assertTrue(job.getFailedItems().contains("跳过:指纹一致"));
    }

    @Test
    void 任务分派_全量类型_保持既有syncOne语义() {
        CarSyncJobEntity job = runningJob(3L, "SELECTED");
        when(jobMapper.selectById(3L)).thenReturn(job);
        when(jobMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        CarModelEntity row = rowFor(39L, "39", "海狮08EV");
        when(modelServiceMock.syncOne("39"))
                .thenReturn(new CarModelService.SyncOutcome(row, new CleanStats()));

        jobService.runJob(3L, List.of("39"));

        verify(modelServiceMock).syncOne("39");
        verify(modelServiceMock, never()).backfillImages(anyString());
        verify(modelServiceMock, never()).dispatchIncremental(anyString());
        // 全量路径收尾语义不变:SUCCESS + 明细 JSON(无备注条目)
        assertEquals("SUCCESS", job.getStatus());
        assertEquals("[]", job.getFailedItems());
    }

    /** 车型 PARTIAL(即使 note 不含「失败」二字,如「版本不完整」)也必须让任务终态为 PARTIAL。 */
    @Test
    void 任务分派_车型PARTIAL_任务终态PARTIAL() {
        CarSyncJobEntity job = runningJob(6L, "SELECTED");
        when(jobMapper.selectById(6L)).thenReturn(job);
        when(jobMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        CarModelEntity row = rowFor(39L, "39", "海狮08EV");
        row.setSyncStatus("PARTIAL");
        row.setSyncNote("版本不完整");
        when(modelServiceMock.syncOne("39"))
                .thenReturn(new CarModelService.SyncOutcome(row, new CleanStats()));

        jobService.runJob(6L, List.of("39"));

        assertEquals("PARTIAL", job.getStatus());
        assertEquals(1, job.getSuccess());
        assertEquals(0, job.getFailed());
        assertTrue(job.getFailedItems().contains("版本不完整"));
        assertTrue(job.getFailedItems().contains("\"partial\":true"), job.getFailedItems());
    }

    /** 10-21 R2:失败回写只动 sync_status/last_sync_error/updated_at,原因截断 500,不写 last_sync_at。 */
    @Test
    void 失败回写_状态FAILED_原因截断500_不动最后成功时间() {
        service.markSyncFailed("26", "x".repeat(600));

        ArgumentCaptor<Wrapper<CarModelEntity>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
        verify(modelMapper).update(isNull(), captor.capture());
        UpdateWrapper<CarModelEntity> uw = (UpdateWrapper<CarModelEntity>) captor.getValue();
        String sqlSet = uw.getSqlSet();
        uw.getSqlSegment();   // 物化 WHERE,paramNameValuePairs 装载全部取值
        assertTrue(sqlSet.contains("sync_status"), "set 含 sync_status");
        assertTrue(sqlSet.contains("last_sync_error"), "set 含 last_sync_error");
        assertTrue(sqlSet.contains("updated_at"), "set 含 updated_at");
        assertFalse(sqlSet.contains("last_sync_at"), "失败不写 last_sync_at(保留最后成功时间)");
        var values = uw.getParamNameValuePairs().values();
        assertTrue(values.contains("FAILED"), "状态置 FAILED");
        String err = (String) values.stream()
                .filter(v -> v instanceof String s && s.startsWith("xxx"))
                .findFirst().orElseThrow();
        assertEquals(500, err.length(), "失败原因截断 500 字符");
    }

    /** 10-21 R2:任务 catch 分支必须回写车型失败状态(RETRY/SELECTED/IMAGE_BACKFILL/INCREMENTAL 同口径)。 */
    @Test
    void 任务失败_回写车型失败状态_任务终态FAILED() {
        CarSyncJobEntity job = runningJob(4L, "SELECTED");
        when(jobMapper.selectById(4L)).thenReturn(job);
        when(jobMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(modelServiceMock.syncOne("99")).thenThrow(new RuntimeException("官网采集失败"));

        jobService.runJob(4L, List.of("99"));

        verify(modelServiceMock).markSyncFailed("99", "官网采集失败");
        assertEquals("FAILED", job.getStatus());
        assertEquals(1, job.getFailed());
        assertTrue(job.getFailedItems().contains("官网采集失败"));
    }

    /** 10-21 R2:失败状态回写自身异常不得中断任务/覆盖原始错误。 */
    @Test
    void 失败回写异常_不覆盖原始错误_任务照常收尾() {
        CarSyncJobEntity job = runningJob(5L, "INCREMENTAL");
        when(jobMapper.selectById(5L)).thenReturn(job);
        when(jobMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(modelServiceMock.dispatchIncremental("99")).thenThrow(new RuntimeException("官网采集失败"));
        doThrow(new RuntimeException("db down")).when(modelServiceMock)
                .markSyncFailed(anyString(), anyString());

        jobService.runJob(5L, List.of("99"));

        assertEquals("FAILED", job.getStatus());
        assertTrue(job.getFailedItems().contains("官网采集失败"), "明细保留原始错误");
        assertFalse(job.getFailedItems().contains("db down"), "回写异常不覆盖原始错误");
    }

    @Test
    void 创建回填任务_覆盖全部库存车型_按id升序_IMAGE_BACKFILL() {
        CarModelEntity g100 = rowFor(10L, "100", "先同步车型");
        CarModelEntity g156 = rowFor(20L, "156", "后同步车型");
        when(modelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(g100, g156));
        when(jobMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(modelServiceMock.backfillImages("100"))
                .thenReturn(new CarModelService.IncrementalOutcome(null,
                        new CarModelService.SyncOutcome(g100, null)));
        when(modelServiceMock.backfillImages("156"))
                .thenReturn(new CarModelService.IncrementalOutcome("官网无图",
                        new CarModelService.SyncOutcome(g156, null)));
        doAnswer(inv -> {
            ((CarSyncJobEntity) inv.getArgument(0)).setId(7L);
            return 1;
        }).when(jobMapper).insert(any(CarSyncJobEntity.class));
        // 任务落库后经 this.runJob 同步执行:self==null 直调;selectById 返回刚插入的实体
        when(jobMapper.selectById(7L)).thenAnswer(inv -> {
            CarSyncJobEntity j = new CarSyncJobEntity();
            j.setId(7L);
            j.setJobType("IMAGE_BACKFILL");
            j.setStatus("RUNNING");
            return j;
        });

        Long jobId = jobService.createBackfillJob();

        assertEquals(7L, jobId);
        ArgumentCaptor<CarSyncJobEntity> cap = ArgumentCaptor.forClass(CarSyncJobEntity.class);
        verify(jobMapper).insert(cap.capture());
        assertEquals("IMAGE_BACKFILL", cap.getValue().getJobType());
        assertEquals(2, cap.getValue().getTotal());
        assertEquals("system", cap.getValue().getCreatedBy());
        // 库存 id 升序逐车型回填,顺序可观测
        InOrder order = inOrder(modelServiceMock);
        order.verify(modelServiceMock).backfillImages("100");
        order.verify(modelServiceMock).backfillImages("156");
        // 「官网无图」备注进明细(终态用 WHERE status='RUNNING' 条件写)
        ArgumentCaptor<Wrapper<CarSyncJobEntity>> finishCap =
                (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
        verify(jobMapper, atLeastOnce()).update(isNull(), finishCap.capture());
        AbstractWrapper<?, ?, ?> fin = finishCap.getAllValues().stream()
                .map(w -> (AbstractWrapper<?, ?, ?>) w)
                .filter(w -> String.valueOf(w.getSqlSet()).contains("failed_items"))
                .findFirst().orElseThrow();
        fin.getSqlSegment();   // 物化 WHERE,装载 paramNameValuePairs
        assertTrue(fin.getParamNameValuePairs().values().stream()
                .anyMatch(v -> String.valueOf(v).contains("官网无图")));
        assertTrue(fin.getParamNameValuePairs().containsValue("SUCCESS"));
        assertTrue(fin.getParamNameValuePairs().containsValue(2));
    }

    @Test
    void 创建回填任务_库内无车型_拒建并存() {
        when(modelMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class, () -> jobService.createBackfillJob());
        verify(jobMapper, never()).insert(any(CarSyncJobEntity.class));
    }

    /** 轻回填/跳过分支的域边界断言:参数/清洗/切块/向量域零交互。 */
    private void verifyNoTouchParamsAndCleanAndVector() {
        verifyNoInteractions(groupMapper, paramMapper, cleanService, docService, vectorStoreService);
        verify(cleanMapper, never()).delete(any());
    }

    /** 有效参数响应(10-21 预检要求非空)。 */
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

    /** 有效版本响应(10-21 预检要求非空)。 */
    private static List<GoodsAttrListDto.Version> versions() {
        GoodsAttrListDto.Version v = new GoodsAttrListDto.Version();
        v.setName("205领航版");
        return List.of(v);
    }
}