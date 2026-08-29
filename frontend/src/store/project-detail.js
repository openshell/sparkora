/**
 * 项目详情数据域唯一数据源(Pinia)
 *
 * 解决的问题:ProjectLayout / StepBrief / StepVersions 三级各自独立加载,
 * 切换步骤即销毁重建组件并重新请求 —— 一是浪费、二是时序竞态(轮询 × 切页 × 重建)、
 * 三是任一环异常就让页面退化成"空白/引导语"。现以项目 id 为键缓存三层共享数据:
 *   project(项目详情)/ brief(创作简报)/ versions(版本列表)/ styles(风格库)
 * 组件只在这里读写,不再各自 onMounted 请求;同一项目切换子步骤瞬时直出缓存。
 *
 * 状态映射(状态机判定)仍以 frontend/src/constants/project.js 为唯一事实源。
 */
import { defineStore } from 'pinia'
import { projectApi, styleApi } from '../api'

export const useProjectDetailStore = defineStore('projectDetail', {
  state: () => ({
    // 以 String(projectId) 为键:{ project, brief, versions, styles,
    //   projectError, briefError, versionsError, stylesError }
    map: {},
    loadSeq: 0,        // 请求序号:活性检查,防止过期响应写入新路由的数据
    pollingTimer: null // 生成中状态轮询(全应用唯一)
  }),

  getters: {
    entry: (s) => (id) => s.map[String(id)],
    project: (s) => (id) => s.map[String(id)]?.project || null,
    brief: (s) => (id) => s.map[String(id)]?.brief || null,
    versions: (s) => (id) => s.map[String(id)]?.versions || [],
    styles: (s) => (id) => s.map[String(id)]?.styles || [],
    projectError: (s) => (id) => s.map[String(id)]?.projectError || '',
    briefError: (s) => (id) => s.map[String(id)]?.briefError || '',
    versionsError: (s) => (id) => s.map[String(id)]?.versionsError || '',
    stylesError: (s) => (id) => s.map[String(id)]?.stylesError || ''
  },

  actions: {
    _entryOf(id) {
      const key = String(id)
      if (!this.map[key]) {
        this.map[key] = {
          project: null, brief: null, versions: [], styles: [],
          projectError: '', briefError: '', versionsError: '', stylesError: '',
          _projectLoading: false, _briefLoading: false, _versionsLoading: false, _stylesLoading: false
        }
      }
      return this.map[key]
    },

    _netMsg(e) {
      return e?.response?.data?.msg || e?.message || '网络异常，请稍后重试'
    },

    /** 活性检查:过期响应一律通过 loadSeq 丢弃(见各 ensure 方法) */
    /** 项目详情(简报生成中的轮询也走它);force=true 时无视缓存,强制刷新 */
    async ensureProject(id, { force = false } = {}) {
      const e0 = this._entryOf(id)
      if (!force && e0.project) return e0.project       // 已有数据:直出缓存
      if (e0._projectLoading) return e0.project         // 在途请求:并发去重
      e0._projectLoading = true
      e0.projectError = ''
      const seq = ++this.loadSeq
      try {
        const res = await projectApi.get(id)
        if (seq !== this.loadSeq) return this.project(id) // 已有更新的请求,丢弃本响应
        const e = this._entryOf(id)
        e.project = res.data
        return e.project
      } catch (err) {
        if (seq !== this.loadSeq) return this.project(id)
        this._entryOf(id).projectError = this._netMsg(err)
        return null
      } finally {
        e0._projectLoading = false
      }
    },

    async ensureBrief(id, { force = false } = {}) {
      const e0 = this._entryOf(id)
      if (!force && e0.brief) return e0.brief
      if (e0._briefLoading) return e0.brief
      e0._briefLoading = true
      e0.briefError = ''
      try {
        const res = await projectApi.getBrief(id)
        // 无简报时后端返回 HTTP 200 + data:null(正常路径),brief=null 展示引导语属预期
        this._entryOf(id).brief = parseBrief(res.data)
        return this.brief(id)
      } catch (err) {
        this._entryOf(id).briefError = this._netMsg(err)
        return null
      } finally {
        e0._briefLoading = false
      }
    },

    async ensureVersions(id, { force = false } = {}) {
      const e0 = this._entryOf(id)
      if (!force && e0.versions.length) return e0.versions
      if (e0._versionsLoading) return e0.versions
      e0._versionsLoading = true
      e0.versionsError = ''
      try {
        const res = await projectApi.listVersions(id)
        this._entryOf(id).versions = res.data || []
        return this.versions(id)
      } catch (err) {
        this._entryOf(id).versionsError = this._netMsg(err)
        return []
      } finally {
        e0._versionsLoading = false
      }
    },

    async ensureStyles(id, { force = false } = {}) {
      const e0 = this._entryOf(id)
      if (!force && e0.styles.length) return e0.styles
      if (e0._stylesLoading) return e0.styles
      e0._stylesLoading = true
      e0.stylesError = ''
      try {
        const res = await styleApi.list(true)
        this._entryOf(id).styles = res.data || []
        return this.styles(id)
      } catch (err) {
        this._entryOf(id).stylesError = this._netMsg(err)
        return []
      } finally {
        e0._stylesLoading = false
      }
    },

    /** 生成中轮询:GENerating_* 期间每 4s 刷详情,状态翻转即拉取相应数据后停止 */
    startPolling(id, { intervalMs = 4000 } = {}) {
      this.stopPolling()
      this.pollingTimer = setInterval(async () => {
        const before = this.project(id)?.status
        await this.ensureProject(id, { force: true })
        const after = this.project(id)?.status
        if (!after || after === before) return
        // 状态翻转:按新状态拉取对应数据,然后停止轮询
        if (after === 'READY') { await this.ensureBrief(id, { force: true }); this.stopPolling() }
        else if (after === 'VERSIONS_READY' || after === 'DRAFT') { await this.ensureVersions(id, { force: true }); this.stopPolling() }
      }, intervalMs)
    },

    stopPolling() {
      if (this.pollingTimer) { clearInterval(this.pollingTimer); this.pollingTimer = null }
    },

    /** 本项目数据全部作废(生成动作后强制重取用;离开页面不清理缓存,切回仍可直出) */
    invalidate(id) {
      const e = this.map[String(id)]
      if (!e) return
      e.brief = null; e.versions = []
    },

    /** 登出/长期离开时调用:清空全部缓存与轮询 */
    resetAll() {
      this.stopPolling()
      this.loadSeq++
      this.map = {}
    }
  }
})

/** StepBrief.parseBrief 原样搬运:JSON 字符串列反序列化为数组 */
export function parseBrief(b) {
  if (!b) return null
  const j = (s) => { try { return JSON.parse(s) } catch { return [] } }
  return { ...b, titleCandidates: j(b.titleCandidates), coreViewpoints: j(b.coreViewpoints),
    outline: j(b.outline), factRisks: j(b.factRisks) }
}