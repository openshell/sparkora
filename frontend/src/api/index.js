import http from './http'

export const authApi = {
  login: (data) => http.post('/auth/login', data),
  logout: () => http.post('/auth/logout'),
  me: () => http.get('/auth/me')
}

export const projectApi = {
  list: (params) => http.get('/projects', { params }),
  get: (id) => http.get(`/projects/${id}`),
  create: (data) => http.post('/projects', data),
  update: (id, data) => http.put(`/projects/${id}`, data),
  remove: (ids) => http.delete(`/projects/${ids}`),
  // AI 生成耗时可达数十秒，单独放宽超时（覆盖 http.js 默认 30s）
  generateBrief: (id) => http.post(`/projects/${id}/generate/brief`, null, { timeout: 120000 }),
  getBrief: (id) => http.get(`/projects/${id}/brief`),
  // 版本生成耗时较长（N 版 × 单版数秒~数十秒），单独放宽超时
  generateVersions: (id, count = 2) => http.post(`/projects/${id}/generate/versions`, null, { params: { count }, timeout: 300000 }),
  listVersions: (id) => http.get(`/projects/${id}/versions`),
  setCurrentVersion: (id, versionId) => http.put(`/projects/${id}/current-version`, null, { params: { versionId } })
}
