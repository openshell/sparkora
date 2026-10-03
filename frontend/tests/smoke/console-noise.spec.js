import { test, expect } from '../fixtures/index.js';
import { SMOKE_VIEWPORT } from '../fixtures/matrix.js';

const PAGES = ['/', '/images', '/knowledge', '/projects/1/preview', '/projects/1/publish', '/login'];

// AC5:关键页面无未捕获异常 / console.error 噪声(失败不被噪声掩盖,错误不被噪声带过)
for (const path of PAGES) {
  test(`no page errors on ${path}`, async ({ page }) => {
    await page.setViewportSize(SMOKE_VIEWPORT);
    const errors = [];
    page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
    page.on('console', (m) => {
      if (m.type() === 'error') errors.push('console.error: ' + m.text());
    });
    await page.goto(path);
    await page.waitForLoadState('networkidle');
    expect(errors).toEqual([]);
  });
}
