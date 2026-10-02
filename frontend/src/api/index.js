import http from './http'

// 超时联动:本文件最长的 axios 超时为 300000ms(300s)。产线 nginx 反代的
// proxy_read_timeout/proxy_send_timeout 必须 >= 该值(见 frontend/nginx.conf.template,当前 300s),
// 否则长耗时 AI 接口会被 nginx 先掐断返回 504(前端仍傻等到自身超时)。调整任一侧须联动检查两处。

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
  // 版本生成：body={styleIds:[...]}，每选一个风格生成一版。09-27-gen-async 起接口异步化(毫秒级返回占位),
  // 生成由后台执行,前端靠项目状态轮询翻转刷新,超时收紧为默认量级。
  generateVersions: (id, styleIds) => http.post(`/projects/${id}/generate/versions`, { styleIds }, { timeout: 30000 }),
  listVersions: (id) => http.get(`/projects/${id}/versions`),
  setCurrentVersion: (id, versionId) => http.put(`/projects/${id}/current-version`, null, { params: { versionId } }),
  // S5 发布:参数清单(主题/高亮/默认值 + 通道就绪度 + 历史发布信息)
  publishOptions: (id) => http.get(`/projects/${id}/publish-options`),
  // S5 发布到公众号草稿箱:渲染+上传+发布链路。params={theme?, highlight?, macStyle?, footnote?}
  // 超时必须 >= 后端 WENYAN_MCP_PUBLISH_TIMEOUT_MS(默认 180s)。09-27 实测 /publish 单次可达 43s+,
  // 若前端比后端先放弃,浏览器断开但后端仍在跑并写入草稿 → 前端报失败、草稿却已存在,重试即重复草稿
  // (与「后端 30s 阈值 < 实耗」同一类事故)。取 300s(= 本文件最长值),后端到 180s 会先给出中文超时提示。
  publish: (id, params) => http.post(`/projects/${id}/publish`, null, { params, timeout: 300000 }),
  // 编辑版本标题(S6);body={title}
  saveTitle: (id, versionId, title) =>
    http.put(`/projects/${id}/versions/${versionId}/title`, { title }),
  // 简报阶段点选标题(S6);body={title},空串清除
  setSelectedTitle: (id, title) =>
    http.put(`/projects/${id}/selected-title`, { title }),
  // S9 深度模式:研究计划+反问(clarify 约 10~30s,放宽超时同 generateBrief)。
  // 10-02:后端忽略请求体,一律从项目实体读取主题/内容描述/读者/字数;传空对象仅为占位。
  startDeep: (id) =>
    http.post(`/projects/${id}/deep/clarify`, {}, { timeout: 120000 }),
  // S9 深度模式:锁定反问答案
  submitDeepAnswers: (id, briefId, answers) =>
    http.post(`/projects/${id}/deep/clarify-answer`, { briefId, answers }),
  // S9 深度模式:锁定答案并立即开跑多代理研究(前端轮询 status 展示进度)
  runDeep: (id, briefId) => http.post(`/projects/${id}/deep/run`, { briefId }),
  // S9 深度模式:批量生成深度正文(styleIds[] 一次提交,后端一次 claim + 后台逐风格生成,
  // 09-27-gen-async 异步化——毫秒级返回占位,前端靠轮询状态翻转刷新;timeout 收紧为默认量级)
  generateDeep: (id, briefId, styleIds = []) =>
    http.post(`/projects/${id}/deep/generate`, { briefId, styleIds }, { timeout: 30000 }),
  // S9 深度模式:基于事实手册生成简报(自动生成失败后的手动重试;约十几秒,放宽超时)
  generateDeepBrief: (id, briefId) => http.post(`/projects/${id}/deep/brief`, { briefId }, { timeout: 120000 }),
  // 文章仿写(09-09-article-imitation):分析原文+风格推荐。09-27-gen-async 异步化:毫秒级返回占位,
  // 后台 AI 分析,前端据 project.status(GENERATING_BRIEF→READY)轮询翻转刷新。
  analyzeImitation: (id) => http.post(`/projects/${id}/imitation/analyze`, null, { timeout: 30000 }),
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
  // size 一律传「实际像素」（1024x1024/1536x1024/1024x1536，后端白名单）；比例档→像素映射见 utils/imageGenRatio.js
  // signal?（09-27-img-gen-size-ux）：AbortController 信号，供生成中「取消」；不传=行为不变
  // 注意：取消只是客户端停止等待，**服务端可能仍处理完成并把图入库**（见 AiImageDrawer 的如实告知文案）
  // skipGlobalErrorToast（09-27）：生图/重生成由调用方做错误分层（取消→后端msg→超时→传输层），
  //   必须关掉 http.js 的全局提示，否则超时/断网/取消会同时弹两个 toast（且其中一个是英文框架串）
  generateText: (projectId, prompt, size, n, tags, signal) =>
    http.post('/images/generate-text',
      { projectId: projectId == null || projectId === '' ? null : Number(projectId), prompt, size, n: n == null ? 1 : n, tags: tags?.length ? tags : undefined },
      { timeout: 300000, signal, skipGlobalErrorToast: true }),
  // 图生图（单图库参考图 JSON 路径）：body={projectId?, refImageId, prompt, size?, n?, tags?[]}。
  // 09-26 img2img-multi-ref 起图生图 UI 统一走多图 generateFromImageUpload；此导出保留 API 面，无 UI 调用方。
  generateFromImage: (projectId, refImageId, prompt, size, n, tags) =>
    http.post('/images/generate-from-image',
      { projectId: projectId == null || projectId === '' ? null : Number(projectId),
        refImageId: refImageId == null ? null : Number(refImageId), prompt,
        size, n: n == null ? 1 : n, tags: tags?.length ? tags : undefined },
      { timeout: 300000 }),
  // 图生图（多参考图直传，09-26 img2img-multi-ref）：multipart files[]（本地/粘贴）+ refImageIds[]（图库）
  //  + prompt + projectId? + size? + n? + tags?[]。
  // 参考图不落图库（后端内存校验后字节直传 AI）；总数须 1~4，顺序为「先 files 后 refImageIds」，后端不重排。
  // 字段名须与后端 @RequestParam 完全一致；不要手工设 Content-Type——交给 axios 生成 multipart boundary。
  // signal?（09-27-img-gen-size-ux）：AbortController 信号，供生成中「取消」；不传=行为不变（取消只停客户端等待）
  generateFromImageUpload: (projectId, files, refImageIds, prompt, size, n, tags, signal) => {
    const fd = new FormData()
    ;(files || []).forEach(f => { if (f) fd.append('files', f) })
    ;(refImageIds || []).forEach(id => { if (id != null) fd.append('refImageIds', Number(id)) })
    fd.append('prompt', prompt)
    if (projectId != null && projectId !== '') fd.append('projectId', Number(projectId))
    if (size) fd.append('size', size)
    fd.append('n', n == null ? 1 : n)
    ;(tags || []).forEach(t => { const v = String(t || '').trim(); if (v) fd.append('tags', v) })
    return http.post('/images/generate-from-image-upload', fd, { timeout: 300000, signal, skipGlobalErrorToast: true })
  },
  // 单图标签全量覆盖（09-13 image-tags）：body={tags:[]}，空数组=清空
  updateTags: (imageId, tags) => http.put(`/images/${imageId}/tags`, { tags: tags || [] }),
  // 批量打标/移除（09-13 image-tags）：action=add(补打) / remove(移除)，逐张幂等
  batchTags: (ids, tags, action) => http.post('/images/tags/batch', { ids, tags, action }),
  // 重新生成（S10）：同 prompt/gen_size 产新图（不覆盖源图），返回候选列表
  // skipGlobalErrorToast（09-27）：两个调用方（抽屉内候选重生成 / 图库页卡片重生成）都自带错误提示
  regenerate: (imageId) => http.post(`/images/${imageId}/regenerate`, null, { timeout: 300000, skipGlobalErrorToast: true }),
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
