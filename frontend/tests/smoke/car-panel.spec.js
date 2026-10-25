import { test, expect } from '../fixtures/index.js'
import { SMOKE_VIEWPORT } from '../fixtures/matrix.js'

const ROWS = [
  {
    id: 2, goodsId: '156', name: '大唐EV', salesNetwork: '王朝', priceRange: '239,900 - 309,900',
    syncStatus: 'SUCCESS', sourceState: 'OK', lastSyncAt: '2026-10-21T10:00:00',
    sourceCreatedAt: '2026-10-20T17:44:53',
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

  // 统计条 8 项：全部/已同步/失败/数据待复核/部分同步/官网无数据/同步中/封面覆盖
  const stats = page.locator('.stats-bar')
  await expect(stats.locator('.stat').nth(0).locator('.stat-num')).toHaveText('2')
  await expect(stats.locator('.stat').nth(1).locator('.stat-num')).toHaveText('1')
  await expect(stats.locator('.stat').nth(2).locator('.stat-num')).toHaveText('0')
  await expect(stats.locator('.stat').nth(3).locator('.stat-num')).toHaveText('0')
  await expect(stats.locator('.stat').nth(4).locator('.stat-num')).toHaveText('0')
  await expect(stats.locator('.stat').nth(5).locator('.stat-num')).toHaveText('1')
  await expect(stats.locator('.stat').nth(6).locator('.stat-num')).toHaveText('0')
  await expect(stats.locator('.stat').nth(7)).toContainText('1 / 2')

  const cards = page.locator('.car-grid .car-card')
  await expect(cards).toHaveCount(2)
  // 官网 createdAt → 卡片「创建」时间
  await expect(cards.nth(0).locator('.c-meta')).toContainText('创建 2026-10-20')
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


test('car panel status filter is URL-backed and shared with stats/labels', async ({ page }) => {
  const rows = [
    {
      id: 1, goodsId: '900', name: '复核车型', salesNetwork: '王朝', priceRange: '100,000',
      syncStatus: 'FAILED', sourceState: 'INCOMPLETE', lastSyncAt: '2026-10-21T10:00:00',
      coverImageUrl: 'https://img.example.com/a.png', introImageUrls: [], lastSyncError: '结构异常'
    },
    {
      id: 2, goodsId: '901', name: '部分车型', salesNetwork: '海洋', priceRange: '120,000',
      syncStatus: 'PARTIAL', sourceState: 'OK', lastSyncAt: '2026-10-21T09:00:00',
      coverImageUrl: 'https://img.example.com/b.png', introImageUrls: [], lastSyncError: null
    },
    {
      id: 3, goodsId: '902', name: '正常车型', salesNetwork: '海洋', priceRange: '150,000',
      syncStatus: 'SUCCESS', sourceState: 'OK', lastSyncAt: '2026-10-21T08:00:00',
      coverImageUrl: null, introImageUrls: ['https://img.example.com/c.png'], lastSyncError: null
    }
  ]
  await page.route('**/api/car/models', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'ok', data: rows }) }))
  await page.setViewportSize(SMOKE_VIEWPORT)

  // R2：直接打开带 query 的链接即恢复筛选（REVIEW 优先于 FAILED）
  await page.goto('/knowledge?status=REVIEW')
  await page.waitForLoadState('networkidle')
  const cards = page.locator('.car-grid .car-card')
  await expect(cards).toHaveCount(1)
  await expect(cards.nth(0).locator('.c-name')).toHaveText('复核车型')
  await expect(cards.nth(0).locator('.c-status')).toContainText('数据待复核')
  await expect(page).toHaveURL(/status=REVIEW/)

  // R1：统计项可点击切换筛选，且 URL 同步
  await page.locator('.stats-bar .stat', { hasText: '部分同步' }).click()
  await expect(cards).toHaveCount(1)
  await expect(cards.nth(0).locator('.c-name')).toHaveText('部分车型')
  await expect(cards.nth(0).locator('.c-status')).toContainText('部分同步')
  await expect(page).toHaveURL(/status=PARTIAL/)

  // 「全部车型」清除状态筛选，query 中不再残留 status
  await page.locator('.stats-bar .stat', { hasText: '全部车型' }).click()
  await expect(cards).toHaveCount(3)
  await expect(page).not.toHaveURL(/status=/)

  // R3：全选筛选结果 → 批量栏计数联动；再点取消 = 清空全部
  const selectAll = page.locator('.select-all')
  await expect(selectAll).toBeVisible()
  await selectAll.click()
  await expect(page.locator('.bulk-bar')).toContainText('已选 3 个')
  await selectAll.click()
  await expect(page.locator('.bulk-bar')).toHaveCount(0)
})

