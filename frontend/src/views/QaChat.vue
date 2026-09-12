<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header">
        <div>
          <span class="page-kicker">Knowledge QA</span>
          <h2>知识问答</h2>
        </div>
        <div class="actions">
          <el-button type="primary" @click="onNewSession">
            <el-icon class="btn-icon"><Plus /></el-icon>新建会话
          </el-button>
        </div>
      </div>

      <div class="qa-layout">
        <!-- 左：会话列表（三态） -->
        <aside class="qa-side">
          <div v-if="sessionsLoading" class="side-state"><el-skeleton :rows="4" animated /></div>
          <div v-else-if="sessionsError" class="side-state state-error">
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
              @click="selectSession(s.id)">
              <div class="session-title">{{ s.title || '新会话' }}</div>
              <div class="session-meta">{{ fmtTime(s.updatedAt) }}</div>
              <el-button class="session-del" size="small" text type="danger" @click.stop="onDeleteSession(s)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </li>
          </ul>
        </aside>

        <!-- 右：对话流 -->
        <section class="qa-main">
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
                    <div class="bubble-text">{{ m.content }}</div>
                    <CitationList
                      v-if="m.role === 'assistant'"
                      :citations="m.citations"
                      :rag-status="m.ragStatus" />
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
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Delete, WarningFilled, Loading, Promotion } from '@element-plus/icons-vue'
import TopBar from '../layouts/TopBar.vue'
import CitationList from './project/deep/CitationList.vue'
import { qaApi } from '../api'

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
.qa-layout {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 16px;
  align-items: start;
}

/* 会话列表 */
.qa-side {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--card);
  min-height: 480px;
  padding: 8px;
}
.side-state { padding: 12px; }
.session-list { list-style: none; margin: 0; padding: 0; }
.session-item {
  position: relative;
  padding: 10px 34px 10px 12px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background .15s;
  min-height: 44px;
}
.session-item:hover { background: var(--brand-weak); }
.session-item.active { background: var(--brand-weak); }
.session-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-meta { font-size: 12px; color: var(--faint); margin-top: 2px; }
.session-del { position: absolute; right: 2px; top: 50%; transform: translateY(-50%); }

/* 对话区 */
.qa-main {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--card);
  display: flex;
  flex-direction: column;
  min-height: 520px;
  max-height: calc(100vh - 220px);
}
.chat-flow { flex: 1; overflow-y: auto; padding: 16px; }
.chat-empty { padding: 40px 0; }
.loading { padding: 8px; }
.bubble-row { display: flex; margin-bottom: 14px; }
.bubble-row.is-user { justify-content: flex-end; }
.bubble-row.is-assistant { justify-content: flex-start; }
.bubble {
  max-width: 82%;
  padding: 10px 14px;
  border-radius: var(--radius);
  font-size: 14px;
  line-height: 1.7;
}
.bubble-user {
  background: var(--brand);
  color: #fff;
  border-bottom-right-radius: 4px;
}
.bubble-assistant {
  background: var(--paper);
  color: var(--ink);
  border: 1px solid var(--line);
  border-bottom-left-radius: 4px;
  width: 82%;
}
.bubble-text { white-space: pre-wrap; word-break: break-word; }
.thinking { margin-left: 8px; color: var(--muted); font-size: 13px; }
.spin { animation: spin 1s linear infinite; vertical-align: middle; }
@keyframes spin { to { transform: rotate(360deg); } }

.chat-input {
  border-top: 1px solid var(--line);
  padding: 10px;
  display: flex;
  gap: 10px;
  align-items: flex-end;
}
.chat-input :deep(.el-textarea__inner) { min-height: 52px !important; }
.send-btn { min-height: 44px; flex: 0 0 auto; }

/* 三态错误块 */
.state-error { text-align: center; padding: 24px 8px; }
.state-title { font-weight: 600; margin: 8px 0 4px; }
.state-msg { color: var(--muted); font-size: 13px; margin-bottom: 12px; }
.btn-icon { margin-right: 4px; }

@media (max-width: 768px) {
  .qa-layout { grid-template-columns: 1fr; }
  .qa-side { min-height: auto; }
  .session-list { max-height: 220px; overflow-y: auto; }
  .qa-main { min-height: 420px; max-height: none; }
  .bubble, .bubble-assistant { max-width: 92%; width: auto; }
  .chat-input { flex-direction: column; align-items: stretch; }
  .send-btn { width: 100%; }
  .session-item { min-height: 44px; }
}
</style>
