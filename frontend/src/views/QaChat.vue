<template>
  <!-- 批 2:去掉 .container 窄卡 + 页内页头(标题/新建动作并入上下文条),改为满高主从工作台 -->
  <div class="page qa-page">
    <div ref="layoutRef" class="qa-layout" :class="{ dragging }">
      <!-- 左:会话列表(可折叠 + 可拖拽调宽,记忆 localStorage) -->
      <aside v-show="!sideCollapsed" class="qa-side" :style="{ flex: `0 0 ${sideWidth}px` }">
        <div class="side-head">
          <span class="side-title">会话</span>
          <span class="side-count">{{ sessions.length }}</span>
          <button type="button" class="side-toggle" title="收起会话列表" @click="sideCollapsed = true">
            <el-icon :size="14"><DArrowLeft /></el-icon>
          </button>
        </div>
        <div class="side-body">
          <div v-if="sessionsLoading" class="side-state"><el-skeleton :rows="4" animated /></div>
          <div v-else-if="sessionsError" class="state-error">
            <el-icon :size="28" color="var(--faint)"><WarningFilled /></el-icon>
            <div class="state-title">会话加载失败</div>
            <div class="state-msg">{{ sessionsError }}</div>
            <el-button size="small" type="primary" plain @click="loadSessions">重试</el-button>
          </div>
          <el-empty v-else-if="!sessions.length" class="side-state" description="暂无会话，点「新建会话」开始提问" :image-size="60" />
          <ul v-else class="session-list">
            <li
              v-for="s in sessions"
              :key="s.id"
              class="session-item"
              :class="{ active: s.id === activeId }"
              tabindex="0"
              @click="selectSession(s.id)"
              @keydown.enter="selectSession(s.id)">
              <div class="session-title">{{ s.title || '新会话' }}</div>
              <div class="session-meta">{{ fmtTime(s.updatedAt) }}</div>
              <el-button class="session-del" size="small" text type="danger" title="删除会话" @click.stop="onDeleteSession(s)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </li>
          </ul>
        </div>
      </aside>

      <!-- 分隔条:拖拽 / 方向键调整会话列宽(仅展开时存在) -->
      <div
        v-show="!sideCollapsed"
        class="splitter" role="separator" aria-orientation="vertical" tabindex="0"
        :aria-valuenow="Math.round(sideWidth)" :aria-valuemin="SIDE_MIN" :aria-valuemax="SIDE_MAX"
        title="拖拽(或用方向键)调整会话列表宽度"
        @pointerdown="onPointerDown" @keydown="onKey"
      ></div>

      <!-- 右:对话流 -->
      <section class="qa-main">
        <div class="chat-head">
          <button v-if="sideCollapsed" type="button" class="side-expand" title="展开会话列表" @click="sideCollapsed = false">
            <el-icon :size="14"><DArrowRight /></el-icon>会话
          </button>
          <span class="chat-title">{{ currentTitle }}</span>
          <span class="chat-meta">{{ messages.length }} 条消息</span>
        </div>

        <div ref="scrollRef" class="chat-flow">
          <div v-if="!activeId" class="chat-empty">
            <el-empty description="选择左侧会话，或新建会话开始基于车型 / 新闻 / 通用知识库的问答" />
          </div>
          <template v-else>
            <div v-if="messagesLoading" class="loading"><el-skeleton :rows="5" animated /></div>
            <div v-else-if="!messages.length" class="chat-empty">
              <el-empty description="开始你的第一个问题吧，例如「海狮08的续航是多少？」" />
            </div>
            <template v-else>
              <div
                v-for="m in messages"
                :key="m.id"
                class="bubble-row"
                :class="m.role === 'user' ? 'is-user' : 'is-assistant'">
                <div class="bubble" :class="m.role === 'user' ? 'bubble-user' : 'bubble-assistant'">
                  <div v-if="m.role === 'user'" class="bubble-text">{{ m.content }}</div>
                  <div v-else class="bubble-text markdown-body" v-html="renderMd(m.content)"></div>
                  <CitationList
                    v-if="m.role === 'assistant'"
                    :citations="m.citations"
                    :rag-status="m.ragStatus" />
                    <!-- 答案配图（09-15 qa-auto-illustrate）：只读附加展示，历史消息无 imageRefs 不渲染 -->
                    <div v-if="m.role === 'assistant' && imgRefsOf(m).length" class="qa-imgs">
                      <div
                        v-for="img in imgRefsOf(m)"
                        :key="img.imageId"
                        class="qa-img-cell">
                        <el-image
                          class="qa-img-thumb"
                          :src="img.thumbUrl || img.url"
                          :preview-src-list="[img.url]"
                          :initial-index="0"
                          preview-teleported
                          fit="cover" />
                        <span class="qa-img-title" :title="img.title || ''">{{ img.title || '配图' }}</span>
                        <span class="qa-img-src">{{ img.newsId ? '新闻' : (img.source || '图库') }}</span>
                      </div>
                    </div>
                </div>
              </div>
            </template>
            <div v-if="asking" class="bubble-row is-assistant">
              <div class="bubble bubble-assistant">
                <el-icon class="spin"><Loading /></el-icon>
                <span class="thinking">正在检索知识库并合成答案…</span>
              </div>
            </div>
          </template>
        </div>

        <div class="chat-input">
          <el-input
            v-model="question"
            type="textarea"
            :rows="2"
            resize="none"
            maxlength="2000"
            placeholder="输入问题，Enter 发送，Shift+Enter 换行"
            :disabled="!activeId || asking"
            @keydown.enter="onEnter" />
          <el-button
            type="primary"
            class="send-btn"
            :loading="asking"
            :disabled="!activeId || !question.trim()"
            @click="onSend">
            <el-icon v-if="!asking"><Promotion /></el-icon>
            发送
          </el-button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, watch, onMounted } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import MarkdownIt from 'markdown-it'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Delete, WarningFilled, Loading, Promotion, DArrowLeft, DArrowRight } from '@element-plus/icons-vue'
