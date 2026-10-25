package com.sparkora.car.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.EmbedStats;
import com.sparkora.domain.entity.CarModelEntity;
import com.sparkora.domain.entity.CarSyncJobEntity;
import com.sparkora.mapper.CarModelMapper;
import com.sparkora.mapper.CarSyncJobMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 车型运维任务分派单测(10-24 车型运维 P0):VECTOR_REBUILD / CLEAN_ONE 两条新分支语义
 * (失败明细可重试、skipped 备注不重试、运维域不写车型同步状态)与 backfill 定向圈定/校验。
 * self==null 时任务经 this.runJob 同步直跑,终态断言可控(与既有任务单测同范式)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CarSyncJobOpsAsyncTest {

    @Mock CarSyncJobMapper jobMapper;
    @Mock CarModelMapper modelMapper;
    @Mock CarModelService modelService;
    @Mock CarCleanRepairService cleanRepairService;

    CarSyncJobService service;

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new CarSyncJobService(jobMapper, modelMapper, modelService, JSON);
        when(jobMapper.selectCount(any(Wrapper.class))).thenReturn(0L);   // 无新鲜 RUNNING 任务
        when(jobMapper.update(isNull(), any(Wrapper.class))).thenReturn(1); // 心跳/进度/终态条件写全部成功
        doAnswer(inv -> {
            ((CarSyncJobEntity) inv.getArgument(0)).setId(42L);
            return 1;
        }).when(jobMapper).insert(any(CarSyncJobEntity.class));
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

    /** 反射调用 parseFailedGoodsIds,锁定「带 error 的失败明细进入 retry 候选」契约。 */
    @SuppressWarnings("unchecked")
    private List<String> retryCandidates(String failedItems) throws Exception {
        Method m = CarSyncJobService.class.getDeclaredMethod("parseFailedGoodsIds", String.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(service, failedItems);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseItems(String failedItems) throws Exception {
        return (List<Map<String, Object>>) (List<?>) JSON.readValue(failedItems,
                JSON.getTypeFactory().constructCollectionType(List.class, Map.class));
    }

    private static CarModelEntity model(Long id, String goodsId, String name) {
        CarModelEntity m = new CarModelEntity();
        m.setId(id);
        m.setGoodsId(goodsId);
        m.setName(name);
        return m;
    }

    private static CarSyncJobEntity runningJob(Long id, String type) {
        CarSyncJobEntity job = new CarSyncJobEntity();
        job.setId(id);
        job.setJobType(type);
        job.setStatus("RUNNING");
        job.setCancelRequested(0);
        return job;
    }

    // ================= VECTOR_REBUILD =================

    @Test
    void 向量重建_部分失败_落失败明细且可成为重试候选() throws Exception {
        when(modelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(model(1L, "156", "海豚")));
        when(modelMapper.selectOne(any())).thenReturn(model(1L, "156", "海豚"));   // 失败明细 name 取车型名
        when(modelService.rebuildOneByGoodsId("156")).thenReturn(new EmbedStats(3, 2, 1));
        CarSyncJobEntity job = runningJob(42L, "VECTOR_REBUILD");
        when(jobMapper.selectById(42L)).thenReturn(job);

        Long jobId = service.createRebuildJob();

        assertEquals(42L, jobId);
        assertEquals("FAILED", job.getStatus());
        assertEquals(1, job.getFailed());
        assertEquals(0, job.getSuccess());
        assertEquals(0, job.getSkipped());
        List<Map<String, Object>> items = parseItems(job.getFailedItems());
        assertEquals(1, items.size());
        assertEquals("156", items.get(0).get("goodsId"));
        assertEquals("海豚", items.get(0).get("name"));
        assertEquals("向量失败 1/3 块", items.get(0).get("error"));
        // 失败条目(带 error 键)进入 retry 候选
        assertEquals(List.of("156"), retryCandidates(job.getFailedItems()));
        // 运维域:向量失败不写车型同步状态
        verify(modelService, never()).markSyncFailed(anyString(), anyString());
    }

    @Test
    void 向量重建_无切块_计skipped带备注() throws Exception {
        when(modelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(model(1L, "156", "海豚")));
        when(modelService.rebuildOneByGoodsId("156")).thenReturn(new EmbedStats(0, 0, 0));
        CarSyncJobEntity job = runningJob(42L, "VECTOR_REBUILD");
        when(jobMapper.selectById(42L)).thenReturn(job);

        service.createRebuildJob();

        assertEquals("SUCCESS", job.getStatus());
        assertEquals(1, job.getSkipped());
        assertEquals(0, job.getSuccess());
        assertEquals(0, job.getFailed());
        assertTrue(job.getFailedItems().contains("跳过:无切块数据"));
        // 备注条目(无 error 键)不进 retry 候选
        assertTrue(retryCandidates(job.getFailedItems()).isEmpty());
    }

    @Test
    void 向量重建_全部成功_计success带备注() {
        when(modelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                model(1L, "156", "海豚"), model(2L, "100", "唐EV")));
        when(modelService.rebuildOneByGoodsId("156")).thenReturn(new EmbedStats(12, 12, 0));
        when(modelService.rebuildOneByGoodsId("100")).thenReturn(new EmbedStats(8, 8, 0));
        CarSyncJobEntity job = runningJob(42L, "VECTOR_REBUILD");
        when(jobMapper.selectById(42L)).thenReturn(job);

        service.createRebuildJob();

        assertEquals("SUCCESS", job.getStatus());
        assertEquals(2, job.getSuccess());
        assertEquals(0, job.getFailed());
        assertEquals(0, job.getSkipped());
        assertTrue(job.getFailedItems().contains("重建 12 块向量"));
        assertTrue(job.getFailedItems().contains("重建 8 块向量"));
    }

    @Test
    void 向量重建_库内无车型_拒建() {
        when(modelMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createRebuildJob());
        assertTrue(ex.getMessage().contains("库内没有可重建向量的车型"));
        verify(jobMapper, never()).insert(any(CarSyncJobEntity.class));
    }

    @Test
    void 定向向量重建_只圈定指定车型() {
        when(modelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                model(1L, "156", "海豚"), model(2L, "100", "唐EV")));
        when(modelService.rebuildOneByGoodsId(anyString())).thenReturn(new EmbedStats(3, 3, 0));
        CarSyncJobEntity job = runningJob(42L, "VECTOR_REBUILD");
        when(jobMapper.selectById(42L)).thenReturn(job);

        Long jobId = service.createRebuildJob(List.of("100"));

        assertEquals(42L, jobId);
        ArgumentCaptor<CarSyncJobEntity> cap = ArgumentCaptor.forClass(CarSyncJobEntity.class);
        verify(jobMapper).insert(cap.capture());
        assertEquals("VECTOR_REBUILD", cap.getValue().getJobType());
        assertEquals(1, cap.getValue().getTotal());
        // 只重建指定车型,其他车型不触发
        verify(modelService).rebuildOneByGoodsId("100");
        verify(modelService, never()).rebuildOneByGoodsId("156");
        // 定向查询只按 goods_id IN,不走全库 id 升序分支
        ArgumentCaptor<Wrapper<CarModelEntity>> listCap = (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
        verify(modelMapper, atLeastOnce()).selectList(listCap.capture());
        String sql = listCap.getAllValues().stream()
                .map(w -> ((AbstractWrapper<?, ?, ?>) w).getSqlSegment())
                .filter(Objects::nonNull)
                .findFirst().orElse("");
        assertTrue(sql.contains("goods_id"), sql);
        assertEquals(1, job.getSuccess());
    }

    @Test
    void 定向向量重建_未知车型_400语义拒建() {
        when(modelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(model(1L, "156", "海豚")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createRebuildJob(List.of("156", "999")));
        assertTrue(ex.getMessage().contains("车型不存在: 999"));
        verify(jobMapper, never()).insert(any(CarSyncJobEntity.class));
    }

    @Test
    void 定向向量重建_null_仍走全库分支() {
        when(modelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                model(1L, "156", "海豚"), model(2L, "100", "唐EV")));
        when(modelService.rebuildOneByGoodsId(anyString())).thenReturn(new EmbedStats(1, 1, 0));
        CarSyncJobEntity job = runningJob(42L, "VECTOR_REBUILD");
        when(jobMapper.selectById(42L)).thenReturn(job);

        // null = 全库(既有行为不变)
        service.createRebuildJob(null);
        assertTrue(job.getFailedItems().contains("重建 1 块向量"));
        verify(modelService).rebuildOneByGoodsId("156");
        verify(modelService).rebuildOneByGoodsId("100");
    }

    @Test
    void 定向向量重建_空列表_仍走全库分支() {
        when(modelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(model(1L, "156", "海豚")));
        when(modelService.rebuildOneByGoodsId(anyString())).thenReturn(new EmbedStats(2, 2, 0));
        CarSyncJobEntity job = runningJob(42L, "VECTOR_REBUILD");
        when(jobMapper.selectById(42L)).thenReturn(job);

        service.createRebuildJob(List.of());
        assertEquals(1, job.getSuccess());
        verify(modelService).rebuildOneByGoodsId("156");
    }

    @Test
    void 向量重建_分支异常_只进任务明细不写车型同步状态() {
        when(modelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(model(1L, "156", "海豚")));
        when(modelService.rebuildOneByGoodsId("156")).thenThrow(new RuntimeException("embedding 服务不可用"));
        CarSyncJobEntity job = runningJob(42L, "VECTOR_REBUILD");
        when(jobMapper.selectById(42L)).thenReturn(job);

        service.createRebuildJob();

        assertEquals("FAILED", job.getStatus());
        assertEquals(1, job.getFailed());
        assertTrue(job.getFailedItems().contains("156"));
        // 运维域:异常分支也不回写车型 syncStatus=FAILED(与全量同步域区分,10-24)
        verify(modelService, never()).markSyncFailed(anyString(), anyString());
    }

    // ================= CLEAN_ONE =================

    @Test
    void 单台清洗_有产出_计success且allowAi为true() {
        setField(service, "cleanRepairService", cleanRepairService);
        when(modelMapper.selectOne(any())).thenReturn(model(7L, "156", "海豚"));
        when(cleanRepairService.cleanOneByGoodsId("156", true))
                .thenReturn(new CarCleanRepairService.CleanRepairResult(7L, "海豚", 10, 0, 8, 8, 0, 0));
        CarSyncJobEntity job = runningJob(42L, "CLEAN_ONE");
        when(jobMapper.selectById(42L)).thenReturn(job);

        Long jobId = service.createCleanOneJob("156");

        assertEquals(42L, jobId);
        ArgumentCaptor<CarSyncJobEntity> cap = ArgumentCaptor.forClass(CarSyncJobEntity.class);
        verify(jobMapper).insert(cap.capture());
        assertEquals("CLEAN_ONE", cap.getValue().getJobType());
        assertEquals(1, cap.getValue().getTotal());
        assertEquals("SUCCESS", job.getStatus());
        assertEquals(1, job.getSuccess());
        assertTrue(job.getFailedItems().contains("补清洗 8 条"));
        // allowAi=true:显式单台重跑允许 AI(区别于批量 CLEAN_BACKFILL 的 false)
        verify(cleanRepairService).cleanOneByGoodsId("156", true);
        verify(cleanRepairService, never()).cleanOneByGoodsId("156", false);
    }

    @Test
    void 单台清洗_无产出_计skipped带备注() throws Exception {
        setField(service, "cleanRepairService", cleanRepairService);
        when(modelMapper.selectOne(any())).thenReturn(model(7L, "156", "海豚"));
        when(cleanRepairService.cleanOneByGoodsId("156", true))
                .thenReturn(new CarCleanRepairService.CleanRepairResult(7L, "海豚", 0, 0, 0, 0, 0, 0));
        CarSyncJobEntity job = runningJob(42L, "CLEAN_ONE");
        when(jobMapper.selectById(42L)).thenReturn(job);

        service.createCleanOneJob("156");

        assertEquals("SUCCESS", job.getStatus());
        assertEquals(1, job.getSkipped());
        assertEquals(0, job.getSuccess());
        assertEquals(0, job.getFailed());
        assertTrue(job.getFailedItems().contains("跳过:无可清洗参数/版本"));
        assertTrue(retryCandidates(job.getFailedItems()).isEmpty());
    }

    @Test
    void 单台清洗_车型不存在_拒建() {
        when(modelMapper.selectOne(any())).thenReturn(null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createCleanOneJob("999"));
        assertTrue(ex.getMessage().contains("车型不存在"));
        verify(jobMapper, never()).insert(any(CarSyncJobEntity.class));
    }

    @Test
    void 单台清洗_goodsId空白_拒建() {
        assertThrows(IllegalArgumentException.class, () -> service.createCleanOneJob(" "));
        verify(jobMapper, never()).insert(any(CarSyncJobEntity.class));
    }

    @Test
    void 单台清洗_分支异常_只进任务明细不写车型同步状态() {
        setField(service, "cleanRepairService", cleanRepairService);
        when(modelMapper.selectOne(any())).thenReturn(model(7L, "156", "海豚"));
        when(cleanRepairService.cleanOneByGoodsId("156", true)).thenThrow(new RuntimeException("清洗中断"));
        CarSyncJobEntity job = runningJob(42L, "CLEAN_ONE");
        when(jobMapper.selectById(42L)).thenReturn(job);

        service.createCleanOneJob("156");

        assertEquals("FAILED", job.getStatus());
        assertEquals(1, job.getFailed());
        assertTrue(job.getFailedItems().contains("156"));
        verify(modelService, never()).markSyncFailed(anyString(), anyString());
    }

    // ================= backfill 定向(10-24 R3) =================

    @Test
    void 定向回填_只圈定指定车型_不走缺失过滤() {
        when(modelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                model(1L, "156", "海豚"), model(2L, "100", "唐EV")));
        when(modelService.backfillImages(anyString()))
                .thenReturn(new CarModelService.IncrementalOutcome(null, null));
        CarSyncJobEntity job = runningJob(42L, "IMAGE_BACKFILL");
        when(jobMapper.selectById(42L)).thenReturn(job);

        Long jobId = service.createBackfillJob(false, List.of("100", "156"));

        assertEquals(42L, jobId);
        ArgumentCaptor<CarSyncJobEntity> cap = ArgumentCaptor.forClass(CarSyncJobEntity.class);
        verify(jobMapper).insert(cap.capture());
        assertEquals("IMAGE_BACKFILL", cap.getValue().getJobType());
        assertEquals(2, cap.getValue().getTotal());
        // 定向模式圈定查询只按 goods_id IN,不含 cover_image_id/source_state 缺失过滤
        ArgumentCaptor<Wrapper<CarModelEntity>> listCap = (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
        verify(modelMapper, atLeastOnce()).selectList(listCap.capture());
        String sql = listCap.getAllValues().stream()
                .map(w -> ((AbstractWrapper<?, ?, ?>) w).getSqlSegment())
                .filter(Objects::nonNull)
                .findFirst().orElse("");
        assertTrue(sql.contains("goods_id"), sql);
        assertFalse(sql.contains("cover_image_id"), "定向模式不走缺失过滤: " + sql);
        verify(modelService).backfillImages("100");
        verify(modelService).backfillImages("156");
        assertEquals(2, job.getSuccess());
    }

    @Test
    void 定向回填_未知车型_400语义拒建() {
        when(modelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(model(1L, "156", "海豚")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createBackfillJob(false, List.of("156", "999")));
        assertTrue(ex.getMessage().contains("车型不存在: 999"));
        verify(jobMapper, never()).insert(any(CarSyncJobEntity.class));
    }

    @Test
    void 定向回填_null列表_走旧缺失过滤分支() {
        when(modelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(model(1L, "156", "海豚")));
        when(modelService.backfillImages("156"))
                .thenReturn(new CarModelService.IncrementalOutcome(null, null));
        CarSyncJobEntity job = runningJob(42L, "IMAGE_BACKFILL");
        when(jobMapper.selectById(42L)).thenReturn(job);

        service.createBackfillJob(false, null);

        // 回归:旧行为 = MISSING 过滤(cover_image_id/source_state 条件在场)
        ArgumentCaptor<Wrapper<CarModelEntity>> listCap = (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
        verify(modelMapper, atLeastOnce()).selectList(listCap.capture());
        String sql = listCap.getAllValues().stream()
                .map(w -> ((AbstractWrapper<?, ?, ?>) w).getSqlSegment())
                .filter(s -> s != null && s.contains("cover_image_id"))
                .findFirst().orElse("");
        assertTrue(sql.contains("source_state"), sql);
        verify(modelService).backfillImages("156");
    }

    @Test
    void 定向回填_空列表_与null同走旧分支() {
        when(modelMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        // 空列表 = 旧行为:过滤后无可回填车型 → 400
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createBackfillJob(false, List.of()));
        assertTrue(ex.getMessage().contains("库内没有可回填的车型"));
    }

    // ================= retry 保留源 job_type(10-25 P2) =================

    /** 构造带失败明细的源任务(failedItems 只含 error 条目才有重试候选)。 */
    private static CarSyncJobEntity finishedJob(Long id, String type, String failedItems) {
        CarSyncJobEntity job = new CarSyncJobEntity();
        job.setId(id);
        job.setJobType(type);
        job.setStatus("FAILED");
        job.setFailedItems(failedItems);
        return job;
    }

    @Test
    void 重试_重建向量任务_保留源类型且只圈定失败车型() {
        String items = "[{\"goodsId\":\"156\",\"name\":\"海豚\",\"error\":\"向量失败 1/3 块\"}]";
        when(jobMapper.selectById(9L)).thenReturn(finishedJob(9L, "VECTOR_REBUILD", items));
        when(jobMapper.selectById(42L)).thenReturn(runningJob(42L, "VECTOR_REBUILD"));
        when(modelService.rebuildOneByGoodsId("156")).thenReturn(new EmbedStats(3, 3, 0));

        Long jobId = service.retry(9L);

        assertEquals(42L, jobId);
        ArgumentCaptor<CarSyncJobEntity> cap = ArgumentCaptor.forClass(CarSyncJobEntity.class);
        verify(jobMapper).insert(cap.capture());
        assertEquals("VECTOR_REBUILD", cap.getValue().getJobType());
        assertEquals(1, cap.getValue().getTotal());
        verify(modelService).rebuildOneByGoodsId("156");
        verify(modelService, never()).syncOne(anyString());
    }

    @Test
    void 重试_单车型清洗_保留源类型() {
        setField(service, "cleanRepairService", cleanRepairService);
        String items = "[{\"goodsId\":\"156\",\"name\":\"海豚\",\"error\":\"清洗中断\"}]";
        when(jobMapper.selectById(9L)).thenReturn(finishedJob(9L, "CLEAN_ONE", items));
        when(jobMapper.selectById(42L)).thenReturn(runningJob(42L, "CLEAN_ONE"));
        when(modelMapper.selectOne(any())).thenReturn(model(7L, "156", "海豚"));
        when(cleanRepairService.cleanOneByGoodsId("156", true))
                .thenReturn(new CarCleanRepairService.CleanRepairResult(7L, "海豚", 3, 3, 8, 8, 0, 0));

        service.retry(9L);

        ArgumentCaptor<CarSyncJobEntity> cap = ArgumentCaptor.forClass(CarSyncJobEntity.class);
        verify(jobMapper).insert(cap.capture());
        assertEquals("CLEAN_ONE", cap.getValue().getJobType());
        assertEquals(1, cap.getValue().getTotal());
        verify(cleanRepairService).cleanOneByGoodsId("156", true);
        verify(modelService, never()).syncOne(anyString());
    }

    @Test
    void 重试_增量任务_退化为RETRY全量同步() {
        String items = "[{\"goodsId\":\"156\",\"name\":\"海豚\",\"error\":\"官网超时\"}]";
        when(jobMapper.selectById(9L)).thenReturn(finishedJob(9L, "INCREMENTAL", items));
        when(jobMapper.selectById(42L)).thenReturn(runningJob(42L, "RETRY"));
        when(modelMapper.selectOne(any())).thenReturn(model(1L, "156", "海豚"));
        when(modelService.syncOne("156")).thenReturn(new CarModelService.SyncOutcome(model(1L, "156", "海豚"), null));

        service.retry(9L);

        ArgumentCaptor<CarSyncJobEntity> cap = ArgumentCaptor.forClass(CarSyncJobEntity.class);
        verify(jobMapper).insert(cap.capture());
        assertEquals("RETRY", cap.getValue().getJobType());
        verify(modelService).syncOne("156");
    }

    // ================= 任务详情派生当前车型(10-25 P2) =================

    @Test
    void 查询任务_运行中补当前车型名() {
        CarSyncJobEntity job = runningJob(42L, "VECTOR_REBUILD");
        job.setCurrentGoodsId("156");
        when(jobMapper.selectById(42L)).thenReturn(job);
        when(modelMapper.selectOne(any())).thenReturn(model(1L, "156", "海豚"));

        CarSyncJobEntity got = service.get(42L);

        assertEquals("156", got.getCurrentGoodsId());
        assertEquals("海豚", got.getCurrentModelName());
    }

    @Test
    void 查询任务_无当前车型_不查车型且派生为空() {
        when(jobMapper.selectById(42L)).thenReturn(runningJob(42L, "VECTOR_REBUILD"));

        CarSyncJobEntity got = service.get(42L);

        assertNull(got.getCurrentModelName());
        verify(modelMapper, never()).selectOne(any());
    }
}