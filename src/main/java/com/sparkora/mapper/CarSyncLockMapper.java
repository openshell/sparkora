package com.sparkora.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 车型同步互斥锁 mapper(10-21-car-sync-safety)。
 *
 * <p>表 {@code sparkora_car_sync_lock(lock_key PK, owner, expires_at)}。抢占用
 * {@code INSERT ... ON CONFLICT DO UPDATE ... WHERE expires_at < now()} 的原子性:
 * 影响行数=1 表示抢到(新键或已过期),0 表示仍被未过期持有。
 */
@Mapper
public interface CarSyncLockMapper {

    // now 必须由调用方传 JVM 时间:expires_at 也是 JVM 写入,若与 DB CURRENT_TIMESTAMP 混用,
    // 部署时区不一致会让过期判定偏移数小时(锁被多持有/提前失效)。
    @Insert("INSERT INTO sparkora_car_sync_lock(lock_key, owner, expires_at) "
            + "VALUES(#{lockKey}, #{owner}, #{expiresAt}) "
            + "ON CONFLICT (lock_key) DO UPDATE SET owner = #{owner}, expires_at = #{expiresAt}, created_at = #{now} "
            + "WHERE sparkora_car_sync_lock.expires_at < #{now}")
    int tryAcquire(@Param("lockKey") String lockKey,
                   @Param("owner") String owner,
                   @Param("expiresAt") LocalDateTime expiresAt,
                   @Param("now") LocalDateTime now);

    @Delete("DELETE FROM sparkora_car_sync_lock WHERE lock_key = #{lockKey} AND owner = #{owner}")
    int release(@Param("lockKey") String lockKey, @Param("owner") String owner);
}
