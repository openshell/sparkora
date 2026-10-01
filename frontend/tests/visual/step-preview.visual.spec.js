import { test, expect } from '../fixtures/index.js';
import { MATRIX } from '../fixtures/matrix.js';
import { applyTheme, prepareStable } from '../fixtures/stable.js';

test.describe('step-preview visual', () => {
  for (const { width, theme } of MATRIX) {
    test(`w${width}-${theme}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await applyTheme(page, theme);
      await page.goto('/projects/1/preview');
      await prepareStable(page);
      await expect(page.locator('.wenyan-preview').first()).toBeVisible();
      await expect(page).toHaveScreenshot(`step-preview-${width}-${theme}.png`);
    });
  }
});
