<template>
  <div ref="host" class="cm-host"></div>
</template>

<script setup>
/**
 * MarkdownEditor(CodeMirror 6,原版 @wenyan-md/ui 的 MarkdownEditor 同款引擎)。
 * 仅在本组件挂载时动态 import CodeMirror chunk;粘贴图片 → imageApi.upload → 本地 /images/** URL markdown。
 * 衍生自 wenyan 项目(caol64/wenyan-ui,Apache-2.0);本组件为 Vue 3 重写,视觉适配 sparkora 变量。
 */
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { imageApi } from '../api'
import { ElMessage } from 'element-plus'

const props = defineProps({
  modelValue: { type: String, default: '' },
  projectId: { type: [String, Number], default: null }
})
const emit = defineEmits(['update:modelValue', 'ready', 'scroll'])

const host = ref(null)
let view = null
let EditorView = null
let disposers = []

/** 滚动百分比回调(由 makeScrollHandler 调用,再 emit 给父组件)。 */
let onScrollPercent = (p) => { emit('scroll', p) }

const mkTheme = (HighlightStyle, syntaxHighlighting, defaultHighlightStyle) => {
  return [
    EditorView.theme({
      '&': { fontSize: '13px', backgroundColor: 'transparent', color: 'var(--text, #333)', height: '100%' },
      '.cm-content': { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace', lineHeight: '1.7', padding: '12px 0' },
      '.cm-scroller': { overflow: 'auto', height: '100%' },
      '&.cm-focused': { outline: 'none' },
      '.cm-gutters': { backgroundColor: 'transparent', border: 'none', color: 'var(--muted, #999)' },
      '.cm-activeLine': { backgroundColor: 'rgba(0,0,0,0.03)' },
      '.cm-activeLineGutter': { backgroundColor: 'transparent', color: 'var(--text, #333)' }
    }),
    syntaxHighlighting(defaultHighlightStyle, { fallback: true })
  ]
}

const insertAtCursor = (text) => {
  if (!view) return
  const { state } = view
  const pos = state.selection.main.head
  view.dispatch({ changes: { from: pos, insert: text }, selection: { anchor: pos + text.length } })
  view.focus()
}

/** 编辑器滚动 → 上层(百分比,用于与预览同步)。 */
const makeScrollHandler = (EditorView, emit) => {
  return EditorView.domEventHandlers({
    scroll: (event, viewRef) => {
      const scroller = viewRef.scrollDOM
      const max = scroller.scrollHeight - scroller.clientHeight
      const percent = max > 0 ? scroller.scrollTop / max : 0
      // 交给父组件(通过原生事件冒泡替代:在此直接回调 props 事件)
      onScrollPercent(percent)
      return false
    }
  })
}

/** 粘贴图片上传 → 本地 /images/** URL markdown(原版“粘贴即上传”行为)。 */
const makePasteHandler = (EditorView) => {
  return EditorView.domEventHandlers({
    paste: (event) => {
      const files = event.clipboardData?.files
      if (!files || !files.length) return false
      const images = [...files].filter(f => f.type.startsWith('image/'))
      if (!images.length) return false
      event.preventDefault()
      images.forEach(img => {
        if (props.projectId == null || props.projectId === '') {
          ElMessage.warning('项目内才能上传图片,请先保存项目')
          return
        }
        const placeholder = `\n![上传中...](uploading-${Date.now()})\n`
        insertAtCursor(placeholder)
        imageApi.upload(props.projectId, img).then(res => {
          if (res.code !== 0) throw new Error(res.msg || '上传失败')
          // 后端返回图片实体（storagePath 相对路径），本地 /images/** 静态映射访问;
          // 发布/预览组装时由 PreviewService.replaceLocalImages 统一转七牛公网 URL
          const p = res.data?.storagePath
          if (!p) throw new Error('上传返回缺少 storagePath')
          const url = `/images/${p}`
          const body = view.state.doc.toString()
          const idx = body.indexOf(placeholder.trim())
          if (idx >= 0) {
            const md = `![](${url})`
            view.dispatch({ changes: { from: idx, to: idx + placeholder.trim().length, insert: md } })
          }
          ElMessage.success('图片已上传并插入')
        }).catch(e => {
          ElMessage.error(e?.message || '图片上传失败')
          const body = view.state.doc.toString()
          const idx = body.indexOf(placeholder.trim())
          if (idx >= 0) view.dispatch({ changes: { from: idx, to: idx + placeholder.trim().length } })
        })
      })
      return true
    }
  })
}

onMounted(async () => {
  const [{ EditorView: EV }, { markdown, markdownLanguage }, { defaultKeymap, history, historyKeymap }, { keymap }, { HighlightStyle, syntaxHighlighting, defaultHighlightStyle }] = await Promise.all([
    import('@codemirror/view'),
    (async () => {
      const m = await import('@codemirror/lang-markdown')
      const langData = await import('@codemirror/language-data')
      return { markdown: m.markdown, langData }
    })(),
    import('@codemirror/commands'),
    import('@codemirror/view'),
    import('@codemirror/language')
  ])
  EditorView = EV

  const baseTheme = mkTheme(HighlightStyle, syntaxHighlighting, defaultHighlightStyle)
  const updateListener = EditorView.updateListener.of(u => {
    if (u.docChanged) emit('update:modelValue', u.state.doc.toString())
  })

  const [{ basicSetup }] = await Promise.all([import('codemirror')])
  view = new EditorView({
    doc: props.modelValue || '',
    parent: host.value,
    extensions: [
      basicSetup,
      markdown({ codeLanguages: (await import('@codemirror/language-data')).languages }),
      keymap.of([...defaultKeymap, ...historyKeymap]),
      history(),
      makePaste(),
      makeScrollHandler(EV),
      updateListener,
      baseTheme
    ]
  })
  emit('ready', view)
})

function makePaste() {
  return makePasteHandler(EditorView)
}

watch(() => props.modelValue, (v) => {
  if (!view) return
  const cur = view.state.doc.toString()
  if (v !== cur) view.dispatch({ changes: { from: 0, to: cur.length, insert: v || '' } })
})

onBeforeUnmount(() => { view?.destroy(); view = null })
defineExpose({
  insertText: insertAtCursor,
  /** 上层业务插入 markdown(插图面板等):插到当前光标处并聚焦。 */
  insertMd: insertAtCursor,
  focus: () => view?.focus(),
  /** 父组件发起的滚动同步(百分比)。 */
  scrollToPercent: (p) => {
    if (!view) return
    const sc = view.scrollDOM
    sc.scrollTop = p * Math.max(0, sc.scrollHeight - sc.clientHeight)
  }
})
</script>

<style scoped>
.cm-host { height: 100%; overflow: hidden; }
.cm-host :deep(.cm-editor) { height: 100%; }
</style>