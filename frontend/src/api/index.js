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
  // 版本生成：body={styleIds:[...]}，每选一个风格生成一版；耗时较长放宽超时
  generateVersions: (id, styleIds) => http.post(`/projects/${id}/generate/versions`, { styleIds }, { timeout: 300000 }),
  listVersions: (id) => http.get(`/projects/${id}/versions`),
  setCurrentVersion: (id, versionId) => http.put(`/projects/${id}/current-version`, null, { params: { versionId } })
}

export const styleApi = {
  list: (enabledOnly) => http.get('/styles', { params: { enabledOnly } }),
  get: (id) => http.get(`/styles/${id}`),
  create: (data) => http.post('/styles', data),
  update: (id, data) => http.put(`/styles/${id}`, data),
  remove: (id) => http.delete(`/styles/${id}`),
  // 提炼入库：body={name, sourceText}，AI 耗时放宽超时
  extract: (name, sourceText) => http.post('/styles/extract', { name, sourceText }, { timeout: 120000 })
}

export const imageApi = {
  // 图库列表（projectId 可选过滤）
  list: (projectId) => http.get('/images', { params: projectId ? { projectId } : {} }),
  // 上传图库图：multipart file + projectId?；projectId 为空/undefined 时不带该字段(全局图库)
  upload: (projectId, file) => {
    const fd = new FormData()
    fd.append('file', file)
    if (projectId != null && projectId !== '') fd.append('projectId', Number(projectId))
    return http.post('/images/upload', fd, { timeout: 120000 })
  },
  // 文生图：body={projectId, prompt, size?}，AI 耗时放宽超时;projectId 一律转数字(路由参数是字符串)
  generateText: (projectId, prompt, size) =>
    http.post('/images/generate-text', { projectId: Number(projectId), prompt, size }, { timeout: 300000 }),
  // 图生图：body={projectId, refImageId, prompt, size?}
  generateFromImage: (projectId, refImageId, prompt, size) =>
    http.post('/images/generate-from-image',
      { projectId: Number(projectId), refImageId: refImageId == null ? null : Number(refImageId), prompt, size },
      { timeout: 300000 }),
  // 配图快照：{images[], currentVersionId, coverImageId, bodyImageIds[]}
  projectImages: (id) => http.get(`/projects/${id}/images`),
  setCover: (id, imageId) => http.post(`/projects/${id}/images/${imageId}/cover`),
  addBodyImage: (id, imageId) => http.post(`/projects/${id}/images/${imageId}/body`, null, { params: { action: 'add' } }),
  removeBodyImage: (id, imageId) => http.post(`/projects/${id}/images/${imageId}/body`, null, { params: { action: 'remove' } }),
  // 完成配图：VERSIONS_READY→IMAGES_READY（幂等）
  completeImages: (id) => http.post(`/projects/${id}/complete-images`),
  // 删除图库图（ADMIN/EDITOR；被引用时后端 400 并提示引用方）
  remove: (imageId) => http.delete(`/images/${imageId}`)
}
