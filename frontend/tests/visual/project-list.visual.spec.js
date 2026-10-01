import { test, expect } from '../fixtures/index.js';
import { MATRIX } from '../fixtures/matrix.js';
import { applyTheme, prepareStable } from '../fixtures/stable.js';

test.describe('project-list visual', () => {
  for (const { width, theme } of MATRIX) {
    test(`w${width}-${theme}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await applyTheme(page, theme);
      await page.goto('/');
      await prepareStable(page);
      await expect(page.locator('.el-table__row')).toHaveCount(2);
      await expect(page).toHaveScreenshot(`project-list-${width}-${theme}.png`);
    });
  }
});
