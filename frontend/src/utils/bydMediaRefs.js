/**
 * BYD 小程序正文媒体 token 的唯一解析口径。
 *
 * 正文里只允许两种新 token：
 * - 图片：![图片](sparkora-byd-media:<mediaId>)
 * - 视频：![视频](sparkora-byd-video:<mediaId>)
 *
 * 这里同时保留公众号 `sparkora-img:` 的检测，但只用于推送前阻断；它不是 BYD 媒体，
 * 绝不能交给 BYD 暂存或 Agent 上传。
 */
export const BYD_IMAGE_PREFIX = 'sparkora-byd-media:'
export const BYD_VIDEO_PREFIX = 'sparkora-byd-video:'
export const GZH_PENDING_PREFIX = 'sparkora-img:'
export const BYD_MAX_VIDEOS = 3
export const BYD_MEDIA_ID_RE = /^[A-Za-z0-9_-]{1,64}$/

// 复用正文图片语法，兼容 alt 内一层方括号与可选 title；媒体 ID 不允许空白/右括号。
const IMG_RE = /!\[[^[\]]*(?:\[[^[\]]*\])?[^[\]]*\]\(\s*([^)\s]+)(?:\s+["'][^"']*["'])?\s*\)/g
const PREFIX_RE = /sparkora-(?:byd-media|byd-video|img):/gi

const addUnique = (list, seen, value) => {
  if (!value || seen.has(value)) return
  seen.add(value)
  list.push(value)
}

/** 与服务端 renderer 保持同一 mediaId 口径。 */
export function isValidBydMediaId(id) {
  return BYD_MEDIA_ID_RE.test(String(id || ''))
}

export function mediaStorageState(media) {
  const explicit = String(media?.storageState || media?.state || media?.status || '').toUpperCase()
  if (explicit) return explicit
  return media?.ready === true ? 'READY' : ''
}

export function isBydMediaReady(media) {
  return mediaStorageState(media) === 'READY'
}

/** 暂存元数据是否已过期；无效/缺失时间由服务端最终校验，前端不伪造过期结论。 */
export function isBydMediaExpired(media, now = Date.now()) {
  const value = media?.expiresAt
  if (value == null || value === '') return false
  const timestamp = typeof value === 'number' ? value : Date.parse(String(value))
  return Number.isFinite(timestamp) && timestamp <= Number(now)
}

/** 校验媒体类型与 token 类型一致，防止 image token 指向 video 记录或反之。 */
export function bydMediaTypeMatches(media, expectedType) {
  const expected = String(expectedType || '').toUpperCase()
  if (!expected) return true
  const actual = String(media?.mediaType || media?.type || '').toUpperCase()
  return !!actual && actual === expected
}

/**
 * 统一计算正文媒体准备状态。只有类型匹配、未过期且状态严格为 READY 才算可推送；
 * PENDING 计入待准备，其余缺失/类型不符/过期/失败/未知状态一律计入失效。
 *
 * @param {Array<string | { id: string, type?: 'image' | 'video' }>} mediaRefs
 *   正文引用；传 refs 时可同时校验 image/video token 与媒体类型。
 * @param {Record<string, any>} statusById 媒体元数据映射
 */
export function summarizeBydMediaReadiness(mediaRefs, statusById = {}) {
  const ids = []
  const seen = new Set()
  const expectedTypeById = new Map()
  for (const entry of mediaRefs || []) {
    const id = String(typeof entry === 'string' ? entry : entry?.id || '')
    if (!id) continue
    if (!seen.has(id)) {
      seen.add(id)
      ids.push(id)
    }
    const expected = String(typeof entry === 'object' ? entry?.type || '' : '').toUpperCase()
    if (!expected) continue
    const previous = expectedTypeById.get(id)
    expectedTypeById.set(id, previous && previous !== expected ? 'CONFLICT' : expected)
  }

  let readyCount = 0
  let pendingCount = 0
  let missingCount = 0
  let expiredCount = 0
  let typeMismatchCount = 0
  for (const id of ids) {
    const media = statusById?.[id]
    const state = mediaStorageState(media)
    const expected = expectedTypeById.get(id)
    const typeMatches = expected !== 'CONFLICT' && bydMediaTypeMatches(media, expected)
    const expired = isBydMediaExpired(media)
    if (state === 'READY' && typeMatches && !expired) {
      readyCount++
    } else if (state === 'PENDING' && typeMatches && !expired) {
      pendingCount++
    } else {
      missingCount++
      if (expired) expiredCount++
      if (!typeMatches) typeMismatchCount++
    }
  }
  const unreadyCount = ids.length - readyCount
  return {
    ids,
    totalCount: ids.length,
    readyCount,
    pendingCount,
    missingCount,
    expiredCount,
    typeMismatchCount,
    unreadyCount,
    allReady: unreadyCount === 0
  }
}

