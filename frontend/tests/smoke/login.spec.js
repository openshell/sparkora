import { test, expect } from '../fixtures/index.js';
import { SMOKE_VIEWPORT } from '../fixtures/matrix.js';

test('login form posts and redirects', async ({ page }) => {
  await page.setViewportSize(SMOKE_VIEWPORT);
  // 登录用例要「未登录」冷启动:清掉 fixture 预置的登录态
  await page.addInitScript(() => localStorage.clear());
  await page.goto('/login');
  await page.fill('input[placeholder="输入用户名"]', 'test');
  await page.fill('input[type="password"]', '123456');
  await page.click('button:has-text("登 录")');
  await page.waitForURL(/\/$/);
  await expect.poll(() => page.evaluate(() => localStorage.getItem('sparkora_token'))).toBe('mock-token');
});
