import { test, expect } from '../fixtures/index.js';
import { MATRIX } from '../fixtures/matrix.js';
import { applyTheme, prepareStable } from '../fixtures/stable.js';

test.describe('step-versions visual', () => {
  for (const { width, theme } of MATRIX) {
    test(`w${width}-${theme}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await applyTheme(page, theme);
      // 直接 goto /projects/1/versions 会被 ProjectLayout.loadProject 自动前跳到预览
      // (VERSIONS_READY 的 activeStep=预览 > 路由步骤),故走用户真实路径:先落预览页再点步骤 rail
      await page.goto('/projects/1/preview');
      await expect(page.locator('.wenyan-preview').first()).toBeVisible();
      await page.locator('nav.steps-nav .step-item').filter({ hasText: '版本' }).click();
      await expect(page).toHaveURL(/\/projects\/1\/versions/);
      await expect(page.locator('.step-body.versions-step')).toBeVisible();
      await expect(page.locator('text=版本一').first()).toBeVisible();
      await prepareStable(page);
      await expect(page).toHaveScreenshot(`step-versions-${width}-${theme}.png`);
    });
  }
});
