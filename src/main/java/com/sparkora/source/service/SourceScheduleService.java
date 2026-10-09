package com.sparkora.source.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sparkora.config.SourceProperties;
import com.sparkora.domain.entity.SourceEntity;
import com.sparkora.mapper.SourceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

/**
 * 信源动态调度(10-05-source-crawl-base,父 design §3)。
 *
 * <p>{@code @Scheduled} 的 cron 是编译期固定的,无法「每源不同 cron + 运行时增删」;故以
 * {@link TaskScheduler} + {@link CronTrigger} 在启动(ApplicationRunner)与信源变更时按注册表逐源注册/注销。
 * map 以 sourceId 为键天然幂等(重复注册先注销旧任务)。
 *
 * <p><b>发布窗口</b>:{@code window_start_day/window_end_day} 非空时注册<b>每日 cron</b>,任务执行时判断
 * 「今日在窗口内 && 本批未完成」才真正采集;成功后按 {@code (sourceId, 年月)} 记完成标记,窗口内剩余日跳过;
 * 失败/部分失败次日重试。无窗口的源退回其自身 {@code cron}(单点)。
 *
 * <p><b>资源红线</b>:全局串行({@link SourceJobService} 内含全局锁);同源运行中跳过。不得每源 @Async 并发。
 */
@Slf4j
@Component
public class SourceScheduleService {

    /** 发布窗口源使用的每日 cron(03:30,与新闻同步错峰)。 */
    static final String DAILY_CRON = "0 30 3 * * ?";

    /** 每日触发时刻(与 {@link #DAILY_CRON} 对齐,用于下次运行时间计算)。 */
    static final java.time.LocalTime DAILY_TIME = java.time.LocalTime.of(3, 30);

    private final SourceMapper sourceMapper;
    private final SourceJobService jobService;
    private final SourceProperties props;
    private final TaskScheduler taskScheduler;
    private final Supplier<LocalDate> dateSupplier;
    private final Supplier<LocalDateTime> nowSupplier;

    /** sourceId -> 调度句柄(改 cron/停用先 cancel 再重注册)。 */
    private final Map<Long, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    @Autowired
    public SourceScheduleService(SourceMapper sourceMapper, SourceJobService jobService,
                                 SourceProperties props, TaskScheduler taskScheduler) {
        this(sourceMapper, jobService, props, taskScheduler, LocalDate::now, LocalDateTime::now);
    }

    /** 测试可注入日期(包级可见)。 */
    SourceScheduleService(SourceMapper sourceMapper, SourceJobService jobService, SourceProperties props,
                          TaskScheduler taskScheduler, Supplier<LocalDate> dateSupplier) {
        this(sourceMapper, jobService, props, taskScheduler, dateSupplier, LocalDateTime::now);
    }

    /** 测试可注入日期与当前时刻(包级可见;G6 nextRunAt 计算需可控 now)。 */
    SourceScheduleService(SourceMapper sourceMapper, SourceJobService jobService, SourceProperties props,
                          TaskScheduler taskScheduler, Supplier<LocalDate> dateSupplier,
                          Supplier<LocalDateTime> nowSupplier) {
        this.sourceMapper = sourceMapper;
        this.jobService = jobService;
        this.props = props;
        this.taskScheduler = taskScheduler;
        this.dateSupplier = dateSupplier;
        this.nowSupplier = nowSupplier;
    }

    /** 启动/信源变更后:按注册表逐条注册(先清后建)。 */
    public int registerAll() {
        tasks.keySet().forEach(this::unregister);
        if (!props.isCollectEnabled()) {
            log.info("信源采集总开关关闭(SOURCE_COLLECT_ENABLED=false),不注册任何调度");
            return 0;
        }
        int n = 0;
        for (SourceEntity src : sourceMapper.selectList(new QueryWrapper<SourceEntity>().eq("enabled", true))) {
            if (register(src)) n++;
        }
        log.info("信源动态调度注册 {} 个源", n);
        return n;
    }

    /** 注册/重注册单个源:停用或总开关关闭则仅注销。 */
    public boolean register(SourceEntity src) {
        if (src == null || src.getId() == null) return false;
        unregister(src.getId());
        if (!props.isCollectEnabled()) return false;
        if (!Boolean.TRUE.equals(src.getEnabled())) return false;
        String cron = cronFor(src);
        if (cron == null || cron.isBlank()) return false;
        try {
            Long sourceId = src.getId();
            ScheduledFuture<?> f = taskScheduler.schedule(() -> runScheduled(sourceId), new CronTrigger(cron));
            if (f != null) {
                tasks.put(sourceId, f);
                log.info("信源 {} 已注册调度 cron={}", sourceId, cron);
                return true;
            }
        } catch (Exception e) {
            log.warn("信源 {} 注册调度失败: {}", src.getId(), e.getMessage());
        }
        return false;
    }

    /** 注销单个源的调度(停用/删除/改 cron 前调用)。 */
    public void unregister(Long sourceId) {
        ScheduledFuture<?> f = tasks.remove(sourceId);
        if (f != null) f.cancel(false);
    }

    /** 当前已注册的源数(观测/测试用)。 */
    public int registeredCount() {
        return tasks.size();
    }

    /** 是否已注册该源(测试用)。 */
    public boolean isRegistered(Long sourceId) {
        return tasks.containsKey(sourceId);
    }

