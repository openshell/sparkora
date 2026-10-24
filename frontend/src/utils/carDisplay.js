/**
 * 车型展示纯函数（10-21-knowledge-car-sync-ux）。
 *
 * 知识中心车型 Tab 与 /car/sync、/car/:id 共用：封面回退、卖点解析、价格格式化、
 * 前端排序与状态文案。无响应式/无副作用，可直接单测。
 */

/** 卡片/详情缩略图：封面优先，其次详情图集首图；都没有返回空串。 */
export function coverUrlOf(row) {
  if (!row) return ''
  if (row.coverImageUrl) return row.coverImageUrl
  const intro = row.introImageUrls
  return Array.isArray(intro) && intro.length ? intro[0] || '' : ''
}

/**
 * 当前展示的是否为「无主图 → 详情图首图」回退。
 *
 * 详情图是超长竖图（实测 1080×12657～1920×38853），不能按 contain 整张塞进卡片，
 * 否则会缩成一条窄缝；回退时由视图改为 cover + object-position: top，裁出长图顶部。
 */
export function coverIsFallback(row) {
  return !!row && !row.coverImageUrl
    && Array.isArray(row.introImageUrls) && !!row.introImageUrls[0]
}

/** 卖点字段兼容 JSON 字符串 / 数组两态；解析失败/空值返回 []。 */
export function parseFeatures(raw) {
  if (Array.isArray(raw)) return raw.filter(v => typeof v === 'string' && v.trim()).map(v => v.trim())
  if (typeof raw === 'string' && raw.trim()) {
    try {
      const v = JSON.parse(raw)
      return Array.isArray(v) ? v.filter(x => typeof x === 'string' && x.trim()).map(x => x.trim()) : []
    } catch {
      return []
    }
  }
  return []
}

/** 取价格串中的第一个数字（如 "96,800 - 126,800" → 96800）；无法解析返回 null。 */
function firstPriceValue(range) {
  if (range == null) return null
  const m = String(range).replace(/,/g, '').match(/\d+(?:\.\d+)?/)
  if (!m) return null
  const v = Number(m[0])
  return Number.isFinite(v) ? v : null
}

function fmtWan(v) {
  return String(Number((v / 10000).toFixed(2)))
}

/**
 * 价格展示："96,800 - 126,800" → "9.68–12.68 万"；单值 → "9.68 万"。
 * 已带「万」的价格串原样返回；无法解析时也原样返回（不编造数字）。
 */
export function formatPrice(range) {
  if (range == null || range === '') return ''
  const raw = String(range).trim()
  if (!raw) return ''
  if (raw.includes('万')) return raw
  const nums = (raw.replace(/,/g, '').match(/\d+(?:\.\d+)?/g) || []).map(Number).filter(Number.isFinite)
  if (!nums.length) return raw
  const low = fmtWan(nums[0])
  if (nums.length < 2 || nums[0] === nums[1]) return `${low} 万`
  return `${low}–${fmtWan(nums[1])} 万`
}

/** lastSyncAt → 毫秒；空/非法返回 null。 */
function timeOf(s) {
  if (!s) return null
  const t = Date.parse(s)
  return Number.isFinite(t) ? t : null
}

/**
 * 前端排序（不改原数组）：
 * - recent：最近同步倒序，无同步时间的排最后；
 * - name：中文名称升序；
 * - price：价格下限升序，价格缺失排最后。
 */
export function sortCars(rows, key) {
  const list = Array.isArray(rows) ? [...rows] : []
  if (key === 'name') {
    return list.sort((a, b) => String(a?.name || '').localeCompare(String(b?.name || ''), 'zh'))
  }
  if (key === 'price') {
    return list.sort((a, b) => {
      const pa = firstPriceValue(a?.priceRange)
      const pb = firstPriceValue(b?.priceRange)
      if (pa == null && pb == null) return 0
      if (pa == null) return 1
      if (pb == null) return -1
      return pa - pb
    })
  }
  // 默认 recent
  return list.sort((a, b) => {
    const ta = timeOf(a?.lastSyncAt)
    const tb = timeOf(b?.lastSyncAt)
    if (ta == null && tb == null) return 0
    if (ta == null) return 1
    if (tb == null) return -1
    return tb - ta
  })
}

/** 同步状态标签（el-tag 的 type + 中文文案）。 */
export function statusMeta(status) {
  const map = {
    PENDING: { label: '待同步', type: 'info' },
    SYNCING: { label: '同步中', type: 'warning' },
    SUCCESS: { label: '已同步', type: 'success' },
    FAILED: { label: '失败', type: 'danger' }
  }
  return map[status] || { label: status || '—', type: 'info' }
}

/** 官网 sourceCreatedAt → YYYY-MM-DD（无值/非法返回空串）。 */
export function formatDate(value) {
  if (!value) return ''
  const s = String(value).replace('T', ' ')
  return /^\d{4}-\d{2}-\d{2}/.test(s) ? s.slice(0, 10) : ''
}
