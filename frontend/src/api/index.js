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
  setCurrentVersion: (id, versionId) => http.put(`/projects/${id}/current-version`, null, { params: { versionId } }),
  // S5 发布:参数清单(主题/高亮/默认值 + 通道就绪度 + 历史发布信息)
  publishOptions: (id) => http.get(`/projects/${id}/publish-options`),
  // S5 发布到公众号草稿箱:渲染+上传+发布链路约十几秒,放宽超时(同 generateBrief);params={theme?, highlight?, macStyle?, footnote?}
  publish: (id, params) => http.post(`/projects/${id}/publish`, null, { params, timeout: 120000 }),
  // 编辑版本标题(S6);body={title}
  saveTitle: (id, versionId, title) =>
    http.put(`/projects/${id}/versions/${versionId}/title`, { title }),
  // 简报阶段点选标题(S6);body={title},空串清除
  setSelectedTitle: (id, title) =>
    http.put(`/projects/${id}/selected-title`, { title }),
  // S9 深度模式:研究计划+反问(clarify 约 10~30s,放宽超时同 generateBrief)
  startDeep: (id, topic, extraInfo = '') =>
    http.post(`/projects/${id}/deep/clarify`, { topic, extraInfo }, { timeout: 120000 }),
  // S9 深度模式:锁定反问答案
  submitDeepAnswers: (id, briefId, answers) =>
    http.post(`/projects/${id}/deep/clarify-answer`, { briefId, answers }),
  // S9 深度模式:锁定答案并立即开跑多代理研究(前端轮询 status 展示进度)
  runDeep: (id, briefId) => http.post(`/projects/${id}/deep/run`, { briefId }),
  // S9 深度模式:基于事实手册生成深度正文(styleId 由后端回查风格表注入 system prompt,09-10-style-library-enhance)
  generateDeep: (id, briefId, styleId = null) =>
    http.post(`/projects/${id}/deep/generate`, { briefId, styleId }, { timeout: 300000 }),
  // S9 深度模式:基于事实手册生成简报(自动生成失败后的手动重试;约十几秒,放宽超时)
  generateDeepBrief: (id, briefId) => http.post(`/projects/${id}/deep/brief`, { briefId }, { timeout: 120000 }),
  // 文章仿写(09-09-article-imitation):分析原文+风格推荐(一次 AI 调用,约 10~30s,放宽超时)
  analyzeImitation: (id) => http.post(`/projects/${id}/imitation/analyze`, null, { timeout: 120000 }),
  // 文章仿写:取分析+风格推荐(无则 data=null)
  getImitation: (id) => http.get(`/projects/${id}/imitation`),
  // 09-11-preview-publish-bridge:保存预览页样式(主题/高亮/Mac/脚注,项目级);body={theme?,highlight?,macStyle?,footnote?}
  savePreviewStyle: (id, data) => http.put(`/projects/${id}/preview-style`, data),
  // 09-11-preview-publish-bridge:保存发布元信息(作者/原文地址,项目级);body={author?,sourceUrl?}
  savePublishMeta: (id, data) => http.put(`/projects/${id}/publish-meta`, data),
  // 09-15 article-auto-illustrate 子C:配图建议(按段落锚点语义检索图库,零副作用——不写正文/body_image_ids)。
  // body={tags?[], minScore?};data=[{anchorKey,anchorIndex,headingPath,anchorText,candidates[ImageSearchHit]}]
  // 逐锚点 embedding 检索,放宽超时
  illustrationSuggestions: (id, data) =>
    http.post(`/projects/${id}/illustration-suggestions`, data || {}, { timeout: 120000 }),
  // 忽略某锚点建议组(该锚点后续不再推荐;幂等);body={anchorKey}
  dismissIllustration: (id, anchorKey) =>
    http.post(`/projects/${id}/illustration-suggestions/dismiss`, { anchorKey })
}

export const styleApi = {
  list: (enabledOnly) => http.get('/styles', { params: { enabledOnly } }),
  get: (id) => http.get(`/styles/${id}`),
  create: (data) => http.post('/styles', data),
  update: (id, data) => http.put(`/styles/${id}`, data),
  remove: (id) => http.delete(`/styles/${id}`),
  // 提炼入库：body={name, sourceText}，AI 耗时放宽超时
  extract: (name, sourceText) => http.post('/styles/extract', { name, sourceText }, { timeout: 120000 }),
  // 两步式提炼预览：AI 提炼不入库(id=null)，回填表单人工修改后再走 create；超时同 extract
  extractPreview: (name, sourceText) =>
    http.post('/styles/extract/preview', { name, sourceText }, { timeout: 120000 })
}