    /**
     * 调度触发入口:总开关关闭/源不存在/停用 → 跳过;发布窗口外的源按窗口语义判断。
     * 全局防重叠(markStale + 同源 hasFreshRunning),触发只创建任务。
     */
    public void runScheduled(Long sourceId) {
        if (!props.isCollectEnabled()) return;
        SourceEntity src = sourceMapper.selectById(sourceId);
        if (src == null || !Boolean.TRUE.equals(src.getEnabled())) return;

        String batchKey = null;
        if (hasWindow(src)) {
            LocalDate today = dateSupplier.get();
            if (!inWindow(src, today.getDayOfMonth())) {
                return;   // 非窗口期不触发
            }
            batchKey = batchKey(src.getId(), YearMonth.from(today));
            if (batchKey.equals(src.getLastBatchKey())) {
                log.info("信源 {} 本批({})已采完,窗口内跳过", sourceId, batchKey);
                return;   // 窗口内成功后跳过
            }
        }
        try {
            jobService.markStaleRunningAsFailed();
            if (jobService.hasFreshRunning(sourceId)) {
                log.warn("信源 {} 已有运行中任务,跳过本轮", sourceId);
                return;
            }
            Long jobId = jobService.createJob(sourceId, null, "SCHEDULED", batchKey);
            log.info("信源 {} 已创建定时采集任务 jobId={} batchKey={}", sourceId, jobId, batchKey);
        } catch (Exception e) {
            log.error("信源 {} 定时采集失败: {}", sourceId, e.getMessage(), e);
        }
    }

    /** 调度 cron:发布窗口源用每日 cron;否则用源自身 cron。 */
    static String cronFor(SourceEntity src) {
        return hasWindow(src) ? DAILY_CRON : src.getCron();
    }

    /**
     * 计算某源下次运行时间(G6/R11,只读;不改调度注册)。
     *
     * <ul>
     *   <li>总开关关闭({@code collectEnabled=false})或源停用 → {@code null}(面板显示「已停用」/「未启用调度」);</li>
     *   <li>有发布窗口:今日在窗口内、本批未完成且当前早于每日 03:30 → 今日 03:30;否则下一个窗口起始日 03:30;</li>
     *   <li>无窗口:用 {@link CronExpression#parse} 计算 {@code next(now)}。</li>
     * </ul>
     *
     * <p>异常(非法 cron 等)降级为 {@code null}(面板显示「—」),不阻断列表。
     */
    public LocalDateTime nextRunAt(SourceEntity src) {
        if (src == null || !Boolean.TRUE.equals(src.getEnabled())) return null;
        if (!props.isCollectEnabled()) return null;
        try {
            LocalDate today = dateSupplier.get();
            if (hasWindow(src)) {
                LocalDateTime todayRun = today.atTime(DAILY_TIME);
                boolean batchDone = src.getLastBatchKey() != null
                        && src.getLastBatchKey().equals(batchKey(src.getId(), YearMonth.from(today)));
                if (inWindow(src, today.getDayOfMonth()) && !batchDone) {
                    // 窗口内、本批未完成:今日 03:30 未到 → 今日;已过 → 次日 03:30(仍在窗口内则次日,否则下月窗口起始日)
                    if (nowSupplier.get().isBefore(todayRun)) return todayRun;
                    LocalDate tomorrow = today.plusDays(1);
                    if (inWindow(src, tomorrow.getDayOfMonth())
                            && YearMonth.from(tomorrow).equals(YearMonth.from(today))) {
                        return tomorrow.atTime(DAILY_TIME);
                    }
                }
                // 下一个窗口起始日:今日窗口尚未开始 → 本月窗口起始日;否则 → 下月窗口起始日
                int startDay = Math.min(src.getWindowStartDay(), src.getWindowEndDay());
                LocalDate thisMonthStart = today.withDayOfMonth(1).plusDays(Math.max(1, startDay) - 1L);
                LocalDate start = today.isBefore(thisMonthStart) ? thisMonthStart : thisMonthStart.plusMonths(1);
                return start.atTime(DAILY_TIME);
            }
            String cron = src.getCron();
            if (cron == null || cron.isBlank()) return null;
            return CronExpression.parse(cron).next(nowSupplier.get());
        } catch (Exception e) {
            log.warn("信源 {} 下次运行时间计算失败(降级 null): {}", src.getId(), e.getClass().getSimpleName());
            return null;
        }
    }

    /** 是否配置发布窗口(起止日均非空)。 */
    static boolean hasWindow(SourceEntity src) {
        return src.getWindowStartDay() != null && src.getWindowEndDay() != null;
    }

    /** 今日是否落在窗口内(含端点)。 */
    static boolean inWindow(SourceEntity src, int dayOfMonth) {
        int start = Math.min(src.getWindowStartDay(), src.getWindowEndDay());
        int end = Math.max(src.getWindowStartDay(), src.getWindowEndDay());
        return dayOfMonth >= start && dayOfMonth <= end;
    }

    /** 发布窗口批次键(sourceId + 年月):成功后写入 last_batch_key,窗口内剩余日跳过。 */
    static String batchKey(Long sourceId, YearMonth ym) {
        return sourceId + "-" + ym;
    }
}