/**
 * 解析正文中的 BYD 图片/视频引用。
 *
 * @param {string} markdown
 * @param {{ isResolved?: (id: string, type: 'image' | 'video') => boolean }} [options]
 *   isResolved 可选；返回 false 的媒体会进入 unresolvedImageTokens / unresolvedVideoTokens。
 *   未传时只解析 token，不臆测远端记录是否存在。
 * @returns {{
 *   refs: Array<{ type: 'image' | 'video', id: string, target: string }>,
 *   mediaIds: string[],
 *   imageIds: string[],
 *   videoIds: string[],
 *   unresolvedImageTokens: string[],
 *   unresolvedVideoTokens: string[],
 *   legacyTokenIds: string[],
 *   malformedTokenCount: number,
 *   imageRefCount: number,
 *   videoRefCount: number,
 *   imageCount: number,
 *   videoCount: number,
 *   totalCount: number,
 *   hasAny: boolean,
 *   hasLegacyGzhTokens: boolean,
 *   hasMalformedTokens: boolean,
 *   hasUnresolvedTokens: boolean,
 *   allResolved: boolean
 * }}
 */
export function parseBydMediaRefs(markdown, options = {}) {
  const text = String(markdown || '')
  const resolver = typeof options.isResolved === 'function' ? options.isResolved : null
  const refs = []
  const mediaIds = []
  const imageIds = []
  const videoIds = []
  const unresolvedImageTokens = []
  const unresolvedVideoTokens = []
  const legacyTokenIds = []
  const recognizedPrefixPositions = new Set()
  const seenMedia = new Set()
  const seenImages = new Set()
  const seenVideos = new Set()
  const seenUnresolvedImages = new Set()
  const seenUnresolvedVideos = new Set()
  const seenLegacy = new Set()
  const re = new RegExp(IMG_RE.source, 'g')
  let match

  while ((match = re.exec(text)) !== null) {
    const target = match[1]
    if (!target) continue
    const lowered = target.toLowerCase()
    const targetMarker = match[0].indexOf('](')
    const targetStart = match.index + match[0].indexOf(target, targetMarker >= 0 ? targetMarker + 2 : 0)
    let type = null
    let id = ''
    if (lowered.startsWith(BYD_IMAGE_PREFIX)) {
      type = 'image'
      id = target.slice(BYD_IMAGE_PREFIX.length)
    } else if (lowered.startsWith(BYD_VIDEO_PREFIX)) {
      type = 'video'
      id = target.slice(BYD_VIDEO_PREFIX.length)
    } else if (lowered.startsWith(GZH_PENDING_PREFIX)) {
      recognizedPrefixPositions.add(targetStart)
      addUnique(legacyTokenIds, seenLegacy, target.slice(GZH_PENDING_PREFIX.length))
      continue
    }
    if (!type || !id) continue

    recognizedPrefixPositions.add(targetStart)
    refs.push({ type, id, target })
    addUnique(mediaIds, seenMedia, id)
    const validId = isValidBydMediaId(id)
    if (type === 'image') {
      addUnique(imageIds, seenImages, id)
      if (!validId || (resolver && !resolver(id, type))) {
        addUnique(unresolvedImageTokens, seenUnresolvedImages, id)
      }
    } else {
      addUnique(videoIds, seenVideos, id)
      if (!validId || (resolver && !resolver(id, type))) {
        addUnique(unresolvedVideoTokens, seenUnresolvedVideos, id)
      }
    }
  }

  // 裸文本、损坏的 Markdown 或写在 alt/title 里的前缀都不能悄悄进入草稿。
  let malformedTokenCount = 0
  const prefixRe = new RegExp(PREFIX_RE.source, 'gi')
  while ((match = prefixRe.exec(text)) !== null) {
    if (!recognizedPrefixPositions.has(match.index)) malformedTokenCount++
  }

  const imageRefCount = refs.reduce((count, ref) => count + (ref.type === 'image' ? 1 : 0), 0)
  const videoRefCount = refs.reduce((count, ref) => count + (ref.type === 'video' ? 1 : 0), 0)
  const hasMalformedTokens = malformedTokenCount > 0
  const hasUnresolvedTokens = unresolvedImageTokens.length > 0 || unresolvedVideoTokens.length > 0 || hasMalformedTokens

  return {
    refs,
    mediaIds,
    imageIds,
    videoIds,
    unresolvedImageTokens,
    unresolvedVideoTokens,
    legacyTokenIds,
    malformedTokenCount,
    imageRefCount,
    videoRefCount,
    imageCount: imageIds.length,
    videoCount: videoIds.length,
    totalCount: imageIds.length + videoIds.length,
    hasAny: refs.length > 0,
    hasLegacyGzhTokens: legacyTokenIds.length > 0,
    hasMalformedTokens,
    hasUnresolvedTokens,
    allResolved: !hasUnresolvedTokens
  }
}

export function hasBydMediaRefs(markdown) {
  return parseBydMediaRefs(markdown).hasAny
}

export function imageMediaMarkdown(mediaId) {
  return `\n![图片](${BYD_IMAGE_PREFIX}${String(mediaId || '').trim()})\n`
}

export function videoMediaMarkdown(mediaId) {
  return `\n![视频](${BYD_VIDEO_PREFIX}${String(mediaId || '').trim()})\n`
}

export default {
  BYD_IMAGE_PREFIX,
  BYD_VIDEO_PREFIX,
  GZH_PENDING_PREFIX,
  BYD_MAX_VIDEOS,
  BYD_MEDIA_ID_RE,
  isValidBydMediaId,
  mediaStorageState,
  isBydMediaReady,
  isBydMediaExpired,
  bydMediaTypeMatches,
  summarizeBydMediaReadiness,
  parseBydMediaRefs,
  hasBydMediaRefs,
  imageMediaMarkdown,
  videoMediaMarkdown
}