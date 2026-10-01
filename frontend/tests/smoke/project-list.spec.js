import { test, expect } from '../fixtures/index.js';
import { SMOKE_VIEWPORT } from '../fixtures/matrix.js';

test('project list renders and has create entry', async ({ page }) => {
  await page.setViewportSize(SMOKE_VIEWPORT);
  await page.goto('/');
  await page.waitForLoadState('networkidle');
  await expect(page.locator('text=新能源汽车内容策划')).toBeVisible();
  await expect(page.locator('text=科技前沿解读')).toBeVisible();
  await expect(page.locator('text=新建创作任务')).toBeVisible();
});

// R4:搜索触发 /api/projects 请求(服务端筛选),而不是本地过滤
test('project list search triggers /api/projects request', async ({ page }) => {
  await page.setViewportSize(SMOKE_VIEWPORT);
  const projectRequests = [];
  page.on('request', (req) => {
    const u = new URL(req.url());
    if (u.pathname === '/api/projects') projectRequests.push(req.url());
  });
  await page.goto('/');
  await expect(page.locator('.el-table__row')).toHaveCount(2);

  await page.locator('input[placeholder="搜索主题关键字"]').fill('新能源');
  await page.locator('input[placeholder="搜索主题关键字"]').press('Enter');

  await expect
    .poll(() => projectRequests.filter((u) => u.includes(`topic=${encodeURIComponent('新能源')}`)).length, { timeout: 5000 })
    .toBeGreaterThan(0);
});

// R4:上下文条主题切换给 <html> 挂/去 dark class(store/theme.js 契约)
test('project list theme toggle flips html.dark', async ({ page }) => {
  await page.setViewportSize(SMOKE_VIEWPORT);
  await page.goto('/');
  await expect(page.locator('text=新能源汽车内容策划')).toBeVisible();
  const isDark = () => page.evaluate(() => document.documentElement.classList.contains('dark'));
  expect(await isDark()).toBe(false);
  await page.locator('button.theme-toggle').click();
  await expect.poll(isDark).toBe(true);
  await page.locator('button.theme-toggle').click();
  await expect.poll(isDark).toBe(false);
});
