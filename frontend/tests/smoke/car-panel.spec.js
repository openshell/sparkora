import { test, expect } from '../fixtures/index.js'
import { SMOKE_VIEWPORT } from '../fixtures/matrix.js'

const ROWS = [
  {
    id: 2, goodsId: '156', name: '大唐EV', salesNetwork: '王朝', priceRange: '239,900 - 309,900',
    syncStatus: 'SUCCESS', sourceState: 'OK', lastSyncAt: '2026-10-21T10:00:00',
    coverImageUrl: 'https://img.example.com/cover.png',
    introImageUrls: ['https://img.example.com/detail2.png'], lastSyncError: null
  },
  {
    id: 1, goodsId: '155', name: '海狮05EV', salesNetwork: '海洋', priceRange: '96,800 - 126,800',
    syncStatus: 'FAILED', sourceState: 'EMPTY', lastSyncAt: '2026-10-20T10:00:00',
    lastCheckedAt: '2026-10-21T09:00:00', coverImageUrl: null,
    introImageUrls: ['https://img.example.com/detail1.png'], lastSyncError: '官网无数据'
  }
]

test('car tab renders cover fallback, stats and failure reason', async ({ page }) => {
  await page.route('**/api/car/models', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'ok', data: ROWS }) }))
  await page.setViewportSize(SMOKE_VIEWPORT)
  await page.goto('/knowledge')
  await page.waitForLoadState('networkidle')

  const stats = page.locator('.stats-bar')
  await expect(stats.locator('.stat').nth(0).locator('.stat-num')).toHaveText('2')
  await expect(stats.locator('.stat').nth(1).locator('.stat-num')).toHaveText('1')
  await expect(stats.locator('.stat').nth(2).locator('.stat-num')).toHaveText('0')
  await expect(stats.locator('.stat').nth(3).locator('.stat-num')).toHaveText('1')
  await expect(stats.locator('.stat').nth(5)).toContainText('1 / 2')

  const cards = page.locator('.car-grid .car-card')
  await expect(cards).toHaveCount(2)
  // 封面优先 / 无封面回退详情图首图
  await expect(cards.nth(0).locator('img').first()).toHaveAttribute('src', 'https://img.example.com/cover.png')
  await expect(cards.nth(1).locator('img').first()).toHaveAttribute('src', 'https://img.example.com/detail1.png')
  // 主图 contain；回退详情图改为顶部裁剪（cover + object-position: top），避免长图缩成窄条
  await expect(cards.nth(0).locator('img').first()).toHaveCSS('object-fit', 'contain')
  await expect(cards.nth(1).locator('.thumb')).toHaveClass(/thumb--fallback/)
  await expect(cards.nth(1).locator('img').first()).toHaveCSS('object-fit', 'cover')
  await expect(cards.nth(1).locator('img').first()).toHaveCSS('object-position', '50% 0%')

  // 官网无数据：独立状态标签 + 全量同步禁用；原因仍可见
  await expect(cards.nth(1).locator('.c-status')).toContainText('官网无数据')
  await expect(cards.nth(1).locator('button', { hasText: '同步' })).toBeDisabled()
  await expect(page.locator('.c-error')).toContainText('官网无数据')

  // 「无封面」筛选 → 只剩 1 台
  await page.locator('.filter').nth(1).click()
  await page.locator('.el-select-dropdown__item:visible', { hasText: '无封面' }).click()
  await expect(cards).toHaveCount(1)
  await expect(page.locator('.result-line')).toContainText('当前显示 1 台')

  // 清除筛选恢复
  await page.locator('.clear-btn').click()
  await expect(cards).toHaveCount(2)
})
