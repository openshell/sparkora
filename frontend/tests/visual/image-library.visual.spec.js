import { test, expect } from '../fixtures/index.js';
import { MATRIX } from '../fixtures/matrix.js';
import { applyTheme, prepareStable } from '../fixtures/stable.js';

test.describe('image-library visual', () => {
  for (const { width, theme } of MATRIX) {
    test(`w${width}-${theme}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await applyTheme(page, theme);
      await page.goto('/images');
      await prepareStable(page);
      await expect(page.locator('text=example.jpg').first()).toBeVisible();
      await expect(page).toHaveScreenshot(`image-library-${width}-${theme}.png`);
    });
  }
});
