import { bydMediaApi } from '../api'
import {
  bydMediaTypeMatches,
  isBydMediaExpired,
  isBydMediaReady,
  mediaStorageState,
  summarizeBydMediaReadiness
} from './bydMediaRefs'

/** BYD 暂存与小程序发布端的共同前端上限。 */
export const BYD_MAX_IMAGE_BYTES = 20 * 1024 * 1024
export const BYD_MAX_VIDEO_BYTES = 300 * 1024 * 1024
export const BYD_MIN_VIDEO_SECONDS = 2
export const BYD_MAX_VIDEO_SECONDS = 120
export const BYD_MAX_VIDEOS = 3

const IMAGE_MIME_BY_EXT = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  webp: 'image/webp'
}

const extOfName = (name) => {
  const m = /\.([A-Za-z0-9]+)$/.exec(String(name || '').trim())
  return m ? m[1].toLowerCase() : ''
}

const baseNameOf = (name) => String(name || '').split(/[\\/]/).pop() || ''

const imageMimeOf = (file) => {
  const type = String(file?.type || '').toLowerCase()
  if (/^image\/(png|jpe?g|pjpeg|webp)$/.test(type)) {
    if (type === 'image/pjpeg') return 'image/jpeg'
    if (type === 'image/jpg') return 'image/jpeg'
    return type
  }
  return IMAGE_MIME_BY_EXT[extOfName(file?.name)] || ''
}

const imageExtOfMime = (mime) => {
  if (mime === 'image/png') return 'png'
  if (mime === 'image/webp') return 'webp'
  return 'jpg'
}

const normalizeImageFile = (file) => {
  const declaredType = String(file?.type || '').toLowerCase()
  const genericType = !declaredType || declaredType === 'application/octet-stream'
  const mimeType = imageMimeOf(file)
  if (!mimeType || (!genericType && !/^image\/(png|jpe?g|pjpeg|webp)$/.test(declaredType))) {
    throw new Error('仅支持 JPEG、PNG、WebP 格式图片')
  }
  const rawName = baseNameOf(file?.name)
  const ext = extOfName(rawName)
  const extMatchesMime = mimeType === 'image/jpeg'
    ? ext === 'jpg' || ext === 'jpeg'
    : ext === imageExtOfMime(mimeType)
  if (extMatchesMime && String(file.type || '').toLowerCase() === mimeType) return file
  const stem = rawName.replace(/\.[^.]*$/, '') || 'paste'
  return new File([file], `${stem}.${imageExtOfMime(mimeType)}`, {
    type: mimeType,
    lastModified: file?.lastModified || Date.now()
  })
}

const normalizeVideoFile = (file) => {
  const rawName = baseNameOf(file?.name)
  const type = String(file?.type || '').toLowerCase()
  const genericType = !type || type === 'application/octet-stream'
  const isMp4Name = /\.mp4$/i.test(rawName)
  const isMp4Type = type === 'video/mp4'
  if ((!genericType && !isMp4Type) || (!isMp4Type && !isMp4Name)) {
    throw new Error('仅支持 MP4 视频')
  }
  if (isMp4Name && isMp4Type) return file
  return new File([file], isMp4Name ? rawName : `${rawName.replace(/\.[^.]*$/, '') || 'clip'}.mp4`, {
    type: 'video/mp4',
    lastModified: file?.lastModified || Date.now()
  })
}

/** 从浏览器预留/上传响应中取出媒体 DTO（兼容 data 与 data.media 两种薄包装）。 */
export function mediaFromResponse(response, fallbackMessage = '媒体接口返回异常') {
  if (!response || (response.code != null && response.code !== 0)) {
    throw new Error(response?.msg || fallbackMessage)
  }
  const media = response.data?.media || response.data
  if (!media || !media.mediaId) throw new Error('媒体接口返回缺少 mediaId')
  return media
}

export {
  bydMediaTypeMatches,
  isBydMediaExpired,
  isBydMediaReady,
  mediaStorageState,
  summarizeBydMediaReadiness
}

const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms))

/** 上传完成后轮询一次媒体元数据，避免把仍为 PENDING 的 token 写进正文。 */
export async function waitForBydMediaReady(projectId, mediaId, { attempts = 6, intervalMs = 350 } = {}) {
  let last = null
  for (let i = 0; i < attempts; i++) {
    const res = await bydMediaApi.get(projectId, mediaId)
    last = mediaFromResponse(res, '查询媒体准备状态失败')
    const state = mediaStorageState(last)
    if (state === 'READY') return last
    if (state === 'FAILED') throw new Error('媒体暂存失败，请重新上传')
    if (i < attempts - 1) await delay(intervalMs)
  }
  throw new Error(`媒体仍在准备中（${mediaStorageState(last) || '未知状态'}），请稍后重试`)
}

export function readImageDimensions(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const img = new Image()
    const cleanup = () => URL.revokeObjectURL(url)
    img.onload = () => {
      const width = Number(img.naturalWidth || img.width || 0)
      const height = Number(img.naturalHeight || img.height || 0)
      cleanup()
      if (!width || !height) reject(new Error('无法读取图片尺寸'))
      else resolve({ width, height })
    }
    img.onerror = () => {
      cleanup()
      reject(new Error('无法读取图片元数据，请更换图片'))
    }
    img.src = url
  })
}

