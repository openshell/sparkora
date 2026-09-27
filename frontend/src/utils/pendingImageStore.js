/**
 * 剪贴板粘贴图的会话暂存区 + 正文占位 token 工具（09-27-preview-clipboard-image）。
 *
 * 用途：预览页粘贴图片时**不再立即上传七牛**，而是在本模块级单例登记 File，正文只插入
 * 占位 token `![](sparkora-img:<id>)`；用户点预览页「去发布 →」时才逐张上传图床、把 token
 * 替换为公网 URL（见 composables/usePendingImageFlush.js）。
 *
 * 约定：
 * - 模块级单例，**不做持久化**（刷新/关页/换设备即丢；Blob 体积大不适合 localStorage/IndexedDB，
 *   与 utils/imageRefCache.js 同一约定）。
 * - 条目结构：`{ id, projectId, file, previewUrl, createdAt }`；`id` 为会话内唯一短串
 *   （仅含 `[A-Za-z0-9_-]`，与正文 token 正则一致）。
 * - **ObjectURL 所有权**：同一 File 全局只建一个 URL（跨条目按 `file` 引用去重）；
 *   remove/clear 时确认无其他条目仍引用该 File 才 revoke（复用 imageRefCache 范式）。
 * - 暂存图**不登记** `sparkora_article_version_image`（沿用「markdown 为渲染真值、手动插图不登记」
 *   的既有口径，见 docs/spec/image.md 已知限制）。
 */

/** 正文占位 token 的正则（id 仅含 URL 安全字符）。 */
export const TOKEN_RE = /sparkora-img:([A-Za-z0-9_-]+)/g

/** token 前缀（后端发布防呆只做存在性判断，与前端同一字面量）。 */
const TOKEN_PREFIX = 'sparkora-img:'

/** @type {Map<string, object>} key=id -> 条目 */
const store = new Map()
let seq = 0

/** 释放 ObjectURL（仅 blob:；幂等，重复 revoke 无副作用）。 */
const revoke = (url) => {
  if (url && typeof url === 'string' && url.startsWith('blob:')) {
    try { URL.revokeObjectURL(url) } catch (e) { /* 已释放/环境不支持：忽略 */ }
  }
}

/** 是否仍有其他条目引用同一 File（决定删除某条时能否安全 revoke 其 ObjectURL）。 */
const otherEntryUsesFile = (file, exceptId) => {
  if (!file) return false
  for (const [k, v] of store) if (k !== exceptId && v.file === file) return true
  return false
}

/** 为 File 取自有 ObjectURL：已有条目持有同一 File 的 URL 则复用，否则新建。 */
const urlForFile = (file) => {
  if (!file) return ''
  for (const v of store.values()) {
    if (v.file === file && v.previewUrl) return v.previewUrl
  }
  try { return URL.createObjectURL(file) } catch (e) { return '' }
}

/**
 * 登记一张待上传图片（不发起网络请求）。
 * @param {number|string} projectId 所属项目（按项目隔离，切换项目时 clearProject 释放）
 * @param {File} file 已按 MIME 补扩展名的图片文件（校验在调用方完成）
 * @returns {{id: string, previewUrl: string}|null} 会话内唯一 id 与本地预览 URL（失败为 null）
 */
export function add(projectId, file) {
  if (!file) return null
  const id = `i${(++seq).toString(36)}${Math.random().toString(36).slice(2, 7)}`
  const entry = {
    id,
    projectId: String(projectId ?? ''),
    file,
    previewUrl: urlForFile(file),
    createdAt: Date.now()
  }
  store.set(id, entry)
  return { id, previewUrl: entry.previewUrl }
}

/** 读取一条暂存条目（未命中返回 undefined）。 */
export function get(id) {
  if (id == null) return undefined
  return store.get(String(id))
}

/** 取 token 对应的本地预览 URL（无条目返回空串，调用方保留原 src 显示破图）。 */
export function previewUrl(id) {
  if (id == null) return ''
  return store.get(String(id))?.previewUrl || ''
}

/** 某项目下全部暂存条目（保登记顺序）。 */
export function entries(projectId) {
  const p = String(projectId ?? '')
  return [...store.values()].filter((e) => e.projectId === p)
}