import CitationList from './project/deep/CitationList.vue'
import { usePageHeader } from '../composables/usePageHeader'
import { useSplitPane } from '../composables/useSplitPane'
import { qaApi } from '../api'

// 助手答案按 Markdown 渲染（html:false 关闭裸 HTML，规避 XSS）；用户消息仍纯文本展示
const md = new MarkdownIt({ html: false, breaks: true, linkify: true })
const renderMd = (src) => { try { return md.render(src || '') } catch { return '' } }

const sessions = ref([])
const sessionsLoading = ref(false)
const sessionsError = ref('')
const activeId = ref(null)

const messages = ref([])
const messagesLoading = ref(false)
const question = ref('')
const asking = ref(false)
const scrollRef = ref(null)

const fmtTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '')

/** 会话列头显示的当前会话名(未选中时占位) */
const currentTitle = computed(() => {
  const s = sessions.value.find((x) => x.id === activeId.value)
  return s ? (s.title || '新会话') : '未选择会话'
})

// ==== 会话列:可拖拽调宽 + 可折叠,均记忆到 localStorage ====
const SIDE_MIN = 200
const SIDE_MAX = 480
const SIDE_KEY = 'sparkora.qaSideWidth'
const COLLAPSE_KEY = 'sparkora.qaSideCollapsed'
const { containerRef: layoutRef, dragging, size: sideWidth, onPointerDown, onKey } =
  useSplitPane({ storageKey: SIDE_KEY, initial: 280, min: SIDE_MIN, max: SIDE_MAX, unit: 'px' })
const sideCollapsed = ref(localStorage.getItem(COLLAPSE_KEY) === '1')
watch(sideCollapsed, (v) => localStorage.setItem(COLLAPSE_KEY, v ? '1' : '0'))

// ==== 上下文条(外壳 topbar):面包屑 + 主 CTA「新建会话」(取代原页头按钮)====
const header = usePageHeader()
if (header) {
  header.crumbs = [{ label: '知识' }, { label: '问答' }]
  header.actions = [{ key: 'new', label: '新建会话', type: 'primary', icon: Plus, onClick: () => onNewSession() }]
}
onBeforeRouteLeave(() => { if (header) { header.crumbs = []; header.actions = [] } })

/**
 * 答案配图（09-15 qa-auto-illustrate）。后端 image_refs 是 JSON 字符串（TEXT 列，同 citations 惯例），
 * 但不同链路/未来可能直接给数组——两种形态都要兼容（同 CitationList 的兼容写法）。
 * 字段名以 QaImageRef record 为准：imageId/url/thumbUrl/title/newsId/source（非实体 id）。
 * 历史消息无 imageRefs → 返回空数组 → v-if 不渲染（行为不变）。
 */
