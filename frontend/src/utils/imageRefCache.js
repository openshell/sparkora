/**
 * 参考图会话缓存（09-26 img2img-ref-upload）。
 *
 * 用途：粘贴 / 本地文件作为图生图参考图时，参考图**不落图库**（后端只做内存校验后字节直传 AI），
 * 结果的 `ref_image_id` 为 NULL —— 后端 `/regenerate` 无法复用参考图。故前端在生成成功时把
 * 「结果图 id → 该次参考图信息」写进本模块级单例 Map，供同会话内「重生成」复用。
 *
 * 约定：
 * - 模块级单例（图库页 / 预览页两个入口共享同一份），**不做持久化**（刷新即失效，
 *   用户已接受；Blob 体积大不适合 localStorage/IndexedDB）。
 * - LRU 上限（默认 20）：超限淘汰最久未使用的条目。
 * - **ObjectURL 所有权**：缓存为每个不同的 `File` 创建**一个**自有 ObjectURL（同一 File 被 n 张结果
 *   共享时复用同一个 URL，以 `file` 引用去重）；淘汰 / 删除 / 清空时若无其他条目再引用该 File 则 revoke。
 *   这样满足「淘汰时释放」契约且不会误伤别处（如组件本地预览）正在使用的 URL。
 * - 键统一 `String(imageId)` 归一，避免 `1` 与 `'1'` 查不到。
 */

const DEFAULT_MAX_ENTRIES = 20

/** @type {Map<string, object>} key=String(imageId) -> info，Map 保序即为 LRU 顺序（队尾=最近使用） */
const store = new Map()

/** 释放 ObjectURL（仅 blob:；幂等，已释放的 URL 再 revoke 无副作用）。 */
const revoke = (url) => {
  if (url && typeof url === 'string' && url.startsWith('blob:')) {
    try { URL.revokeObjectURL(url) } catch (e) { /* 已释放/环境不支持：忽略 */ }
  }
}

/** 是否仍有其他条目引用同一 File（决定删除某条时能否安全 revoke 其 ObjectURL）。 */
const otherEntryUsesFile = (file, exceptKey) => {
  if (!file) return false
  for (const [k, v] of store) if (k !== exceptKey && v.file === file) return true
  return false
}

/** 为 File 取自有 ObjectURL：已有条目持有同一 File 的 URL 则复用，否则新建。 */
const urlForFile = (file) => {
  if (!file) return null
  for (const v of store.values()) if (v.file === file && v.previewUrl) return v.previewUrl
  try { return URL.createObjectURL(file) } catch (e) { return null }
}

/**
 * 写入/刷新一条缓存（同 key 覆盖并提升为最近使用）。
 * @param {number|string} imageId 生成结果图的 id
 * @param {{file?: Blob|File, fileName?: string, prompt?: string, size?: string, tags?: string[],
 *          projectId?: number|string|null}} info 参考图信息（重生成所需）
 */
export function put(imageId, info) {
  if (imageId == null || !info) return
  const key = String(imageId)
  const prev = store.get(key)
  let previewUrl
  if (prev && prev.file === info.file && prev.previewUrl) {
    // 同一条目同 File 覆盖（如重生成链回写）：沿用旧自有 URL，不新建、不 revoke（避免泄漏）
    previewUrl = prev.previewUrl
  } else {
    previewUrl = urlForFile(info.file)
    // 覆盖成不同 File：旧 URL 若无其他条目共享则释放
    if (prev && !otherEntryUsesFile(prev.file, key)) revoke(prev.previewUrl)
  }
  store.delete(key)
  store.set(key, { ...info, previewUrl })
  evictIfNeeded()
}

/**
 * 读取一条缓存（命中即视为「最近使用」，移到队尾）。未命中返回 undefined。
 * 返回值为浅拷贝：调用方不可通过修改返回值影响缓存内部状态。
 */
export function get(imageId) {
  if (imageId == null) return undefined
  const key = String(imageId)
  const info = store.get(key)
  if (!info) return undefined
  store.delete(key)
  store.set(key, info)   // 提升为最近使用
  return { ...info }
}

/**
 * 是否存在缓存（**不**提升 LRU 顺序）。
 * 供模板 `:disabled` / 按钮态判断使用——若用 get() 会在每次渲染时扰动 LRU 顺序。
 */
export function has(imageId) {
  if (imageId == null) return false
  return store.has(String(imageId))
}

/** 删除一条缓存；仅当无其他条目共享该 File 时释放其自有 ObjectURL。 */
export function deleteEntry(imageId) {
  if (imageId == null) return
  const key = String(imageId)
  const info = store.get(key)
  if (!info) return
  store.delete(key)
  if (!otherEntryUsesFile(info.file, key)) revoke(info.previewUrl)
}

/** 超限淘汰最久未使用（Map 头部）的条目。 */
function evictIfNeeded() {
  while (store.size > DEFAULT_MAX_ENTRIES) {
    const oldestKey = store.keys().next().value
    const info = store.get(oldestKey)
    store.delete(oldestKey)
    if (!otherEntryUsesFile(info?.file, oldestKey)) revoke(info?.previewUrl)
  }
}

/** 清空全部缓存并释放所有自有 ObjectURL（登出/切模块等场景备用）。 */
export function clear() {
  for (const info of store.values()) revoke(info?.previewUrl)
  store.clear()
}

/** 当前条目数（调试/测试用）。 */
export function size() { return store.size }

export { DEFAULT_MAX_ENTRIES }

export default { put, get, has, delete: deleteEntry, clear, size }
