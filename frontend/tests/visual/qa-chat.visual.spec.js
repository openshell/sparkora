import { test, expect } from '../fixtures/index.js';
import { MATRIX } from '../fixtures/matrix.js';
import { applyTheme, prepareStable } from '../fixtures/stable.js';

test.describe('qa-chat visual', () => {
  for (const { width, theme } of MATRIX) {
    test(`w${width}-${theme}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await applyTheme(page, theme);
      // 知识中心信息架构收敛后,问答并入「知识中心 → 检索问答」tab(顶级 /qa 已移除)
      await page.goto('/knowledge');
      await page.getByRole('tab', { name: '检索问答' }).click();
      await prepareStable(page);
      await expect(page.locator('.session-item').first()).toContainText('测试会话');
      await expect(page).toHaveScreenshot(`qa-chat-${width}-${theme}.png`);
    });
  }
});