export const carApi = {
  // 车型知识库（S6 RAG）
  list: () => http.get('/car/models'),
  detail: (id, versionId) => http.get(`/car/models/${id}`, { params: versionId ? { versionId } : {} }),
  // 官网车型目录（供同步页手动选择）
  catalog: () => http.get('/car/catalog'),
  // 创建同步任务：body={goodsIds:[...]}，异步执行，返回 {jobId}
  createJob: (goodsIds) => http.post('/car/sync/jobs', { goodsIds }),
  // 查询同步任务进度
  getJob: (id) => http.get(`/car/sync/jobs/${id}`),
  // 同步任务历史
  listJobs: () => http.get('/car/sync/jobs'),
  // 重试任务失败项，返回新任务 {jobId}
  retryJob: (id) => http.post(`/car/sync/jobs/${id}/retry`),
  // 同步单个车型（详情页用，同步阻塞）
  syncOne: (id) => http.post(`/car/models/${id}/sync`, null, { timeout: 300000 }),
  // 批量重建全部车型向量（ADMIN，一次性运维；耗时=车型数×块数×embedding，放宽超时）
  rebuildAll: () => http.post('/car/models/rebuild-all', null, { timeout: 300000 }),
  remove: (id) => http.delete(`/car/models/${id}`),
  // 内部问答检索：body={modelId, query, topK?}
  rag: (modelId, query, topK) => http.post('/car/rag', { modelId, query, topK }, { timeout: 120000 })
}

// C2/C3 新闻知识域：浏览（读三角色）；同步任务创建/重试（ADMIN/EDITOR）
export const newsApi = {
  // 分页列表：?page&size&keyword → R<PageResult<NewsEntity>>（rows/total/page/size，列表不含正文）
  list: (params) => http.get('/news', { params }),
  // 详情（含正文 content）
  get: (id) => http.get(`/news/${id}`),
  // 创建同步任务：body={jobType:"FULL"|"INCREMENT"}，返回 {jobId}
  createJob: (jobType) => http.post('/news/sync/jobs', { jobType }),
  // 查询同步任务进度
  getJob: (id) => http.get(`/news/sync/jobs/${id}`),
  // 同步任务历史
  listJobs: () => http.get('/news/sync/jobs'),
  // 重试任务失败项，返回新任务 {jobId}
  retryJob: (id) => http.post(`/news/sync/jobs/${id}/retry`)
}

// S7 通用汽车知识库（KbLibrary 换封装用；行为与原先直调 http 完全一致）
export const kbApi = {
  list: () => http.get('/kb/docs'),
  get: (id) => http.get(`/kb/docs/${id}`),
  create: (data) => http.post('/kb/docs', data),
  update: (id, data) => http.put(`/kb/docs/${id}`, data),
  remove: (id) => http.delete(`/kb/docs/${id}`),
  rebuild: (id) => http.post(`/kb/docs/${id}/rebuild`)
}

