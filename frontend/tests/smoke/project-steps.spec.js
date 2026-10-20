import { test, expect } from '../fixtures/index.js';
import { SMOKE_VIEWPORT } from '../fixtures/matrix.js';
import { DATA } from '../fixtures/data.js';

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

// 契约:BYD 面板只属于小程序三步工作台；公众号发布页不得混入。
test('BYD panel is not mounted on the gzh publish step', async ({ page }) => {
  await page.setViewportSize(SMOKE_VIEWPORT)
  await page.goto('/projects/1/publish')
  await expect(page.locator('.step-body.publish-step')).toBeVisible()
  await expect(page.locator('.byd-panel')).toHaveCount(0)
})

// 契约:BYD 状态接口返回 R<T> 外层信封；成功态 result.draftId 缺失时必须回退 state.lastDraftId。
test('BYD panel is mounted on byd-push and falls back to state.lastDraftId', async ({ page }) => {
  await page.route('**/api/projects/1', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 0,
      msg: 'ok',
      data: { ...DATA.projectDetail, channel: 'byd_miniapp', status: 'DRAFT' },
    }),
  }))
  await page.route('**/api/projects/1/byd-dynasty/state', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 0,
      msg: 'ok',
      data: {
        projectId: 1,
        agentConfigured: true,
        agentOnline: true,
        agentPreflightReady: true,
        agentPreflight: {
          ready: true,
          checks: { wechatProcessesRunning: [], mmkvDraftCount: 2 },
          errors: [],
        },
        agentDisabledReason: null,
        agentId: 'agent-local',
        agentMachineName: 'dev-pc',
        agentVersion: '1.1.0',
        circleConfigured: true,
        circleId: 'circle-1',
        circleTitle: '王朝车友会',
        publishedAt: '2026-10-20T18:35:14+08:00',
        lastJobId: 'job-1',
        lastDraftId: 'draft-from-state',
        lastError: null,
        latestJob: { jobId: 'job-1', status: 'SUCCESS', result: null },
      },
    }),
  }))
  await page.setViewportSize(SMOKE_VIEWPORT)
  await page.goto('/projects/1/byd-push')
  await expect(page).toHaveURL(/\/projects\/1\/byd-push/)
  await expect(page.locator('.byd-panel')).toBeVisible()
  await expect(page.getByText('草稿 ID：draft-from-state', { exact: true })).toBeVisible()
})
// 契约:已配置但 Agent 离线时必须明确提示离线，不得误报「服务端缺少配置」。
test('BYD panel reports configured agent offline instead of disabled', async ({ page }) => {
  await page.route('**/api/projects/1', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 0,
      msg: 'ok',
      data: { ...DATA.projectDetail, channel: 'byd_miniapp', status: 'DRAFT' },
    }),
  }))
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
        publishedAt: null,
        lastDraftId: null,
        lastError: null,
        latestJob: null,
      },
    }),
  }))
  await page.setViewportSize(SMOKE_VIEWPORT)
  await page.goto('/projects/1/byd-push')
  await expect(page.getByText('本机 Agent 离线')).toBeVisible()
  await expect(page.getByText('BYD 推送通道未启用')).toHaveCount(0)
  await expect(page.getByText('Agent 已在线且本地预检通过，可以创建推送任务。')).toHaveCount(0)
})

// 契约:成功态优先展示任务结果 draftId，state.lastDraftId 仅作为旧响应回退。
test('BYD panel prefers job result draftId over state.lastDraftId', async ({ page }) => {
  await page.route('**/api/projects/1', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 0,
      msg: 'ok',
      data: { ...DATA.projectDetail, channel: 'byd_miniapp', status: 'DRAFT' },
    }),
  }))
  await page.route('**/api/projects/1/byd-dynasty/state', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 0,
      msg: 'ok',
      data: {
        projectId: 1,
        agentConfigured: true,
        agentOnline: true,
        agentPreflightReady: true,
        agentPreflight: {
          ready: true,
          checks: { wechatProcessesRunning: [], mmkvDraftCount: 2 },
          errors: [],
        },
        agentDisabledReason: null,
        agentId: 'agent-local',
        agentMachineName: 'dev-pc',
        agentVersion: '1.1.0',
        circleConfigured: true,
        circleId: 'circle-1',
        circleTitle: '王朝车友会',
        publishedAt: '2026-10-20T18:35:14+08:00',
        lastDraftId: 'stale-draft-from-state',
        lastError: null,
        latestJob: { jobId: 'job-2', status: 'SUCCESS', result: { draftId: 'draft-from-job' } },
      },
    }),
  }))
  await page.setViewportSize(SMOKE_VIEWPORT)
  await page.goto('/projects/1/byd-push')
  await expect(page.getByText('草稿 ID：draft-from-job', { exact: true })).toBeVisible()
  await expect(page.getByText('草稿 ID：stale-draft-from-state', { exact: true })).toHaveCount(0)
})

for (const scenario of [
  {
    name: 'does not reuse lastDraftId when the job id does not match',
    lastJobId: 'older-job',
    result: null,
  },
  {
    name: 'does not reuse lastDraftId when the current result has no draftId',
    lastJobId: 'job-2',
    result: { draftCount: 6 },
  },
]) {
  test(`BYD panel ${scenario.name}`, async ({ page }) => {
    await page.route('**/api/projects/1', route => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        msg: 'ok',
        data: { ...DATA.projectDetail, channel: 'byd_miniapp', status: 'DRAFT' },
      }),
    }))
    await page.route('**/api/projects/1/byd-dynasty/state', route => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        msg: 'ok',
        data: {
          projectId: 1,
          agentConfigured: true,
          agentOnline: true,
          agentPreflightReady: true,
          agentPreflight: {
            ready: true,
            checks: { wechatProcessesRunning: [], mmkvDraftCount: 2 },
            errors: [],
          },
          agentDisabledReason: null,
          agentId: 'agent-local',
          agentMachineName: 'dev-pc',
          agentVersion: '1.1.0',
          circleConfigured: true,
          circleId: 'circle-1',
          circleTitle: '王朝车友会',
          publishedAt: '2026-10-20T18:35:14+08:00',
          lastJobId: scenario.lastJobId,
          lastDraftId: 'stale-draft-from-older-job',
          lastError: null,
          latestJob: { jobId: 'job-2', status: 'SUCCESS', result: scenario.result },
        },
      }),
    }))
    await page.setViewportSize(SMOKE_VIEWPORT)
    await page.goto('/projects/1/byd-push')
    await expect(page.getByText('草稿 ID：-', { exact: true })).toBeVisible()
    await expect(page.getByText('草稿 ID：stale-draft-from-older-job', { exact: true })).toHaveCount(0)
  })
}
