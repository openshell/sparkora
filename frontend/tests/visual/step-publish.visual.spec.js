import { test, expect } from '../fixtures/index.js';
import { MATRIX } from '../fixtures/matrix.js';
import { applyTheme, prepareStable } from '../fixtures/stable.js';

test.describe('step-publish visual', () => {
  for (const { width, theme } of MATRIX) {
    test(`w${width}-${theme}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await applyTheme(page, theme);
      await page.goto('/projects/1/publish');
      await prepareStable(page);
      await expect(page.locator('.step-body.publish-step')).toBeVisible();
      await expect(page.locator('text=版本标题')).toBeVisible();
      await expect(page).toHaveScreenshot(`step-publish-${width}-${theme}.png`);
    });
  }
});
