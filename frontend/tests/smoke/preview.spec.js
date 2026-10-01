import { test, expect } from '../fixtures/index.js';
import { SMOKE_VIEWPORT } from '../fixtures/matrix.js';

test('preview has wenyan-preview contract', async ({ page }) => {
  await page.setViewportSize(SMOKE_VIEWPORT);
  await page.goto('/projects/1/preview');
  await expect(page.locator('.wenyan-preview')).toBeVisible();
  // 契约:预览 HTML 由本地渲染注入 v-html 容器(非 iframe),正文来自 mock 版本 contentMd
  await expect(page.locator('.wenyan-preview')).toContainText('测试标题');
  await expect(page.locator('text=正文加载失败')).toHaveCount(0);
});

// R4/AC3:分栏拖拽后左右 pane 宽度变化(StepPreview 双 pane)
test('preview split pane drag changes pane width', async ({ page }) => {
  await page.setViewportSize(SMOKE_VIEWPORT);
  await page.goto('/projects/1/preview');
  await expect(page.locator('.wenyan-preview')).toBeVisible();

  const leftPane = page.locator('.duo .pane-left');
  const before = (await leftPane.boundingBox()).width;

  const sp = await page.locator('.duo .splitter').boundingBox();
  await page.mouse.move(sp.x + sp.width / 2, sp.y + sp.height / 2);
  await page.mouse.down();
  await page.mouse.move(sp.x + sp.width / 2 + 90, sp.y + sp.height / 2, { steps: 8 });
  await page.mouse.up();

  await expect
    .poll(async () => (await leftPane.boundingBox()).width, { timeout: 3000 })
    .toBeGreaterThan(before + 40);
  // 拖拽后分栏比例记忆到 localStorage(StepPreview SPLIT_KEY)
  expect(await page.evaluate(() => localStorage.getItem('sparkora.previewSplit'))).toBeTruthy();
});
