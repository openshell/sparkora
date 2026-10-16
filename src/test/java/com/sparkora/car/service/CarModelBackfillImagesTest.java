package com.sparkora.car.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.car.client.BydCmsClient;
import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.car.dto.GoodsInfoDto;
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
    }

    /** 构造单 item 的 goodsInfo 响应(可指定 introduce 列表)。 */
    private static GoodsInfoDto infoItem(String goodsId, String name, List<String> introduce) {
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
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
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
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/img.png", (HttpExchange ex) -> {
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

    @Test
    void 回填_行不存在_抛状态异常不复活被删车型() {
        when(client.goodsInfo("99")).thenReturn(infoItem("99", "已删除车型", List.of()));
        when(modelMapper.selectOne(any())).thenReturn(null);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.backfillImages("99"));
        verify(modelMapper, never()).updateById(any(CarModelEntity.class));
    }

    /**
     * 10-15 真机发现(10 车型 goodsInfo 返回空条目):无条目 ≠ 无图——
     * 必须用库存行存量 URL 下载兜底入库 + 库存行快照指纹,否则图片永不入库、指纹永不初始化。
     */
    @Test
    void 回填_GoodsInfo空条目_存量URL兜底下载入库_快照指纹收敛() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/img.png", (HttpExchange ex) -> {
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
            assertEquals("SUCCESS", row.getSyncStatus());
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
        assertEquals("SUCCESS", row.getSyncStatus());
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
        assertTrue(!CarModelService.computeFingerprint(c).equals(CarModelService.computeFingerprint(d)),
                "图片列表顺序变化应改变指纹");
    }
}