const imgRefsOf = (m) => {
  const raw = m?.imageRefs
  if (Array.isArray(raw)) return raw
  if (typeof raw === 'string' && raw) {
    try { const v = JSON.parse(raw); return Array.isArray(v) ? v : [] } catch { return [] }
  }
  return []
}

const loadSessions = async () => {
  sessionsLoading.value = true
  sessionsError.value = ''
  try {
    const res = await qaApi.listSessions()
    if (res.code === 0) {
      sessions.value = res.data || []
    } else {
      sessionsError.value = res.msg || '加载失败'
    }
  } catch (e) {
    sessionsError.value = e.response?.data?.msg || e.message || '网络异常，请稍后重试'
  } finally {
    sessionsLoading.value = false
  }
}

const scrollToBottom = async () => {
  await nextTick()
  if (scrollRef.value) scrollRef.value.scrollTop = scrollRef.value.scrollHeight
}

const loadMessages = async (id) => {
  messagesLoading.value = true
  messages.value = []
  try {
    const res = await qaApi.getSession(id)
    if (res.code === 0) {
      messages.value = res.data?.messages || []
    } else {
      ElMessage.error(res.msg || '加载会话失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || '加载会话失败')
  } finally {
    messagesLoading.value = false
    scrollToBottom()
  }
}

const selectSession = (id) => {
  if (id === activeId.value) return
  activeId.value = id
  loadMessages(id)
}

const onNewSession = async () => {
  try {
    const res = await qaApi.createSession('')
    if (res.code === 0) {
      await loadSessions()
      activeId.value = res.data.id
      messages.value = []
    } else {
      ElMessage.error(res.msg || '新建会话失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || '新建会话失败')
  }
}

const onDeleteSession = async (s) => {
  try {
    await ElMessageBox.confirm(`确定删除会话「${s.title || '新会话'}」？`, '删除会话', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    const res = await qaApi.removeSession(s.id)
    if (res.code === 0) {
      if (activeId.value === s.id) {
        activeId.value = null
        messages.value = []
      }
      sessions.value = sessions.value.filter((x) => x.id !== s.id)
      ElMessage.success('已删除')
    } else {
      ElMessage.error(res.msg || '删除失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || '删除失败')
  }
}

const onEnter = (e) => {
  // Shift+Enter 换行；Enter 直接发送
  if (e.shiftKey) return
  // isComposing / keyCode 229:中文输入法组字中的回车是「选词」,当发送会把半截拼音发出去
  // (与 ProjectEdit / StepBrief 的 ClarifyForm 同一口径)
  if (e.isComposing || e.keyCode === 229) return
  e.preventDefault()
  onSend()
}

const onSend = async () => {
  const q = question.value.trim()
  if (!q || !activeId.value || asking.value) return
  asking.value = true
  // 乐观回显用户消息
  messages.value.push({ id: `tmp-${Date.now()}`, role: 'user', content: q })
  question.value = ''
  scrollToBottom()
  try {
    const res = await qaApi.ask(activeId.value, q)
    if (res.code === 0) {
      messages.value = messages.value.filter((m) => typeof m.id !== 'string')
      messages.value.push(res.data.userMessage)
      messages.value.push(res.data.assistantMessage)
      // 首问回填标题后刷新列表
      const s = sessions.value.find((x) => x.id === activeId.value)
      if (s && !s.title && res.data.userMessage?.content) {
        s.title = res.data.userMessage.content.slice(0, 50)
      }
    } else {
      ElMessage.error(res.msg || '问答失败')
      messages.value = messages.value.filter((m) => typeof m.id !== 'string')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || e.message || '问答失败')
    messages.value = messages.value.filter((m) => typeof m.id !== 'string')
  } finally {
    asking.value = false
    scrollToBottom()
  }
}

onMounted(loadSessions)
</script>

<style scoped>
/* 批 2:满高主从工作台 —— 左侧会话列(可折叠/调宽)+ 分隔条 + 右侧对话区,各自内部滚动 */
.qa-page { display: flex; flex-direction: column; flex: 1; min-height: 0; }
.qa-layout { display: flex; align-items: stretch; flex: 1; min-height: 420px; }
.qa-layout.dragging { cursor: col-resize; user-select: none; }

/* 会话列表(列头常驻,列表独立滚动) */
.qa-side {
  display: flex; flex-direction: column; min-width: 0; min-height: 0;
  border: 1px solid var(--line); border-radius: var(--radius-md);
  background: var(--card); overflow: hidden;
}
.side-head {
  display: flex; align-items: center; gap: var(--sp-2);
  padding: var(--sp-2) var(--sp-3); border-bottom: 1px solid var(--line);
  background: var(--n-50); flex: none;
}
.side-title { font-size: var(--fs-12); font-weight: 700; color: var(--ink); }
.side-count {
  font-size: var(--fs-11); color: var(--muted); font-family: var(--font-mono);
  padding: 0 var(--sp-2); border: 1px solid var(--line); border-radius: 999px; background: var(--card);
}
.side-toggle {
  margin-left: auto; display: inline-flex; align-items: center; justify-content: center;
  width: var(--control-h-sm); height: var(--control-h-sm);
  border: 1px solid var(--line); border-radius: var(--radius-sm);
  background: var(--card); color: var(--muted); cursor: pointer;
}
.side-toggle:hover { color: var(--brand); border-color: var(--brand); }
.side-body { flex: 1; min-height: 0; overflow-y: auto; padding: var(--sp-2); }
.side-state { padding: var(--sp-4); }
.session-list { list-style: none; margin: 0; padding: 0; }
.session-item {
  position: relative;
  padding: var(--sp-3) var(--sp-8) var(--sp-3) var(--sp-4);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background .15s, color .15s ease;
  min-height: var(--control-h-lg);
}
.session-item:hover { background: var(--n-50); }
.session-item:focus-visible { outline: none; box-shadow: var(--focus-ring); }
.session-item.active { background: var(--brand-weak); }
.session-title {
  font-size: var(--fs-13); font-weight: 600; color: var(--ink);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.session-meta { font-size: var(--fs-12); color: var(--faint); margin-top: var(--sp-1); }
.session-del { position: absolute; right: var(--sp-1); top: 50%; transform: translateY(-50%); }

/* 分隔条:细竖线,hover/聚焦变品牌色(与 StepPreview 同型) */
.splitter { position: relative; flex: 0 0 6px; cursor: col-resize; touch-action: none; }
.splitter::after {
  content: ""; position: absolute; top: 0; bottom: 0; left: 2px; width: 2px;
  background: var(--line); transition: background .15s ease;
}
.splitter:hover::after, .splitter:focus-visible::after { background: var(--brand); }

/* 对话区 */
.qa-main {
  flex: 1; min-width: 0; min-height: 0;
  display: flex; flex-direction: column;
  border: 1px solid var(--line); border-radius: var(--radius-md);
  background: var(--card); overflow: hidden;
}
.chat-head {
  display: flex; align-items: center; gap: var(--sp-3);
  padding: var(--sp-2) var(--sp-5); border-bottom: 1px solid var(--line);
  background: var(--n-50); flex: none;
}
.chat-title { font-size: var(--fs-13); font-weight: 700; color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat-meta { margin-left: auto; font-size: var(--fs-12); color: var(--faint); white-space: nowrap; }
.side-expand {
  display: inline-flex; align-items: center; gap: var(--sp-1); flex: none;
  height: var(--control-h-sm); padding: 0 var(--sp-2);
  border: 1px solid var(--line); border-radius: var(--radius-sm);
  background: var(--card); color: var(--muted); font-size: var(--fs-12); cursor: pointer;
}
.side-expand:hover { color: var(--brand); border-color: var(--brand); }

.chat-flow { flex: 1; min-height: 0; overflow-y: auto; padding: var(--sp-5); }
.chat-empty { padding: var(--sp-8) 0; }
.loading { padding: var(--sp-2); }
.bubble-row { display: flex; margin-bottom: var(--sp-5); }
.bubble-row.is-user { justify-content: flex-end; }
.bubble-row.is-assistant { justify-content: flex-start; }
.bubble {
  max-width: 82%;
  padding: var(--sp-3) var(--sp-5);
  border-radius: var(--radius-md);
  font-size: var(--fs-14); line-height: var(--lh-14);
}
.bubble-user {
  background: var(--brand);
  color: #fff;
  border-bottom-right-radius: var(--radius-xs);
}
.bubble-assistant {
  background: var(--paper);
  color: var(--ink);
  border: 1px solid var(--line);
  border-bottom-left-radius: var(--radius-xs);
  width: 82%;
}
.bubble-text { white-space: pre-wrap; word-break: break-word; }

/* 助手 Markdown 渲染：段落/列表/代码/引用/表格（html:false 已关裸 HTML） */
.markdown-body { white-space: normal; }
.markdown-body > :first-child { margin-top: 0; }
.markdown-body > :last-child { margin-bottom: 0; }
.markdown-body p { margin: 0 0 var(--sp-2); }
.markdown-body h1, .markdown-body h2, .markdown-body h3,
.markdown-body h4, .markdown-body h5, .markdown-body h6 {
  margin: var(--sp-4) 0 var(--sp-2); line-height: 1.4; font-weight: 600;
}
.markdown-body h1 { font-size: var(--fs-18); }
.markdown-body h2 { font-size: var(--fs-16); }
.markdown-body h3 { font-size: var(--fs-14); }
.markdown-body ul, .markdown-body ol { margin: 0 0 var(--sp-2); padding-left: var(--sp-6); }
.markdown-body li { margin: var(--sp-1) 0; }
.markdown-body a { color: var(--brand); text-decoration: underline; }
.markdown-body blockquote {
  margin: var(--sp-2) 0; padding: var(--sp-1) var(--sp-3);
  border-left: 3px solid var(--line-strong); color: var(--muted);
  background: var(--n-50);
}
.markdown-body code {
  font-family: var(--font-mono);
  font-size: var(--fs-12); padding: 1px var(--sp-1); border-radius: var(--radius-xs);
  background: var(--n-100); color: var(--ink);
}
.markdown-body pre {
  margin: var(--sp-2) 0; padding: var(--sp-3) var(--sp-4); border-radius: var(--radius-md);
  background: var(--n-900); color: var(--n-25);
  overflow-x: auto; white-space: pre;
}
.markdown-body pre code { background: transparent; color: inherit; padding: 0; }
.markdown-body table {
  border-collapse: collapse; margin: var(--sp-2) 0; width: 100%; font-size: var(--fs-13);
  display: block; overflow-x: auto;
}
.markdown-body th, .markdown-body td {
  border: 1px solid var(--line-strong); padding: var(--sp-1) var(--sp-2); text-align: left;
}
.markdown-body th { background: var(--n-50); font-weight: 600; }
.markdown-body hr { border: none; border-top: 1px solid var(--line); margin: var(--sp-4) 0; }
.markdown-body img { max-width: 100%; border-radius: var(--radius-md); }

/* 答案配图行（09-15 qa-auto-illustrate）：横向滚动缩略图，点击 el-image 预览原图 */
.qa-imgs {
  display: flex;
  gap: var(--sp-2);
  margin-top: var(--sp-3);
  padding-top: var(--sp-2);
  border-top: 1px dashed var(--line);
  overflow-x: auto;
}
.qa-img-cell {
  flex: 0 0 auto;
  width: 96px;
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
}
.qa-img-thumb {
  width: 96px;
  height: 96px;
  border-radius: var(--radius-md);
  border: 1px solid var(--line);
  cursor: pointer;
  display: block;
}
.qa-img-title {
  font-size: var(--fs-12);
  color: var(--muted);
  line-height: 1.3;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.qa-img-src { font-size: var(--fs-11); color: var(--faint); }

.thinking { margin-left: var(--sp-2); color: var(--muted); font-size: var(--fs-13); }
.spin { animation: spin 1s linear infinite; vertical-align: middle; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .spin { animation: none; } }

.chat-input {
  border-top: 1px solid var(--line);
  padding: var(--sp-3) var(--sp-5);
  display: flex;
  gap: var(--sp-3);
  align-items: flex-end;
  flex: none;
}
.chat-input :deep(.el-textarea__inner) { min-height: 52px !important; }
.send-btn { flex: 0 0 auto; }

/* 三态错误块 */
.state-error { text-align: center; padding: var(--sp-6) var(--sp-2); }
.state-title { font-weight: 600; margin: var(--sp-2) 0 var(--sp-1); }
.state-msg { color: var(--muted); font-size: var(--fs-13); margin-bottom: var(--sp-4); }
</style>