export const imageApi = {
  // 图库分页列表（S10）：?projectId=&source=&keyword=&tag=&page=&size=，total 供分页
  // 09-13 image-tags：tag 筛选，可与其他筛选组合；rows 每条含 tags[]
  // 09-15 img-classify：tag 可传数组，语义为 AND（须同时具备全部标签）。
  // 注意 axios 默认把数组序列化成 `tag[]=a&tag[]=b`（Spring 不识别），故在此拼成逗号单参数
  //（后端 splitTags 同时支持「重复参数」与「单值内逗号分隔」两种传法）。
  list: (params) => {
    const p = { page: 1, size: 24, ...(params || {}) }
    if (Array.isArray(p.tag)) {
      const v = p.tag.filter(t => t != null && String(t).trim()).map(t => String(t).trim()).join(',')
      p.tag = v || undefined
    }
    return http.get('/images', { params: p })
  },
  // 全库标签清单（09-13 image-tags）：data 直接是 [{name, count}]（count 降序），预选/筛选同源
  listTags: () => http.get('/images/tags'),
  // 图片语义检索（09-15 img-semantic-search 子B）：body={query, topK?, minScore?, tags?[]}，
  // tags 为 AND 预过滤（与 list 同语义）；data=[ImageSearchHit] 按 score 降序（无分页，topK 上限 50）。
  // 命中含 imageId/score/sourceText/fileName/source/sourceRef/url/thumbUrl/tags[]，字段少于图库实体。
  search: (query, opts) => http.post('/images/search', {
    query,
    topK: opts?.topK,
    minScore: opts?.minScore,
    tags: opts?.tags?.length ? opts.tags : undefined
  }, { timeout: 60000 }),
  // 图片来源追溯（09-15 img-classify）：data={sourceRef, news:{id,newsId,title,publishDate,url}|null, imageUrl}
  // 非新闻图（upload/AI 生成图/车型图）返回 news:null，HTTP 200 不报错
  getSource: (imageId) => http.get(`/images/${imageId}/source`),
  // 上传图库图：multipart file + projectId? + tags?（同名多值，逐项 append；空数组不 append）
  upload: (projectId, file, tags) => {
    const fd = new FormData()
    fd.append('file', file)
    if (projectId != null && projectId !== '') fd.append('projectId', Number(projectId))
    ;(tags || []).forEach(t => { const v = String(t || '').trim(); if (v) fd.append('tags', v) })
    return http.post('/images/upload', fd, { timeout: 120000 })
  },
  // 文生图：body={projectId?, prompt, size?, n?, tags?[]}，AI 耗时放宽超时;projectId 一律转数字(路由参数是字符串),null/空 = 全局图库
  generateText: (projectId, prompt, size, n, tags) =>
    http.post('/images/generate-text',
      { projectId: projectId == null || projectId === '' ? null : Number(projectId), prompt, size, n: n == null ? 1 : n, tags: tags?.length ? tags : undefined },
      { timeout: 300000 }),
  // 图生图：body={projectId?, refImageId, prompt, size?, n?, tags?[]}
  generateFromImage: (projectId, refImageId, prompt, size, n, tags) =>
    http.post('/images/generate-from-image',
      { projectId: projectId == null || projectId === '' ? null : Number(projectId),
        refImageId: refImageId == null ? null : Number(refImageId), prompt,
        size, n: n == null ? 1 : n, tags: tags?.length ? tags : undefined },
      { timeout: 300000 }),
  // 单图标签全量覆盖（09-13 image-tags）：body={tags:[]}，空数组=清空
  updateTags: (imageId, tags) => http.put(`/images/${imageId}/tags`, { tags: tags || [] }),
  // 批量打标/移除（09-13 image-tags）：action=add(补打) / remove(移除)，逐张幂等
  batchTags: (ids, tags, action) => http.post('/images/tags/batch', { ids, tags, action }),
  // 重新生成（S10）：同 prompt/gen_size 产新图（不覆盖源图），返回候选列表
  regenerate: (imageId) => http.post(`/images/${imageId}/regenerate`, null, { timeout: 300000 }),
  // 配图快照：{images[], currentVersionId, coverImageId, bodyImageIds[], coverImage?, bodyImages[]}（S10 起 images=引用图集合）
  projectImages: (id) => http.get(`/projects/${id}/images`),
  setCover: (id, imageId) => http.post(`/projects/${id}/images/${imageId}/cover`),
  addBodyImage: (id, imageId) => http.post(`/projects/${id}/images/${imageId}/body`, null, { params: { action: 'add' } }),
  removeBodyImage: (id, imageId) => http.post(`/projects/${id}/images/${imageId}/body`, null, { params: { action: 'remove' } }),
  // 删除图库图（ADMIN/EDITOR；被引用时后端 400 并提示引用方）
  remove: (imageId) => http.delete(`/images/${imageId}`),
  // S4 预览:wenyan 同核渲染(方案 A);params={theme?, highlight?, macStyle?, footnote?}
  preview: (id, params) => http.post(`/projects/${id}/preview`, null, { params }),
  // 保存版本正文(S4 预览页左栏编辑);body={contentMd}
  saveContent: (id, versionId, contentMd) =>
    http.put(`/projects/${id}/versions/${versionId}/content`, { contentMd }),
  // 预览参数清单(主题/高亮/开关默认值,读后端 .env 配置)
  previewOptions: () => http.get('/images/preview-options')
}

// 系统检索设置(09-09-brief-gen-redesign R1):页面控制内部知识库/外部搜索启用;
// 读 ADMIN/EDITOR,写仅 ADMIN
export const settingApi = {
  get: () => http.get('/settings'),
  update: (payload) => http.put('/settings', payload)
}

// C4 多轮对话式知识问答:独立入口,跨三域(CAR/KB/NEWS)检索合成 + 引用
export const qaApi = {
  createSession: (title) => http.post('/qa/sessions', { title }),
  listSessions: () => http.get('/qa/sessions'),
  getSession: (id) => http.get(`/qa/sessions/${id}`),
  // AI 合成耗时长,放宽超时(同 generateBrief)
  ask: (id, question) => http.post(`/qa/sessions/${id}/messages`, { question }, { timeout: 120000 }),
  removeSession: (id) => http.delete(`/qa/sessions/${id}`)
}
