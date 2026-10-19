import { test, expect } from '../fixtures/index.js';
import { SMOKE_VIEWPORT } from '../fixtures/matrix.js';

test('project steps rail visible on preview', async ({ page }) => {
  await page.setViewportSize(SMOKE_VIEWPORT);
  await page.goto('/projects/1/preview');
  await expect(page.locator('aside.step-rail')).toBeVisible();
  await expect(page.locator('nav.steps-nav .step-item')).toHaveCount(4);
});

// 契约:状态领先于路由时 ProjectLayout 自动前跳到活跃步骤
// (VERSIONS_READY → activeStep=预览,落在 versions 路由会被 replace 到 preview)
test('landing behind schedule auto-advances to active step', async ({ page }) => {
  await page.setViewportSize(SMOKE_VIEWPORT);
  await page.goto('/projects/1/versions');
  await expect(page).toHaveURL(/\/projects\/1\/preview/);
});

// R4:步骤主体容器 .step-body + 上下文条接线(usePageHeader 的 crumbs/actions)
test('step page renders step-body and ctxbar wiring', async ({ page }) => {
  await page.setViewportSize(SMOKE_VIEWPORT);
  await page.goto('/projects/1/publish');
  await expect(page.locator('.step-body.publish-step')).toBeVisible();
  // 契约:面包屑写入上下文条(不再自绘页头)
  await expect(page.locator('.ctxbar .crumb').first()).toBeVisible();
  // 契约:页面级动作注入上下文条(发布步:返回预览 + 确认发布)
  await expect(page.locator('.ctxbar-right button', { hasText: '返回预览' })).toBeVisible();
  await expect(page.locator('.ctxbar-right button', { hasText: '确认发布到草稿箱' })).toBeVisible();
  // 发布摘要命中 mock 数据(标题来自版本 title)
  await expect(page.locator('text=版本标题')).toBeVisible();
});

// 契约:BYD 状态接口返回 R<T> 外层信封,组件必须解包 res.data;
// 已配置但 Agent 离线时只能提示离线,不得误报「服务端缺少配置」。
test('BYD panel unwraps state envelope and reports agent offline', async ({ page }) => {
  await page.route('**/api/projects/1/byd-dynasty/state', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 0,
      msg: 'ok',
      data: {
        projectId: 1,
        agentConfigured: true,
        agentOnline: false,
        agentPreflightReady: false,
        agentDisabledReason: null,
      },
    }),
  }))
  await page.setViewportSize(SMOKE_VIEWPORT)
  await page.goto('/projects/1/publish')
  await expect(page.getByText('本机 Agent 离线')).toBeVisible()
  await expect(page.getByText('BYD 推送通道未启用')).toHaveCount(0)
  await expect(page.getByText('Agent 已在线且本地预检通过，可以创建推送任务。')).toHaveCount(0)
})
