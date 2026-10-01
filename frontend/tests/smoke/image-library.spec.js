import { test, expect } from '../fixtures/index.js';
import { SMOKE_VIEWPORT } from '../fixtures/matrix.js';

test('image library renders grid', async ({ page }) => {
  await page.setViewportSize(SMOKE_VIEWPORT);
  await page.goto('/images');
  await expect(page.locator('text=example.jpg').first()).toBeVisible();
  await expect(page.locator('text=共 1 张')).toBeVisible();
});

// R4:tag 筛选 chip 与 URL query 双向同步(syncRouteTag / useImageFilters)
test('image library tag filter syncs with url query', async ({ page }) => {
  await page.setViewportSize(SMOKE_VIEWPORT);
  await page.goto(`/images?tag=${encodeURIComponent('示例')}`);
  await expect(page.locator('.chip-row .el-tag')).toContainText('标签: 示例');

  // 关掉 chip → 筛选清空且 URL 残留的 tag 必须同步移除(否则外部再次带 tag 跳入会被 vue-router 判为重复导航)
  await page.locator('.chip-row .el-tag .el-tag__close').click();
  await expect(page.locator('.chip-row')).toHaveCount(0);
  await expect.poll(() => page.url()).not.toContain('tag=');
});

// R4:语义开关(与精确筛选互斥的语义搜索模式入口)
test('image library semantic mode toggle', async ({ page }) => {
  await page.setViewportSize(SMOKE_VIEWPORT);
  await page.goto('/images');
  await page.getByRole('button', { name: '语义搜索' }).first().click();
  await expect(page.locator('input[placeholder^="如：比亚迪"]')).toBeVisible();
  await expect(page.getByRole('button', { name: '语义搜索' }).first()).toHaveClass(/primary/);
});
