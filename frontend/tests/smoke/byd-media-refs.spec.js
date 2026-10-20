import { test, expect } from '@playwright/test'
import { parseBydMediaRefs, summarizeBydMediaReadiness } from '../../src/utils/bydMediaRefs.js'

test.describe('BYD 正文媒体引用解析', () => {
  test('按首次出现顺序去重，同时保留原始引用次数', () => {
    const result = parseBydMediaRefs([
      '![首图](sparkora-byd-media:img_a "标题")',
      '![视频](sparkora-byd-video:video-1)',
      '![重复图](SPARKORA-BYD-MEDIA:img_a)',
      '![二图](sparkora-byd-media:img_b)'
    ].join('\n'))

    expect(result.mediaIds).toEqual(['img_a', 'video-1', 'img_b'])
    expect(result.imageIds).toEqual(['img_a', 'img_b'])
    expect(result.videoIds).toEqual(['video-1'])
    expect(result.imageRefCount).toBe(3)
    expect(result.videoRefCount).toBe(1)
    expect(result.hasMalformedTokens).toBe(false)
    expect(result.allResolved).toBe(true)
  })

  test('解析器判定失效、非法 mediaId、公众号旧 token 与损坏前缀', () => {
    const result = parseBydMediaRefs([
      '![失效](sparkora-byd-media:missing)',
      '![非法](sparkora-byd-media:bad!)',
      '![旧图](sparkora-img:gzh-1)',
      '裸文本 sparkora-byd-video:broken'
    ].join('\n'), {
      isResolved: (id, type) => type === 'image' && id === 'missing' ? false : true
    })

    expect(result.unresolvedImageTokens).toEqual(['missing', 'bad!'])
    expect(result.legacyTokenIds).toEqual(['gzh-1'])
    expect(result.malformedTokenCount).toBe(1)
    expect(result.hasUnresolvedTokens).toBe(true)
    expect(result.allResolved).toBe(false)
  })

  test('视频 token 只按 video 类型交给 resolver', () => {
    const seen = []
    const result = parseBydMediaRefs('![视频](sparkora-byd-video:v1)', {
      isResolved: (id, type) => {
        seen.push(`${type}:${id}`)
        return false
      }
    })

    expect(seen).toEqual(['video:v1'])
    expect(result.unresolvedVideoTokens).toEqual(['v1'])
    expect(result.unresolvedImageTokens).toEqual([])
  })

  test('媒体准备汇总会阻断过期待定和 image/video 类型不匹配', () => {
    const now = Date.now()
    const summary = summarizeBydMediaReadiness([
      { id: 'ready', type: 'image' },
      { id: 'expired', type: 'video' },
      { id: 'wrong-type', type: 'image' },
      { id: 'pending', type: 'video' }
    ], {
      ready: { mediaId: 'ready', mediaType: 'IMAGE', storageState: 'READY', expiresAt: now + 60_000 },
      expired: { mediaId: 'expired', mediaType: 'VIDEO', storageState: 'READY', expiresAt: now - 60_000 },
      'wrong-type': { mediaId: 'wrong-type', mediaType: 'VIDEO', storageState: 'READY', expiresAt: now + 60_000 },
      pending: { mediaId: 'pending', mediaType: 'VIDEO', storageState: 'PENDING', expiresAt: now + 60_000 }
    })

    expect(summary.readyCount).toBe(1)
    expect(summary.pendingCount).toBe(1)
    expect(summary.missingCount).toBe(2)
    expect(summary.expiredCount).toBe(1)
    expect(summary.typeMismatchCount).toBe(1)
    expect(summary.unreadyCount).toBe(3)
    expect(summary.allReady).toBe(false)
  })
})