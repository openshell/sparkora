package com.sparkora.car.service;

import com.sparkora.mapper.CarSyncLockMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 车型同步互斥锁(10-21-car-sync-safety)。
 *
 * <p>用途:
 * <ul>
 *   <li>{@link #GLOBAL_JOB_KEY}:全局任务串行——同一时刻只允许一个车型同步任务运行;</li>
 *   <li>{@code goods:<goodsId>}:同一车型同一时刻只允许一个同步/删除动作,避免重复版本/参数。</li>
 * </ul>
 *
 * <p>锁行带过期时间(进程崩溃自愈):过期后可被新 owner 抢占;释放时校验 owner,避免误删他人锁。
 * TTL 略大于任务陈旧阈值(60min),默认 70min。
 */
@Slf4j
@Service
public class CarSyncLockService {

    public static final String GLOBAL_JOB_KEY = "car:job";
    /** 略大于后端 60min 陈旧阈值:崩溃后最多 10 分钟即可被新任务抢占,避免 2h 死锁。 */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(70);

    private final CarSyncLockMapper mapper;

    public CarSyncLockService(CarSyncLockMapper mapper) {
        this.mapper = mapper;
    }

    /** 车型锁 key。 */
    public static String goodsKey(String goodsId) {
        return "car:goods:" + goodsId;
    }

    /**
     * 尝试抢锁。
     *
     * @return owner 令牌(成功) / null(仍被持有);调用方成功后必须 {@link #unlock}
     */
    public String tryLock(String key) {
        return tryLock(key, UUID.randomUUID().toString());
    }

    /** 指定 owner 抢锁(任务锁用 job:<id> 作为稳定 owner,便于异步线程释放)。 */
    public String tryLock(String key, String owner) {
        try {
            LocalDateTime now = LocalDateTime.now();
            int n = mapper.tryAcquire(key, owner, now.plus(DEFAULT_TTL), now);
            return n > 0 ? owner : null;
        } catch (Exception e) {
            // 锁表故障不应让同步完全不可用:降级为「无锁」并告警(宁可偶发并发,不可整体停摆)
            log.warn("车型同步锁获取失败(降级放行) key={}: {}", key, e.getMessage());
            return "degraded:" + owner;
        }
    }

    /** 释放锁(owner 校验;降级令牌同样尝试删除)。 */
    public void unlock(String key, String owner) {
        if (key == null || owner == null) return;
        try {
            mapper.release(key, owner);
        } catch (Exception e) {
            log.warn("车型同步锁释放失败(忽略) key={}: {}", key, e.getMessage());
        }
    }
}
