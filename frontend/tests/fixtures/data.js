export const DATA = {
  user: {
    userId: 1,
    username: 'test',
    displayName: '测试',
    role: 'EDITOR',
  },
  token: 'mock-token',
  projects: [
    {
      id: 1,
      topic: '新能源汽车内容策划',
      keywords: '新能源,内容策划',
      status: 'VERSIONS_READY',
      currentVersionId: 101,
      updatedAt: '2026-09-30T10:00:00Z',
      createdBy: 'test',
    },
    {
      id: 2,
      topic: '科技前沿解读',
      keywords: '科技',
      status: 'DRAFT',
      currentVersionId: null,
      updatedAt: '2026-09-30T11:00:00Z',
      createdBy: 'test',
    },
  ],
  projectDetail: {
    id: 1,
    topic: '新能源汽车内容策划',
    status: 'VERSIONS_READY',
    currentVersionId: 101,
    brief: null,
    versions: [
      {
        id: 101,
        // 字段名对齐 ArticleVersionEntity:versionLabel(A/B/C),不是 label!
        versionLabel: '版本一',
        title: '测试标题',
        styleTag: '通俗',
        wordCount: 800,
        aiModel: 'spark-v1',
        contentMd: '# 测试标题\n\n这是预览用的正文内容。',
        htmlContent: '<h1>测试标题</h1><p>这是预览用的正文内容。</p>',
        citations: '[]',
      },
    ],
  },
  projectImages: {
    currentVersionId: 101,
    images: [],
    coverImageId: null,
    bodyImageIds: [],
    coverImage: null,
    bodyImages: [],
  },
  previewOptions: {
    themes: [
      { id: 'default', name: '默认', group: 'builtin', color: '#333', bright: false },
    ],
    highlights: ['github'],
    defaultTheme: 'default',
    highlight: 'github',
    macStyle: false,
    footnote: false,
    // 发布通道检查(ProjectPreviewController.publishOptions 的 publishEnabled)
    publishEnabled: true,
  },
  images: {
    rows: [
      {
        id: 1,
        name: '示例图',
        fileName: 'example.jpg',
        url: 'https://example.com/img1.jpg',
        thumbUrl: 'https://example.com/img1.jpg',
        source: 'upload',
        createdBy: 'test',
        tags: ['示例'],
        createdAt: '2026-09-30T10:00:00Z',
      },
    ],
    total: 1,
    page: 1,
    size: 24,
  },
  imageTags: [{ name: '示例', count: 1 }],
  qa: {
    sessions: [
      { id: 1, title: '测试会话', updatedAt: '2026-09-30T10:00:00Z' },
    ],
    sessionDetail: {
      id: 1,
      title: '测试会话',
      messages: [
        { id: 1, role: 'user', content: '你好' },
        { id: 2, role: 'assistant', content: '这是一条测试回答。', citations: '[]', imageRefs: '[]' },
      ],
    },
    ask: {
      userMessage: { id: 201, role: 'user', content: '你好' },
      assistantMessage: { id: 202, role: 'assistant', content: '这是一条测试回答。', citations: '[]', imageRefs: '[]' },
    },
  },
  styles: [],
};