export function readVideoMetadata(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const video = document.createElement('video')
    let settled = false
    const cleanup = () => {
      clearTimeout(timer)
      video.removeAttribute('src')
      video.load?.()
      URL.revokeObjectURL(url)
    }
    const fail = (message) => {
      if (settled) return
      settled = true
      cleanup()
      reject(new Error(message))
    }
    const timer = setTimeout(() => fail('读取视频信息超时，请重试或更换视频'), 15000)
    video.preload = 'metadata'
    video.muted = true
    video.playsInline = true
    video.onloadedmetadata = () => {
      if (settled) return
      const duration = Number(video.duration)
      const width = Number(video.videoWidth || 0)
      const height = Number(video.videoHeight || 0)
      if (!Number.isFinite(duration) || duration <= 0) {
        fail('无法读取视频时长，请确认文件为有效 MP4')
        return
      }
      if (!width || !height) {
        fail('无法读取视频宽高，请确认文件为有效 MP4')
        return
      }
      settled = true
      cleanup()
      resolve({ duration, width, height })
    }
    video.onerror = () => fail('无法读取视频信息，请确认文件为有效 MP4')
    video.src = url
  })
}

export function validateBydImageFile(file) {
  if (!file) throw new Error('请选择图片')
  const normalized = normalizeImageFile(file)
  if (normalized.size > BYD_MAX_IMAGE_BYTES) throw new Error('图片超过 20MB 上限')
  return { file: normalized, mimeType: imageMimeOf(normalized) }
}

export async function validateBydVideoFile(file, { currentVideoCount = 0, maxVideos = BYD_MAX_VIDEOS } = {}) {
  if (!file) throw new Error('请选择视频')
  if (Number(currentVideoCount) >= Number(maxVideos)) {
    throw new Error(`每篇文章最多 ${maxVideos} 个视频`)
  }
  const normalized = normalizeVideoFile(file)
  if (normalized.size > BYD_MAX_VIDEO_BYTES) throw new Error('视频超过 300MB 上限')
  const metadata = await readVideoMetadata(normalized)
  if (metadata.duration < BYD_MIN_VIDEO_SECONDS || metadata.duration > BYD_MAX_VIDEO_SECONDS) {
    throw new Error('视频时长必须在 2–120 秒之间')
  }
  return { file: normalized, mimeType: 'video/mp4', ...metadata }
}

async function reserveAndUpload(projectId, file, payload, { onProgress, signal } = {}) {
  const res = await bydMediaApi.reserve(projectId, payload)
  const reserved = mediaFromResponse(res, '创建 BYD 媒体暂存记录失败')
  const mediaId = reserved.mediaId
  try {
    const uploadPath = reserved.uploadPath
    if (!uploadPath) throw new Error('媒体接口返回缺少 uploadPath')
    const uploadedRes = await bydMediaApi.uploadRaw(projectId, uploadPath, file, { onProgress, signal })
    const uploaded = mediaFromResponse(uploadedRes, '上传 BYD 暂存媒体失败')
    if (isBydMediaReady(uploaded)) return uploaded
    return await waitForBydMediaReady(projectId, mediaId)
  } catch (error) {
    // 预留成功但上传失败时尽力清掉 PENDING 记录；失败不覆盖原始中文错误。
    void removeBydMediaQuietly(projectId, mediaId)
    throw error
  }
}

export async function uploadBydImage(projectId, file, options = {}) {
  const { file: normalized, mimeType } = validateBydImageFile(file)
  const { width, height } = await readImageDimensions(normalized)
  return reserveAndUpload(projectId, normalized, {
    mediaType: 'IMAGE',
    fileName: normalized.name,
    mimeType,
    byteSize: normalized.size,
    width,
    height
  }, options)
}

export async function uploadBydVideo(projectId, file, options = {}) {
  const { file: normalized, mimeType, duration, width, height } = await validateBydVideoFile(file, options)
  return reserveAndUpload(projectId, normalized, {
    mediaType: 'VIDEO',
    fileName: normalized.name,
    mimeType,
    byteSize: normalized.size,
    width,
    height,
    durationSeconds: Number(duration.toFixed(3))
  }, options)
}

/** 把已有图库图片注册为 BYD URL 源媒体；返回 READY 媒体记录。 */
export async function registerBydImageFromLibrary(projectId, image) {
  const imageId = image?.imageId ?? image?.id
  if (imageId == null || imageId === '') throw new Error('图库图片缺少 id，无法注册 BYD 媒体')
  const res = await bydMediaApi.fromImage(projectId, imageId)
  const media = mediaFromResponse(res, '注册图库图片为 BYD 媒体失败')
  if (isBydMediaReady(media)) return media
  try {
    return await waitForBydMediaReady(projectId, media.mediaId)
  } catch (error) {
    await removeBydMediaQuietly(projectId, media.mediaId)
    throw error
  }
}

export async function removeBydMediaQuietly(projectId, mediaId) {
  if (!mediaId) return
  try { await bydMediaApi.remove(projectId, mediaId) } catch (e) { /* best effort：过期清理兜底 */ }
}

export default {
  BYD_MAX_IMAGE_BYTES,
  BYD_MAX_VIDEO_BYTES,
  BYD_MIN_VIDEO_SECONDS,
  BYD_MAX_VIDEO_SECONDS,
  BYD_MAX_VIDEOS,
  mediaFromResponse,
  mediaStorageState,
  isBydMediaReady,
  isBydMediaExpired,
  bydMediaTypeMatches,
  summarizeBydMediaReadiness,
  waitForBydMediaReady,
  readImageDimensions,
  readVideoMetadata,
  validateBydImageFile,
  validateBydVideoFile,
  uploadBydImage,
  uploadBydVideo,
  registerBydImageFromLibrary,
  removeBydMediaQuietly
}