/** 某项目是否仍有暂存条目（发布页/复制防呆用）。 */
export function hasAny(projectId) {
  const p = String(projectId ?? '')
  for (const e of store.values()) if (e.projectId === p) return true
  return false
}

/** 删除一条暂存条目；仅当无其他条目共享该 File 时释放其 ObjectURL。 */
export function remove(id) {
  if (id == null) return
  const key = String(id)
  const entry = store.get(key)
  if (!entry) return
  store.delete(key)
  if (entry.previewUrl && !otherEntryUsesFile(entry.file, key)) revoke(entry.previewUrl)
}

/** 清空某项目全部暂存条目并释放 ObjectURL（项目切换时调用，保证跨项目隔离）。 */
export function clearProject(projectId) {
  for (const e of entries(projectId)) remove(e.id)
}

/**
 * 只保留当前项目的暂存条目，清空其余全部并释放 ObjectURL。
 *
 * 为什么需要它：`clearProject(旧 id)` 依赖 `watch(projectId)` 的旧值，只能覆盖「同一组件实例内
 * 路由参数变更」；用户经项目列表切到另一项目时 StepPreview **卸载后重新挂载**，watch 首帧
 * `oldId` 为 undefined，旧项目的 blob URL 将永不释放（会话级内存泄漏）。进入预览页时调用
 * 本函数兜底（不清理当前项目，保留「离开再回来仍可重试」的会话语义）。
 */
export function clearOthers(projectId) {
  const p = String(projectId ?? '')
  for (const e of [...store.values()]) if (e.projectId !== p) remove(e.id)
}

/** 当前暂存条目总数（调试/测试用）。 */
export function size() { return store.size }

/** 正文是否含占位 token（与后端发布防呆同一判断口径：只做存在性检查）。 */
export function hasToken(md) {
  return String(md || '').includes(TOKEN_PREFIX)
}

/** 提取正文中全部 token id（去重，保出现顺序）。 */
export function extractTokens(md) {
  const out = []
  const seen = new Set()
  const re = new RegExp(TOKEN_RE.source, 'g')
  let m
  while ((m = re.exec(String(md || ''))) !== null) {
    if (!seen.has(m[1])) { seen.add(m[1]); out.push(m[1]) }
  }
  return out
}

const escapeRe = (s) => String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** 把正文中某 id 的全部 token 替换为给定 URL（flush 成功后调用）。
 *  末位 `(?![A-Za-z0-9_-])` 是 **id 边界**：否则 id 前缀命中会误伤更长 id（如 `i1` 会命中 `i12` 的前缀）；
 *  token 后紧跟 `)`/空白/引号，边界断言不影响正常命中。 */
export function replaceToken(md, id, url) {
  const re = new RegExp(`${TOKEN_PREFIX}${escapeRe(id)}(?![A-Za-z0-9_-])`, 'g')
  return String(md || '').replace(re, String(url || ''))
}

/** 按 resolver(id)->url 批量替换全部 token；resolver 返回空则保留原 token（不误删内容）。 */
export function replaceAllTokens(md, resolver) {
  return String(md || '').replace(new RegExp(TOKEN_RE.source, 'g'), (m, id) => resolver?.(id) || m)
}

/**
 * 渲染产物的**预览投影**替换：只把 `src="sparkora-img:<id>"` 换成 resolver 给出的本地 URL。
 * @wenyan-md/core(marked 15) 对 `![](sparkora-img:<id>)` 原样输出 `<img src="sparkora-img:<id>">`
 * （已实测，未做协议过滤），故在渲染后、sanitize 前按 src 属性精确替换即可，**不污染落库正文**。
 * resolver 返回空（无暂存条目）时保留原 src（显示破图），由发布防呆兜底。
 */
export function mapTokenSrc(html, resolver) {
  return String(html || '').replace(/src="sparkora-img:([A-Za-z0-9_-]+)"/g, (m, id) => {
    const url = resolver?.(id)
    return url ? `src="${url}"` : m
  })
}

export default { add, get, previewUrl, entries, hasAny, remove, clearProject, clearOthers, size }
