import { test, expect } from '../fixtures/index.js';
import { SMOKE_VIEWPORT } from '../fixtures/matrix.js';

// 知识中心信息架构收敛后,问答并入「知识中心 → 检索问答」tab(顶级 /qa 已移除)
const gotoQa = async (page) => {
  await page.goto('/knowledge');
  await page.getByRole('tab', { name: '检索问答' }).click();
};

test('qa chat send and IME safety', async ({ page }) => {
  await page.setViewportSize(SMOKE_VIEWPORT);
  await gotoQa(page);
  await page.locator('button:has-text("新建会话")').first().click();
  const ta = page.locator('.chat-input textarea');
  await expect(ta).toBeEnabled();
  await ta.fill('你好');

  // 组字中回车=选词,不得发送
  await ta.evaluate((el) => {
    const ev = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
    Object.defineProperty(ev, 'isComposing', { value: true });
    el.dispatchEvent(ev);
  });
  await page.waitForTimeout(100);
  await expect(page.locator('.bubble-row')).toHaveCount(0);
  await expect(ta).toHaveValue('你好');

  // 组字结束后回车=发送
  await ta.press('Enter');
  await expect(page.locator('.bubble-assistant')).toBeVisible();
  await expect(ta).toHaveValue('');
});

// R4:会话分栏折叠/展开(useSplitPane + v-show 契约)
test('qa chat session pane collapses and expands', async ({ page }) => {
  await page.setViewportSize(SMOKE_VIEWPORT);
  await gotoQa(page);
  await expect(page.locator('.session-item').first()).toContainText('测试会话');

  await page.locator('.side-toggle').click();
  await expect(page.locator('.qa-side')).toBeHidden();
  await expect(page.locator('.side-expand')).toBeVisible();

  await page.locator('.side-expand').click();
  await expect(page.locator('.qa-side')).toBeVisible();
});
