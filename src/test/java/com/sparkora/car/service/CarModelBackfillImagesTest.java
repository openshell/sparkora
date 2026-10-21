package com.sparkora.car.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.car.client.BydCmsClient;
import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.car.dto.GoodsAttrListDto;
import com.sparkora.car.dto.GoodsInfoDto;
import com.sparkora.car.dto.GoodsParamsDto;
import com.sparkora.ai.vector.VectorStoreService;
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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 轻回填(存量图片补入库,R1)单测:仅 goodsInfo → persistIntroImages 图片域 + 指纹/状态收敛;
 * 不触参数/清洗/向量域(mapper 交互断言);空 introduce → 无图入库 +「官网无图」备注;
 * 网络图经本地内嵌 HttpServer 全链路打通(下载 → persistOrReuse 哈希命中复用 → intro_images 收敛 id 列表)。
 */
@ExtendWith(MockitoExtension.class)
class CarModelBackfillImagesTest {

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
        // 单测用内嵌 127.0.0.1 HttpServer 模拟官网图;生产默认下载器只允许 HTTPS + 白名单
        service.setImageDownloader(CarImageDownloader.permissiveForTest());
    }

    /** 构造单 item 的 goodsInfo 响应(可指定 introduce 列表)。 */
    private static GoodsInfoDto infoItem(String goodsId, String name, List<String> introduce) {
        return infoItem(goodsId, name, null, introduce);
    }

    /** 构造单 item 的 goodsInfo 响应(可指定主体图 img + introduce 列表)。 */
    private static GoodsInfoDto infoItem(String goodsId, String name, String img, List<String> introduce) {
        GoodsInfoDto.Item item = new GoodsInfoDto.Item();
        item.setId(goodsId);
        item.setName(name);
        item.setPrice("239,900 - 309,900");
        item.setImg(img);
        item.setIntroduce(introduce);
        GoodsInfoDto dto = new GoodsInfoDto();
        GoodsInfoDto.GoodsInfoList list = new GoodsInfoDto.GoodsInfoList();
        list.setItems(List.of(item));
        dto.setGoodsinfoList(list);
        return dto;
    }

    /** 已存在的库存车型行(goods_id 唯一键)。 */
    private static CarModelEntity rowFor(Long id, String goodsId, String name) {
        CarModelEntity m = new CarModelEntity();
        m.setId(id);
        m.setGoodsId(goodsId);
        m.setName(name);
        return m;
    }

    @Test
    void 回填_emptyIntroduce_官网无图_无图入库_状态SUCCESS_指纹已收敛() {
        CarModelEntity row = rowFor(22L, "22", "海狮05EV");
        when(client.goodsInfo("22")).thenReturn(infoItem("22", "海狮05EV", List.of()));   // 官网空图
        when(modelMapper.selectOne(any())).thenReturn(row);

        CarModelService.IncrementalOutcome out = service.backfillImages("22");

        assertEquals("官网无图", out.note());
        assertNotNull(out.outcome());
        assertEquals("SUCCESS", row.getSyncStatus());
        assertNotNull(row.getSyncFingerprint());
        assertNull(row.getIntroImages());   // 空 introduce 不冲写 intro_images
        verify(imageService, never()).persistOrReuse(any(), anyString(), any(ImageAssetEntity.class));
        verify(modelMapper).updateById(row);   // 仅轻回填自身一次写回(指纹/状态)
    }

    @Test
    void 回填_有图_persistOrReuse哈希复用_intro_images收敛为id列表() throws Exception {
        // 本地内嵌 HttpServer 模拟官网图片(免外网):返回 PNG 魔数字节
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/img.png", (HttpExchange ex) -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            try (var os = ex.getResponseBody()) { os.write(png); }
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/img.png";
        try {
            CarModelEntity row = rowFor(39L, "39", "海狮08EV");
            when(client.goodsInfo("39")).thenReturn(infoItem("39", "海狮08EV", List.of(url)));
            when(modelMapper.selectOne(any())).thenReturn(row);
            // persistOrReuse 走统一入库管线(内容哈希去重):mock 返回「哈希命中复用」的既有图库资产
            when(imageService.persistOrReuse(any(), eq("png"), any(ImageAssetEntity.class)))
                    .thenAnswer(inv -> {
                        ImageAssetEntity e = new ImageAssetEntity();
                        e.setId(9L);
                        e.setDedupeHit(true);
                        return e;
                    });

            CarModelService.IncrementalOutcome out = service.backfillImages("39");

            assertNull(out.note());   // 有图 → 无备注
            assertEquals("[9]", row.getIntroImages());   // intro_images 收敛为图库 asset id 列表
            assertEquals("SUCCESS", row.getSyncStatus());
            assertNotNull(row.getSyncFingerprint());
            verify(imageService, times(1)).persistOrReuse(any(), eq("png"), any(ImageAssetEntity.class));
            // 入库 preset:全局图库(project_id=null)+ 来源 byd + 自动打标「车型-<名>」
            ArgumentCaptor<ImageAssetEntity> preset = ArgumentCaptor.forClass(ImageAssetEntity.class);
            verify(imageService).persistOrReuse(any(), eq("png"), preset.capture());
            assertNull(preset.getValue().getProjectId());
            assertEquals("byd", preset.getValue().getSource());
            assertEquals(List.of("车型-海狮08EV"), preset.getValue().getTags());
            verify(modelMapper, times(2)).updateById(row);   // persistIntroImages 收敛 id 列表 + 轻回填写指纹/状态
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 回填路径_零触碰_版本参数清洗向量域() throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/img.png", (HttpExchange ex) -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            try (var os = ex.getResponseBody()) { os.write(png); }
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/img.png";
        try {
            CarModelEntity row = rowFor(26L, "26", "2026款海豹07DM-i");
            when(client.goodsInfo("26")).thenReturn(infoItem("26", "2026款海豹07DM-i", List.of(url)));
            when(modelMapper.selectOne(any())).thenReturn(row);
            when(imageService.persistOrReuse(any(), anyString(), any(ImageAssetEntity.class)))
                    .thenAnswer(inv -> {
                        ImageAssetEntity e = new ImageAssetEntity();
                        e.setId(41L);
                        return e;
                    });

            service.backfillImages("26");

            // 回填域边界:除 goodsInfo + 车型主表写回外,版本/参数分组/参数/清洗/切块/向量零交互
            verify(client, never()).goodsParams(anyString());
            verify(client, never()).goodsAttrList(anyString());
            verifyNoInteractions(versionMapper, groupMapper, paramMapper, cleanMapper);
            verifyNoInteractions(cleanService, docService, chunkMapper, vectorStoreService);
        } finally {
            server.stop(0);
        }
    }

    /** 10-21 R1:仅有主体图(img)、无介绍图的车型也必须写封面(introduce 早退不能带走封面)。 */
    @Test
    void 回填_仅有主体图无介绍图_写封面_不动intro_images() throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/cover.png", (HttpExchange ex) -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            try (var os = ex.getResponseBody()) { os.write(png); }
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/cover.png";
        try {
            CarModelEntity row = rowFor(23L, "23", "海狮05EV");
            when(client.goodsInfo("23")).thenReturn(infoItem("23", "海狮05EV", url, List.of()));
            when(modelMapper.selectOne(any())).thenReturn(row);
            when(imageService.persistOrReuse(any(), eq("png"), any(ImageAssetEntity.class)))
                    .thenAnswer(inv -> {
                        ImageAssetEntity e = new ImageAssetEntity();
                        e.setId(88L);
                        return e;
                    });

            CarModelService.IncrementalOutcome out = service.backfillImages("23");

            assertEquals("官网无图", out.note());          // 详情图为空(封面已单独入库)
            assertEquals(88L, row.getCoverImageId());      // 封面写入图库资产 id
            assertNull(row.getIntroImages());              // 无 introduce 不冲写图集
            assertEquals("SUCCESS", row.getSyncStatus());
            assertNull(row.getLastSyncError());            // 成功清除历史失败原因
            // 封面命名与标签契约:<name>-cover.<ext> + [车型-<名>, 车型主图]
            ArgumentCaptor<ImageAssetEntity> preset = ArgumentCaptor.forClass(ImageAssetEntity.class);
            verify(imageService).persistOrReuse(any(), eq("png"), preset.capture());
            assertEquals("海狮05EV-cover.png", preset.getValue().getFileName());
            assertEquals(List.of("车型-海狮05EV", "车型主图"), preset.getValue().getTags());
            assertEquals("byd", preset.getValue().getSource());
            assertNull(preset.getValue().getProjectId());
            verify(modelMapper, times(2)).updateById(row);   // 封面落库 + 轻回填写指纹/状态
        } finally {
            server.stop(0);
        }
    }

    /** 10-21 R1:官网 img 为空时保留旧封面,只更新详情图集。 */
    @Test
    void 回填_主体图为空_保留旧封面_仅更新详情图集() throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/img.png", (HttpExchange ex) -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            try (var os = ex.getResponseBody()) { os.write(png); }
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/img.png";
        try {
            CarModelEntity row = rowFor(24L, "24", "汉EV");
            row.setCoverImageId(5L);   // 旧封面
            when(client.goodsInfo("24")).thenReturn(infoItem("24", "汉EV", null, List.of(url)));
            when(modelMapper.selectOne(any())).thenReturn(row);
            when(imageService.persistOrReuse(any(), eq("png"), any(ImageAssetEntity.class)))
                    .thenAnswer(inv -> {
                        ImageAssetEntity e = new ImageAssetEntity();
                        e.setId(9L);
                        return e;
                    });

            service.backfillImages("24");

            assertEquals(5L, row.getCoverImageId(), "img 为空不得冲写旧封面");
            assertEquals("[9]", row.getIntroImages());
        } finally {
            server.stop(0);
        }
    }

    /** 10-21 R1:封面下载/入库失败时保留旧封面,且不产生额外主表写回。 */
    @Test
    void 封面入库失败_保留旧封面_不写主表() {
        CarModelEntity row = rowFor(25L, "25", "唐EV");
        row.setCoverImageId(5L);
        // 未监听端口 → 连接拒绝,下载失败(走 warn 跳过分支)
        GoodsInfoDto.Item item = infoItem("25", "唐EV", "http://127.0.0.1:1/cover.png", List.of())
                .getGoodsinfoList().getItems().get(0);

        service.persistImages(row, item);

        assertEquals(5L, row.getCoverImageId(), "封面失败不冲写旧封面");
        verify(imageService, never()).persistOrReuse(any(), anyString(), any(ImageAssetEntity.class));
        verify(modelMapper, never()).updateById(any(CarModelEntity.class));
    }

    /** 10-21 R1:全量 syncOne 路径同样写封面(仅有 img 无 introduce)。 */
    @Test
    void 全量同步_有主体图无介绍图_写封面() throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/cover.png", (HttpExchange ex) -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            try (var os = ex.getResponseBody()) { os.write(png); }
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/cover.png";
        try {
            CarModelEntity row = rowFor(26L, "26", "大唐EV");
            when(modelMapper.selectOne(any())).thenReturn(row);
            when(client.goodsInfo("26")).thenReturn(infoItem("26", "大唐EV", url, List.of()));
            when(client.goodsParams("26")).thenReturn(params("尺寸参数", "长×宽×高(mm)", "4780×1890×1670"));
            when(client.goodsAttrList("26")).thenReturn(versions("205领航版"));
            when(imageService.persistOrReuse(any(), eq("png"), any(ImageAssetEntity.class)))
                    .thenAnswer(inv -> {
                        ImageAssetEntity e = new ImageAssetEntity();
                        e.setId(90L);
                        return e;
                    });

            service.syncOne("26");

            assertEquals(90L, row.getCoverImageId());
            assertNull(row.getIntroImages());   // introduce 为空保留旧图集(persistModel 不再写 raw URL)
            assertEquals("SUCCESS", row.getSyncStatus());
            assertNull(row.getLastSyncError());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 回填_行不存在_抛状态异常不复活被删车型() {
        when(modelMapper.selectOne(any())).thenReturn(null);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.backfillImages("99"));
        // 行不存在时连官网都不请求,更不写回任何车型行
        verify(client, never()).goodsInfo(anyString());
        verify(modelMapper, never()).updateById(any(CarModelEntity.class));
    }

    /**
     * 10-15 真机发现(10 车型 goodsInfo 返回空条目):无条目 ≠ 无图——
     * 必须用库存行存量 URL 下载兜底入库 + 库存行快照指纹,否则图片永不入库、指纹永不初始化。
     */
    @Test
    void 回填_GoodsInfo空条目_存量URL兜底下载入库_快照指纹收敛() throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/img.png", (HttpExchange ex) -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            try (var os = ex.getResponseBody()) { os.write(png); }
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/img.png";
        try {
            CarModelEntity row = rowFor(13L, "10029", "海豹智驾版");
            row.setIntroImages("[\"" + url + "\"]");   // 存量旧管线 URL 数组
            // 官网 goodsInfo 返回空条目(items 为空)——走兜底分支
            GoodsInfoDto empty = new GoodsInfoDto();
            when(client.goodsInfo("10029")).thenReturn(empty);
            when(modelMapper.selectOne(any())).thenReturn(row);
            when(imageService.persistOrReuse(any(), eq("png"), any(ImageAssetEntity.class)))
                    .thenAnswer(inv -> {
                        ImageAssetEntity e = new ImageAssetEntity();
                        e.setId(77L);
                        return e;
                    });

            CarModelService.IncrementalOutcome out = service.backfillImages("10029");

            // note 必须可观测;intro_images 收敛 id 列表;库存行快照指纹初始化
            assertEquals("官网无数据,已按存量URL兜底入库1张", out.note());
            assertEquals("[77]", row.getIntroImages());
            // 10-21:官网无数据不得算成功同步(不写 SUCCESS / last_sync_at)
            assertEquals("FAILED", row.getSyncStatus());
            assertEquals("EMPTY", row.getSourceState());
            assertNull(row.getLastSyncAt());
            assertNotNull(row.getSyncFingerprint());
            // 快照指纹与 computeRowSnapshotFingerprint(row) 同口径(row 未变时二次计算应一致)
            assertEquals(CarModelService.computeRowSnapshotFingerprint(row), row.getSyncFingerprint());
            // 指纹必须是 64 位 hex(SHA-256 摘要;防「传原始字节」类缺陷回潮——超长值撞 VARCHAR(64) 直接写库失败)
            assertTrue(row.getSyncFingerprint().matches("[0-9a-f]{64}"));
            verify(imageService, times(1)).persistOrReuse(any(), eq("png"), any(ImageAssetEntity.class));
            verify(client, never()).goodsParams(anyString());
            verify(client, never()).goodsAttrList(anyString());
            verifyNoInteractions(versionMapper, groupMapper, paramMapper, cleanMapper, docService,
                    cleanService, chunkMapper, vectorStoreService);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 回填_GoodsInfo空条目且无存量图_标注无图_不写intro_images() {
        CarModelEntity row = rowFor(33L, "10041", "海狮06EV");
        row.setIntroImages("[]");   // 无 URL 可兜底
        GoodsInfoDto empty = new GoodsInfoDto();   // 空 items
        when(client.goodsInfo("10041")).thenReturn(empty);
        when(modelMapper.selectOne(any())).thenReturn(row);

        CarModelService.IncrementalOutcome out = service.backfillImages("10041");

        assertEquals("官网无数据,且无存量图可兜底", out.note());
        assertEquals("[]", row.getIntroImages());   // 不冲写
        assertEquals("FAILED", row.getSyncStatus());   // 空数据不算成功
        assertEquals("EMPTY", row.getSourceState());
        // 快照指纹仍初始化(行非空快照)——官网恢复后指纹不匹配自动全量收敛
        assertNotNull(row.getSyncFingerprint());
        verify(imageService, never()).persistOrReuse(any(), anyString(), any(ImageAssetEntity.class));
    }

    @Test
    void 指纹_字段规范化口径_同输入稳定_价格变化即变() {
        GoodsInfoDto.Item a = new GoodsInfoDto.Item();
        a.setId("156");
        a.setName("大唐EV");
        a.setSalesNetworkName("王朝");
        a.setPrice("239,900");
        String fa = CarModelService.computeFingerprint(a);
        assertTrue(fa.matches("[0-9a-f]{64}"));
        assertEquals(fa, CarModelService.computeFingerprint(a));   // 同输入稳定(可重算)

        GoodsInfoDto.Item b = new GoodsInfoDto.Item();
        b.setId("156");
        b.setName("大唐EV");
        b.setSalesNetworkName("王朝");
        b.setPrice("239,901");   // 仅价格变化
        assertTrue(fa.matches("[0-9a-f]{64}") && !fa.equals(CarModelService.computeFingerprint(b)),
                "官网可变字段变化应改变指纹");

        GoodsInfoDto.Item c = new GoodsInfoDto.Item();
        c.setId("156");
        c.setName("大唐EV");
        c.setIntroduce(List.of("u1", "u2"));
        GoodsInfoDto.Item d = new GoodsInfoDto.Item();
        d.setId("156");
        d.setName("大唐EV");
        d.setIntroduce(List.of("u2", "u1"));   // introduce 逐项原始顺序敏感
        // 10-21:基础指纹不再包含图片;图片顺序变化只影响 image_fingerprint
        assertEquals(CarModelService.computeFingerprint(c), CarModelService.computeFingerprint(d));
        assertTrue(!CarModelService.computeImageFingerprint(c).equals(CarModelService.computeImageFingerprint(d)),
                "图片列表顺序变化应改变图片指纹");
    }

    private static GoodsParamsDto.ParamsData params(String group, String name, String value) {
        GoodsParamsDto.ParamRow row = new GoodsParamsDto.ParamRow();
        row.setName(name);
        row.setValue(List.of(value));
        GoodsParamsDto.Config cfg = new GoodsParamsDto.Config();
        cfg.setName(group);
        cfg.setValue(List.of(row));
        GoodsParamsDto.ParamsData data = new GoodsParamsDto.ParamsData();
        data.setConfigs(List.of(cfg));
        return data;
    }

    private static List<GoodsAttrListDto.Version> versions(String name) {
        GoodsAttrListDto.Version v = new GoodsAttrListDto.Version();
        v.setName(name);
        return List.of(v);
    }
}