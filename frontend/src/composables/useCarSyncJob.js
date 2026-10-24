import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { carApi } from '../api'

/** 与后端 SYNC_STALE_MS 同口径：RUNNING 超过 60 分钟视为陈旧（JVM 中途死亡遗留）。 */
const RECOVER_STALE_MS = 60 * 60 * 1000

/**
 * 车型同步任务轮询（10-21-knowledge-car-sync-ux）。
 *
 * 知识中心车型 Tab 与 /car/sync 共用：创建任务后 startPoll(jobId)，刷新/切回用
 * recoverLatest() 恢复最近任务；终态自动停止；连续 3 次轮询异常停止并提示。
 */
export function useCarSyncJob() {
  const job = ref(null)
  const jobId = ref(null)
  const retrying = ref(false)
  const cancelling = ref(false)
  /** 已创建任务但尚未拿到首次进度：用于堵住 createJob 返回→首次轮询之间的重复发起窗口。 */
  const starting = ref(false)

  let timer = null
  let failures = 0
  let inFlight = false
  let onFinishedCb = null

  const jobRunning = computed(() => job.value?.status === 'RUNNING')
  /** 任何会与「创建同步任务」冲突的忙碌态（运行中或刚创建待首次进度）。 */
  const jobBusy = computed(() => jobRunning.value || starting.value)

  const progress = computed(() => {
    if (!job.value || !job.value.total) return 0
    const skipped = job.value.skipped != null
      ? (Number(job.value.skipped) || 0)
      : 0
    const processed = (job.value.success || 0) + (job.value.failed || 0) + skipped
    return Math.round((processed / job.value.total) * 100)
  })

  const stopPoll = () => {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  /**
   * 标记「正在创建任务」（createJob 请求已发出、尚未拿到 jobId）。
   * 与 starting 共用同一忙碌位，堵住「createJob 在途 → 首次轮询之前」的重复发起窗口：
   * 请求发起前置位、失败/无 jobId 时 cancelStart() 复位、成功后 startPoll() 接管。
   */
  const beginStart = () => {
    if (jobBusy.value) return false
    starting.value = true
    return true
  }

  /** 创建任务失败时复位忙碌位（成功路径由 startPoll 接管，勿调用）。 */
  const cancelStart = () => {
    if (!jobRunning.value) starting.value = false
  }

  const toastFinished = (data) => {
    const type = data?.jobType
    const done = type === 'IMAGE_BACKFILL' ? '图片补入库完成'
      : type === 'CLEAN_BACKFILL' ? '补清洗完成'
      : type === 'VECTOR_REBUILD' ? '重建向量完成'
      : type === 'CLEAN_ONE' ? '清洗完成'
      : type === 'INCREMENTAL' ? '检查更新完成' : '同步完成'
    const skipped = Number(data?.skipped || 0)
    if (data?.status === 'SUCCESS') {
      if (data?.total && skipped >= data.total) ElMessage.success(`${done}：${skipped} 台无变化`)
      else if (type === 'IMAGE_BACKFILL' || type === 'INCREMENTAL' || type === 'CLEAN_BACKFILL'
        || type === 'VECTOR_REBUILD' || type === 'CLEAN_ONE')
        ElMessage.success(`${done}，处理 ${data.success || 0} 个车型`)
      else ElMessage.success(`同步完成，入库 ${data.success || 0} 个车型`)
    } else if ((data?.failed || 0) > 0) {
      ElMessage.warning(`${done}，${data.success || 0} 成功 / ${data.failed} 失败${skipped ? ` / ${skipped} 跳过` : ''}`)
    }
  }

  const poll = async () => {
    const id = jobId.value
    if (!id || inFlight) return
    inFlight = true
    try {
      const res = await carApi.getJob(id)
      // 轮询期间已切换到新任务：丢弃旧响应，避免旧任务覆盖新任务面板
      if (id !== jobId.value) return
      if (res.code !== 0) throw new Error(res.msg || '任务查询失败')
      if (!res.data) throw new Error('任务查询返回空数据')
      failures = 0
      starting.value = false
      job.value = res.data
      if (res.data.status !== 'RUNNING') {
        stopPoll()
        toastFinished(res.data)
        const cb = onFinishedCb
        onFinishedCb = null
        if (cb) await cb(res.data)
      }
    } catch (e) {
      if (id !== jobId.value) return
      failures += 1
      // 单次失败不清忙碌位：任务可能仍在跑（网络抖动），2s 后重试；
      // 只有连续 3 次失败停止轮询后才解除，避免「假空闲」窗口内重复建任务。
      if (failures >= 3) {
        starting.value = false
        stopPoll()
        ElMessage.warning('任务进度查询连续失败，已停止轮询，请稍后刷新重试')
      }
    } finally {
      inFlight = false
    }
  }

  /** 开始轮询指定任务；onFinished 在进入终态后执行（如刷新列表）。 */
  const startPoll = (id, opts = {}) => {
    if (!id) return
    stopPoll()
    jobId.value = id
    failures = 0
    starting.value = true
    onFinishedCb = typeof opts.onFinished === 'function' ? opts.onFinished : null
    timer = setInterval(poll, 2000)
    poll()
  }

  /**
   * 恢复最近任务：优先**未过期**的 RUNNING；否则取最近一条 24h 内的终态任务展示。
   * 查不到/接口失败静默（页面不因任务恢复失败而报错）。
   *
   * 与后端 `CarSyncJobService.SYNC_STALE_MS` 同口径（startedAt 超 60 分钟视为陈旧）：
   * JVM 中途死亡会留下永久 RUNNING 行（定时自愈只在 car_sync_enabled=true 时跑），
   * 若不排除，前端会一直把它当「运行中」→ jobBusy 恒真 → 无法再发起任何同步。
   */
  const recoverLatest = async (opts = {}) => {
    try {
      const res = await carApi.listJobs()
      if (res.code !== 0 || !Array.isArray(res.data)) return
      const now = Date.now()
      const freshRunning = res.data.find(j => {
        if (j?.status !== 'RUNNING') return false
        const t = Date.parse(j?.heartbeatAt || j?.startedAt || j?.createdAt || '')
        return Number.isFinite(t) && now - t < RECOVER_STALE_MS
      })
      if (freshRunning) {
        startPoll(freshRunning.id, opts)
        return
      }
      // 终态任务才可恢复展示；陈旧 RUNNING / 无时间的行一律跳过（不阻塞新建任务）
      const recent = res.data.find(j => {
        if (j?.status === 'RUNNING') return false
        const t = Date.parse(j?.finishedAt || j?.createdAt || '')
        return Number.isFinite(t) && now - t < 24 * 60 * 60 * 1000
      })
      if (recent) {
        job.value = recent
        jobId.value = recent.id
      }
    } catch {
      // 静默：恢复历史任务失败不影响主列表
    }
  }

  /** 请求取消当前运行中任务：后端在当前车型跑完后停止后续。 */
  const cancelCurrent = async () => {
    if (!jobId.value || cancelling.value || !jobRunning.value) return false
    cancelling.value = true
    try {
      const res = await carApi.cancelJob(jobId.value)
      if (res.code === 0) {
        ElMessage.info('已请求取消：当前车型结束后停止后续（已开始的车型可能仍会完成）')
        return true
      }
      ElMessage.warning(res.msg || '取消失败')
      return false
    } catch (e) {
      ElMessage.warning(e?.response?.data?.msg || '取消失败，请稍后重试')
      return false
    } finally {
      cancelling.value = false
    }
  }

  /** 重试当前任务失败项（返回新任务并继续轮询）。 */
  const retryCurrent = async (opts = {}) => {
    if (!jobId.value || retrying.value) return
    if (!beginStart()) return   // 重试也是「建任务」：堵住请求在途时的并发创建
    retrying.value = true
    try {
      const res = await carApi.retryJob(jobId.value)
      if (res.code === 0 && res.data?.jobId) {
        job.value = null
        startPoll(res.data.jobId, opts)
      } else {
        cancelStart()
        ElMessage.error(res.msg || '重试失败')
      }
    } catch (e) {
      cancelStart()
      ElMessage.error(e?.response?.data?.msg || '重试失败，请稍后重试')
    } finally {
      retrying.value = false
    }
  }

  return {
    job,
    jobId,
    jobRunning,
    jobBusy,
    starting,
    progress,
    retrying,
    cancelling,
    cancelCurrent,
    startPoll,
    stopPoll,
    beginStart,
    cancelStart,
    recoverLatest,
    retryCurrent
  }
}