test('car detail falls back to raw params when cleans are empty', async ({ page }) => {
  const detail = {
    model: {
      id: 14, goodsId: '140', name: '测试车型', salesNetwork: '王朝', priceRange: '115,800 - 145,800',
      syncStatus: 'SUCCESS', sourceState: 'OK', lastSyncAt: '2026-10-21T09:31:57', lastCheckedAt: null,
      coverImageUrl: '', coverImageId: null, introImages: '[]', introImageUrls: [],
      features: '[]', carRights: null
    },
    versions: [{ id: 1, versionName: '2026款' }],
    groups: [{
      group: { id: 1, groupName: '尺寸参数' },
      params: [
        { id: 1, paramName: '轴距(mm)', paramValue: '2820', valuesJson: '["2820"]' },
        { id: 2, paramName: '长×宽×高(mm)', paramValue: '4780×1890×1670', valuesJson: '["4780×1890×1670"]' }
      ],
      cleans: []
    }]
  }
  await page.route('**/api/car/models/14*', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'ok', data: detail }) }))
  await page.setViewportSize(SMOKE_VIEWPORT)
  await page.goto('/car/14')
  await page.waitForLoadState('networkidle')

  await expect(page.locator('.el-alert').first()).toContainText('尚未清洗')
  await expect(page.locator('.el-table__row')).toHaveCount(2)
  await expect(page.locator('.el-table')).toContainText('轴距(mm)')
  await expect(page.locator('.el-table')).toContainText('2820')
  await expect(page.locator('.el-table')).toContainText('原始')
  await expect(page.locator('button', { hasText: '重新清洗' })).toBeVisible()
})


test('car sync history drawer lists recent jobs', async ({ page }) => {
  const job = {
    id: 9, jobType: 'SELECTED', status: 'SUCCESS', success: 2, failed: 0, skipped: 0,
    createdAt: '2026-10-24T13:00:00', createdBy: 'admin', failedItems: '[]'
  }
  await page.route('**/api/car/sync/jobs', (route) => {
    if (route.request().method() === 'GET') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'ok', data: [job] }) })
    }
    return route.fallback()
  })
  await page.goto('/knowledge')
  await page.waitForLoadState('networkidle')
  await page.getByRole('button', { name: '同步记录' }).click()
  await expect(page.locator('.history-table')).toContainText('选中同步')
  await expect(page.locator('.history-table')).toContainText('成功')
})

test('car job panel shows current model, stalled heartbeat and type-specific title', async ({ page }) => {
  const staleAt = new Date(Date.now() - 30 * 60 * 1000).toISOString()
  const running = {
    id: 9, jobType: 'VECTOR_REBUILD', status: 'RUNNING', total: 3, success: 0, failed: 0, skipped: 0,
    heartbeatAt: staleAt, startedAt: staleAt,
    currentGoodsId: '156', currentModelName: '大唐EV', failedItems: null
  }
  await page.route('**/api/car/models', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'ok', data: ROWS }) }))
  await page.route('**/api/car/sync/jobs', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'ok', data: [running] }) }))
  await page.route('**/api/car/sync/jobs/9', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'ok', data: running }) }))
  await page.setViewportSize(SMOKE_VIEWPORT)
  await page.goto('/knowledge')
  await page.waitForLoadState('networkidle')

  const panel = page.locator('.job-panel')
  await expect(panel.locator('.job-title')).toContainText('重建向量中…')
  await expect(panel.locator('.job-current')).toContainText('正在处理：大唐EV')
  await expect(panel.locator('.job-stalled')).toContainText('心跳超过 10 分钟未更新')
})

test('car quality dialog shows vector reconciliation and missing topN', async ({ page }) => {
  const stats = {
    modelCount: 3, chunkCount: 10, embeddedCount: 8, missingCount: 2,
    missingTopN: [{ modelId: 14, modelName: '测试车型', missing: 2 }]
  }
  await page.route('**/api/car/models', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'ok', data: ROWS }) }))
  await page.route('**/api/car/models/vector-stats', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'ok', data: stats }) }))
  await page.setViewportSize(SMOKE_VIEWPORT)
  await page.goto('/knowledge')
  await page.waitForLoadState('networkidle')

  await page.getByRole('button', { name: '质量' }).click()
  const dialog = page.locator('.el-dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('.q-stat').nth(0).locator('.q-num')).toHaveText('3')
  await expect(dialog.locator('.q-stat').nth(1).locator('.q-num')).toHaveText('10')
  await expect(dialog.locator('.q-stat').nth(2).locator('.q-num')).toHaveText('8')
  await expect(dialog.locator('.q-stat').nth(3).locator('.q-num')).toHaveText('2')
  await expect(dialog.locator('.q-table')).toContainText('测试车型')
  await expect(dialog.locator('.q-table')).toContainText('2')

  // 缺失 TopN 行点击跳详情并关闭弹窗
  await dialog.locator('.el-table__row').first().click()
  await expect(page).toHaveURL(/\/car\/14$/)
})
