import { test as base } from '@playwright/test';
import { DATA } from './data.js';

// 1x1 透明 PNG,用作外链图片/字体的离线兜底(确定性内容,避免外网抖动)
const PNG1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
);

function json(body) {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(body),
  };
}

function rOk(data) {
  return json({ code: 0, msg: 'ok', data });
}

function rFail(code = 404, msg = '未 mock') {
  return json({ code, msg, data: null });
}

export function installMocks(page) {
  return page.route('**/*', (route) => {
    const url = route.request().url();
    const u = new URL(url);
    // 只拦后端 API;vite 自身的 /src/api/*.js 模块请求必须放行,
    // 否则会被当成 API 返回 JSON,浏览器按 module script 的 MIME 校验拒绝加载(应用白屏)
    if (!u.pathname.startsWith('/api/')) {
      // 外链字体/图片(如 mock 里的 example.com)一律用固定 PNG 兜底,
      // 否则截图依赖外网可达性,基线会随机抖动
      if (!['localhost', '127.0.0.1'].includes(u.hostname)) {
        return route.fulfill({ status: 200, contentType: 'image/png', body: PNG1X1 });
      }
      return route.fallback();
    }
    const p = u.pathname;

    // auth
    if (p === '/api/auth/me') return route.fulfill(rOk(DATA.user));
    if (p === '/api/auth/login') return route.fulfill(rOk({ token: DATA.token, ...DATA.user }));
    if (p === '/api/auth/logout') return route.fulfill(rOk(null));

    // 项目
    if (p === '/api/projects') return route.fulfill(rOk({ rows: DATA.projects, total: DATA.projects.length, page: 1, size: 10 }));
    if (p === '/api/projects/1') return route.fulfill(rOk(DATA.projectDetail));
    if (p === '/api/projects/1/brief') return route.fulfill(rOk(null));
    if (p === '/api/projects/1/versions') return route.fulfill(rOk(DATA.projectDetail.versions));
    if (p === '/api/projects/1/versions/101') return route.fulfill(rOk(DATA.projectDetail.versions[0]));
    // 预览配图快照:必须含 currentVersionId,否则预览页判「未找到当前版本」
    if (p === '/api/projects/1/images') return route.fulfill(rOk(DATA.projectImages));
    if (p === '/api/projects/1/publish-options') return route.fulfill(rOk(DATA.previewOptions));

    // 图库(列表 rows/total + 全库标签 + 预览选项)
    if (p === '/api/images') return route.fulfill(rOk(DATA.images));
    if (p === '/api/images/tags') return route.fulfill(rOk(DATA.imageTags));
    if (p === '/api/images/preview-options') return route.fulfill(rOk(DATA.previewOptions));

    // 问答(会话列表/详情/新建/提问)
    if (p === '/api/qa/sessions') {
      if (route.request().method() === 'POST') return route.fulfill(rOk({ id: 1, title: '', updatedAt: '2026-09-30T10:00:00Z' }));
      return route.fulfill(rOk(DATA.qa.sessions));
    }
    if (p === '/api/qa/sessions/1') return route.fulfill(rOk(DATA.qa.sessionDetail));
    if (p === '/api/qa/sessions/1/messages') return route.fulfill(rOk(DATA.qa.ask));

    // 风格库
    if (p === '/api/styles') return route.fulfill(rOk(DATA.styles));

    return route.fulfill(rFail(404, '未 mock: ' + p));
  });
}

export function authed(page) {
  return page.addInitScript((d) => {
    localStorage.setItem('sparkora_token', d.token);
    localStorage.setItem('sparkora_user', JSON.stringify(d.user));
  }, DATA);
}

export const test = base.extend({
  page: async ({ page }, use) => {
    await installMocks(page);
    await authed(page);
    await use(page);
  },
});

export const expect = base.expect;
