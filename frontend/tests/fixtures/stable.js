// 必须在 page.goto() 之前调用:主题由 main.js 启动时读 localStorage 应用
export async function applyTheme(page, theme) {
  await page.addInitScript((t) => {
    localStorage.setItem('sparkora_theme', t);
  }, theme);
}

export async function prepareStable(page) {
  await page.addStyleTag({
    content: '*{animation:none!important;transition:none!important;caret-color:transparent!important}',
  });
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => document.fonts.ready);
}
