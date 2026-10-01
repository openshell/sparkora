import { test, expect } from '../fixtures/index.js';
import { MATRIX } from '../fixtures/matrix.js';
import { applyTheme, prepareStable } from '../fixtures/stable.js';

test.describe('qa-chat visual', () => {
  for (const { width, theme } of MATRIX) {
    test(`w${width}-${theme}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await applyTheme(page, theme);
      await page.goto('/qa');
      await prepareStable(page);
      await expect(page.locator('.session-item').first()).toContainText('测试会话');
      await expect(page).toHaveScreenshot(`qa-chat-${width}-${theme}.png`);
    });
  }
});
